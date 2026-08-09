package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLivenessCheckResult;
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
        List<DeviceStatus> operationalDevices = registry.all();
        Set<String> operationalIds = operationalDevices.stream()
            .map(DeviceStatus::deviceId).collect(java.util.stream.Collectors.toSet());
        checkinStatuses.keySet().retainAll(operationalIds);
        staleSince.keySet().retainAll(operationalIds);
        lastKnownState.keySet().retainAll(operationalIds);
        reconnectAttempts.keySet().retainAll(operationalIds);
        for (DeviceStatus status : operationalDevices) {
            String id = status.deviceId();
            Optional<DeviceDescriptor> descriptor = registry.descriptor(id);

            Duration staleThreshold = staleThresholdFor(id, status);
            Duration sinceLastSeen = Duration.between(status.lastSeen(), now);

            if (sinceLastSeen.compareTo(staleThreshold) <= 0) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "on schedule again");
                continue;
            }

            ActiveCheckAttempt activeCheck = performActiveCheck(id, descriptor, now);
            if (activeCheck.outcome() == DeviceLivenessCheckResult.Outcome.REACHABLE) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "active check confirmed it's actually reachable");
                continue;
            }

            Duration missedThreshold = staleThreshold.multipliedBy(MISSED_MULTIPLIER);
            CheckinStatus classified = classify(sinceLastSeen, staleThreshold, missedThreshold);
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
    static CheckinStatus classify(Duration sinceLastSeen, Duration staleThreshold, Duration missedThreshold) {
        if (sinceLastSeen.compareTo(staleThreshold) <= 0) return CheckinStatus.ON_SCHEDULE;
        if (sinceLastSeen.compareTo(missedThreshold) <= 0) return CheckinStatus.LATE;
        return CheckinStatus.MISSED;
    }

    /**
     * Actively verify supported adapters. HA is polled directly. Zigbee asks
     * MQTT for a retained authoritative availability replay and only accepts
     * an explicit ONLINE result; OFFLINE and NO_REPLY fail closed.
     */
    private ActiveCheckAttempt performActiveCheck(String id, Optional<DeviceDescriptor> descriptor, Instant now) {
        String adapterType = descriptor.map(DeviceDescriptor::protocolAdapter).orElse("unknown");
        if ("mqtt".equals(adapterType) && id.startsWith("z2m-")) {
            Optional<Boolean> availability = z2mAdapter.probeRetainedAvailability(
                descriptor.map(DeviceDescriptor::connectionString).orElse(""),
                MQTT_AVAILABILITY_TIMEOUT);
            if (availability.isEmpty()) {
                return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.NO_REPLY,
                    "No retained authoritative availability reply was received.");
            }
            if (!availability.get()) {
                return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.REPORTED_OFFLINE,
                    "The Zigbee availability source still reports this device offline.");
            }
            refreshAfterReachableProbe(id, now);
            return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.REACHABLE,
                "The Zigbee availability source reports this device online.");
        }
        if (!"ha_rest".equals(adapterType) && !"rtsp".equals(adapterType)) {
            return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.UNSUPPORTED,
                "This device can only recover when it sends its next scheduled report.");
        }

        Optional<DeviceStatus> live = registry.activeFetch(id);
        if (live.isEmpty()) {
            return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.NO_REPLY,
                "No response was received from the active device check.");
        }

        DeviceStatus fresh = live.get();
        Map<String, Object> freshAttributes = new LinkedHashMap<>(fresh.attributes());
        if ("rtsp".equals(adapterType)) {
            DeviceStatus existing = registry.get(id);
            Map<String, Object> merged = new LinkedHashMap<>();
            if (existing != null) merged.putAll(existing.attributes());
            merged.putAll(fresh.attributes());
            freshAttributes = merged;
        }
        freshAttributes.remove("staleSince");
        freshAttributes.remove("lastKnownState");
        registry.update(new DeviceStatus(
            id, fresh.type(), fresh.name(), fresh.state(), now, freshAttributes, fresh.location()));
        return new ActiveCheckAttempt(DeviceLivenessCheckResult.Outcome.REACHABLE,
            "rtsp".equals(adapterType)
                ? "The configured camera endpoint accepted an RTSP-port connection."
                : "Home Assistant returned a current state for this device.");
    }

    private void refreshAfterReachableProbe(String id, Instant now) {
        DeviceStatus existing = registry.get(id);
        if (existing == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>(existing.attributes());
        attributes.remove("staleSince");
        attributes.remove("lastKnownState");
        String recoveredState = "OFFLINE".equals(existing.state())
            ? lastKnownState.getOrDefault(id, "ONLINE")
            : existing.state();
        registry.update(new DeviceStatus(
            id, existing.type(), existing.name(), recoveredState,
            now, attributes, existing.location()));
    }

    /**
     * Run the strongest adapter-specific liveness check available on demand.
     * Only a device already classified LATE or MISSED may enter this path;
     * catalog admission and operational authority remain enforced by the
     * registry before any adapter can be reached.
     */
    public DeviceLivenessCheckResult checkNow(String deviceId) {
        DeviceStatus current = registry.get(deviceId);
        if (current == null || registry.descriptor(deviceId).isEmpty()) {
            throw new IllegalArgumentException("Operational device not found");
        }

        CheckinStatus previous = checkinStatuses.get(deviceId);
        if (previous != CheckinStatus.LATE && previous != CheckinStatus.MISSED) {
            throw new IllegalStateException("Check now is available only while a device is LATE or MISSED");
        }

        Instant checkedAt = Instant.now();
        if (Duration.between(current.lastSeen(), checkedAt)
            .compareTo(staleThresholdFor(deviceId, current)) <= 0) {
            checkinStatuses.put(deviceId, CheckinStatus.ON_SCHEDULE);
            recoverIfNeeded(deviceId, "checked in before the requested active check ran");
            return new DeviceLivenessCheckResult(deviceId, "CHECK_NOW",
                DeviceLivenessCheckResult.Outcome.REACHABLE, previous,
                CheckinStatus.ON_SCHEDULE, checkedAt,
                "The device checked in after this card was last refreshed.");
        }

        ActiveCheckAttempt attempt = performActiveCheck(
            deviceId, registry.descriptor(deviceId), checkedAt);
        CheckinStatus currentStatus = previous;
        if (attempt.outcome() == DeviceLivenessCheckResult.Outcome.REACHABLE) {
            currentStatus = CheckinStatus.ON_SCHEDULE;
            checkinStatuses.put(deviceId, currentStatus);
            recoverIfNeeded(deviceId, "user-requested check confirmed reachability");
        }
        log.info("User-requested liveness check for {}: {} ({})",
            deviceId, attempt.outcome(), attempt.message());
        return new DeviceLivenessCheckResult(deviceId, "CHECK_NOW",
            attempt.outcome(), previous, currentStatus, checkedAt, attempt.message());
    }

    private record ActiveCheckAttempt(
        DeviceLivenessCheckResult.Outcome outcome,
        String message
    ) {}

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
        // DeviceRegistry contains operationally authorized devices only.
        return "OFFLINE".equals(status.state());
    }

    /** Per-device checkin status, keyed by deviceId. Devices not yet checked this cycle are omitted. */
    public Map<String, CheckinStatus> getCheckinStatuses() {
        Map<String, CheckinStatus> authorized = new LinkedHashMap<>();
        checkinStatuses.forEach((deviceId, status) -> {
            if (registry.operationallyAuthorized(deviceId)) authorized.put(deviceId, status);
        });
        return Map.copyOf(authorized);
    }

    public Optional<Instant> getStaleSince(String deviceId) {
        return registry.operationallyAuthorized(deviceId)
            ? Optional.ofNullable(staleSince.get(deviceId))
            : Optional.empty();
    }
}
