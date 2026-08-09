package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors device health and marks devices stale when they stop reporting.
 *
 * Stale thresholds by device type:
 *   - Zigbee sensors:    stale after 10 min (Z2M state pushes are event-driven)
 *   - Cameras/RTSP:      stale after 5 min  (Frigate sends motion events)
 *   - HA polled devices: stale after 15 min (HA bridge polls every ~30s)
 *   - Unknown:           stale after 30 min
 *
 * "Stale" is not the same claim as "actually unreachable" — a device that
 * simply hasn't pushed an update yet isn't necessarily broken, and telling
 * users "OFFLINE" the instant one interval is missed was actively
 * misleading (2026-08-08 user report). {@link CheckinStatus} is the
 * user-facing axis for this: ON_SCHEDULE → LATE (past its interval, not yet
 * confirmed dead — a grace tier, `DeviceStatus.state` is untouched here) →
 * MISSED (past a longer grace multiple; for `ha_rest` devices only after an
 * active poll also failed, and for Zigbee only after retained MQTT
 * availability did not confirm ONLINE; unsupported adapters use time alone).
 * `DeviceStatus.state` itself still only flips to OFFLINE at the MISSED
 * tier, same trigger point as before this change, so nothing that reads
 * `state` needs to change; CheckinStatus is additive.
 *
 * System health summary is exposed via getSystemHealth() for GET /api/system/health.
 */
@Component
public class DeviceHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthMonitor.class);

    // deviceId → when the device first went off-schedule (null if healthy)
    private final Map<String, Instant> staleSince = new ConcurrentHashMap<>();
    // deviceId → last state before going offline
    private final Map<String, String> lastKnownState = new ConcurrentHashMap<>();
    // deviceId → current checkin status, recomputed every cycle
    private final Map<String, CheckinStatus> checkinStatuses = new ConcurrentHashMap<>();

    // Reconnect backoff per-device: attempt count
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter z2mAdapter;

    private static final Duration STALE_ZIGBEE  = Duration.ofMinutes(10);
    private static final Duration STALE_CAMERA  = Duration.ofMinutes(5);
    private static final Duration STALE_HA      = Duration.ofMinutes(15);
    private static final Duration STALE_DEFAULT = Duration.ofMinutes(30);
    private static final Duration MQTT_AVAILABILITY_TIMEOUT = Duration.ofSeconds(2);

    /** How many multiples of the stale threshold a device gets in the LATE grace tier before MISSED. */
    private static final int MISSED_MULTIPLIER = 3;

    public DeviceHealthMonitor(DeviceRegistry registry, Zigbee2MqttAdapter z2mAdapter) {
        this.registry = registry;
        this.z2mAdapter = z2mAdapter;
    }

    /** Runs every 60 seconds. Checks all registered devices for staleness. */
    @Scheduled(fixedDelay = 60_000)
    public void checkHealth() {
        Instant now = Instant.now();
        for (DeviceStatus status : registry.all()) {
            String id = status.deviceId();
            Optional<DeviceDescriptor> descriptor = registry.descriptor(id);

            if (descriptor.isPresent() && !descriptor.get().enabled()) {
                checkinStatuses.put(id, CheckinStatus.NOT_CONFIGURED);
                continue;
            }

            Duration staleThreshold = staleThresholdFor(id, status);
            Duration sinceLastSeen = Duration.between(status.lastSeen(), now);

            if (sinceLastSeen.compareTo(staleThreshold) <= 0) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "on schedule again");
                continue;
            }

            if (tryActiveRecovery(id, descriptor, now)) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "active check confirmed it's actually reachable");
                continue;
            }

            Duration missedThreshold = staleThreshold.multipliedBy(MISSED_MULTIPLIER);
            CheckinStatus classified = classify(sinceLastSeen, staleThreshold, missedThreshold, true);
            checkinStatuses.put(id, classified);

            if (!staleSince.containsKey(id)) {
                staleSince.put(id, now);
                lastKnownState.put(id, status.state());
                log.warn("Device {} went off-schedule after {} (last seen {})", id, sinceLastSeen, status.lastSeen());
            }

            if (classified == CheckinStatus.MISSED && !"OFFLINE".equals(status.state())) {
                Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
                attrs.put("staleSince", staleSince.get(id).toString());
                attrs.put("lastKnownState", lastKnownState.get(id));
                registry.update(new DeviceStatus(
                    id, status.type(), status.name(), "OFFLINE",
                    status.lastSeen(), attrs, status.location()));
            }
            scheduleReconnect(id, status);
        }
    }

    /** Pure classification: given how late a device is, which tier is it in. Package-private for tests. */
    static CheckinStatus classify(Duration sinceLastSeen, Duration staleThreshold, Duration missedThreshold, boolean enabled) {
        if (!enabled) return CheckinStatus.NOT_CONFIGURED;
        if (sinceLastSeen.compareTo(staleThreshold) <= 0) return CheckinStatus.ON_SCHEDULE;
        if (sinceLastSeen.compareTo(missedThreshold) <= 0) return CheckinStatus.LATE;
        return CheckinStatus.MISSED;
    }

    /**
     * Actively verify supported adapters. HA is polled directly. Zigbee asks
     * MQTT for a retained authoritative availability replay and only accepts
     * an explicit ONLINE result; OFFLINE and NO_REPLY fail closed.
     */
    private boolean tryActiveRecovery(String id, Optional<DeviceDescriptor> descriptor, Instant now) {
        String adapterType = descriptor.map(DeviceDescriptor::protocolAdapter).orElse("unknown");
        if ("mqtt".equals(adapterType) && id.startsWith("z2m-")) {
            return z2mAdapter.probeRetainedAvailability(
                descriptor.map(DeviceDescriptor::connectionString).orElse(""),
                MQTT_AVAILABILITY_TIMEOUT).orElse(false);
        }
        if (!"ha_rest".equals(adapterType) && !"rtsp".equals(adapterType)) return false;

        Optional<DeviceStatus> live = registry.activeFetch(id);
        if (live.isEmpty()) return false;

        DeviceStatus fresh = live.get();
        Map<String, Object> freshAttributes = fresh.attributes();
        if ("rtsp".equals(adapterType)) {
            DeviceStatus existing = registry.get(id);
            Map<String, Object> merged = new LinkedHashMap<>();
            if (existing != null) merged.putAll(existing.attributes());
            merged.putAll(fresh.attributes());
            freshAttributes = merged;
        }
        registry.update(new DeviceStatus(
            id, fresh.type(), fresh.name(), fresh.state(), now, freshAttributes, fresh.location()));
        return true;
    }

    private void recoverIfNeeded(String id, String reason) {
        if (staleSince.containsKey(id)) {
            log.info("Device {} recovered — {}", id, reason);
            staleSince.remove(id);
            lastKnownState.remove(id);
            reconnectAttempts.remove(id);
        }
    }

    /**
     * Exponential backoff reconnect for MQTT-based devices.
     * On each stale check cycle: only attempt reconnect on cycles that are
     * a power-of-two multiple of the first-stale time (1min, 2min, 4min, 8min…).
     */
    private void scheduleReconnect(String deviceId, DeviceStatus status) {
        String adapter = registry.descriptor(deviceId)
            .map(d -> d.protocolAdapter())
            .orElse("unknown");
        if (!"mqtt".equals(adapter)) return; // HA/RTSP reconnect is handled by their own adapters

        int attempts = reconnectAttempts.merge(deviceId, 1, Integer::sum);
        // Only log/act on power-of-two attempts to avoid log spam
        if (attempts == 1 || (attempts & (attempts - 1)) == 0) {
            log.info("Z2M device {} off-schedule, reconnect attempt #{} — waiting for Z2M bridge to push state",
                deviceId, attempts);
        }
    }

    private Duration staleThresholdFor(String deviceId, DeviceStatus status) {
        if (deviceId.startsWith("z2m-")) return STALE_ZIGBEE;
        return switch (status.type()) {
            case CAMERA -> STALE_CAMERA;
            case THERMOSTAT, LOCK, SMOKE_ALARM, CO_ALARM,
                 DISHWASHER, WASHING_MACHINE, DRYER, POWER_METER -> STALE_HA;
            default -> STALE_DEFAULT;
        };
    }

    /** Returns a structured health summary for GET /api/system/health. */
    public Map<String, Object> getSystemHealth() {
        List<DeviceStatus> all = registry.all();
        long online  = all.stream().filter(d -> "ONLINE".equals(d.state())).count();
        long offline = all.stream().filter(d -> "OFFLINE".equals(d.state())).count();
        long alertEligibleOffline = all.stream().filter(this::isAlertEligibleOffline).count();
        long alarm   = all.stream().filter(d -> "ALARM".equals(d.state())).count();
        long unknown = all.stream().filter(d -> "UNKNOWN".equals(d.state())).count();

        List<Map<String, Object>> staleDevices = staleSince.entrySet().stream()
            .map(e -> Map.<String, Object>of(
                "deviceId", e.getKey(),
                "staleSince", e.getValue().toString(),
                "lastKnownState", lastKnownState.getOrDefault(e.getKey(), "UNKNOWN"),
                "checkinStatus", checkinStatuses.getOrDefault(e.getKey(), CheckinStatus.LATE).name()
            ))
            .toList();

        Map<String, Long> checkinCounts = new LinkedHashMap<>();
        for (CheckinStatus s : CheckinStatus.values()) {
            checkinCounts.put(s.name(), checkinStatuses.values().stream().filter(v -> v == s).count());
        }

        return Map.of(
            "total", all.size(),
            "online", online,
            "offline", offline,
            "alertEligibleOffline", alertEligibleOffline,
            "alarm", alarm,
            "unknown", unknown,
            "zigbeeBridge", z2mAdapter.getBridgeState(),
            "staleDevices", staleDevices,
            "checkinStatusCounts", checkinCounts,
            "checkedAt", Instant.now().toString()
        );
    }

    private boolean isAlertEligibleOffline(DeviceStatus status) {
        if (!"OFFLINE".equals(status.state())) return false;
        if (registry.descriptor(status.deviceId()).map(d -> !d.enabled()).orElse(false)) return false;
        return checkinStatuses.get(status.deviceId()) != CheckinStatus.NOT_CONFIGURED;
    }

    /** Per-device checkin status, keyed by deviceId. Devices not yet checked this cycle are omitted. */
    public Map<String, CheckinStatus> getCheckinStatuses() {
        return Map.copyOf(checkinStatuses);
    }

    public Optional<Instant> getStaleSince(String deviceId) {
        return Optional.ofNullable(staleSince.get(deviceId));
    }
}
