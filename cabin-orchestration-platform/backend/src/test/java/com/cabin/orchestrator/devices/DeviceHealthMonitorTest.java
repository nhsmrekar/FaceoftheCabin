package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers the 2026-08-08 checkin-status tiering: "offline" was misleading
 * users because it fired the instant one poll interval was missed, with no
 * distinction between "hasn't reported yet" and "actually unreachable."
 * See DeviceHealthMonitor's class comment for the full design.
 */
class DeviceHealthMonitorTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(15); // STALE_HA
    private static final Duration MISSED = THRESHOLD.multipliedBy(3);

    // ── Pure classification ────────────────────────────────────────────────

    @Test
    void classifyReturnsNotConfiguredWhenDisabledRegardlessOfTiming() {
        assertEquals(CheckinStatus.NOT_CONFIGURED,
            DeviceHealthMonitor.classify(Duration.ofDays(1), THRESHOLD, MISSED, false));
    }

    @Test
    void classifyReturnsOnScheduleWithinThreshold() {
        assertEquals(CheckinStatus.ON_SCHEDULE,
            DeviceHealthMonitor.classify(Duration.ofMinutes(5), THRESHOLD, MISSED, true));
    }

    @Test
    void classifyReturnsLatePastThresholdButWithinMissedMultiple() {
        assertEquals(CheckinStatus.LATE,
            DeviceHealthMonitor.classify(Duration.ofMinutes(20), THRESHOLD, MISSED, true));
    }

    @Test
    void classifyReturnsMissedPastTheGraceMultiple() {
        assertEquals(CheckinStatus.MISSED,
            DeviceHealthMonitor.classify(Duration.ofMinutes(46), THRESHOLD, MISSED, true));
    }

    // ── Full cycle behavior ────────────────────────────────────────────────

    /** Controllable stand-in for HomeAssistantAdapter — no real HTTP call. */
    private static class FakeHaAdapter implements ProtocolAdapter {
        boolean respond = false;
        String respondState = "ONLINE";

        @Override public String adapterType() { return "ha_rest"; }

        @Override public Optional<DeviceStatus> fetchState(DeviceDescriptor d) {
            if (!respond) return Optional.empty();
            return Optional.of(new DeviceStatus(
                d.deviceId(), d.type(), d.name(), respondState, Instant.now(), Map.of(), d.location()));
        }

        @Override public boolean sendCommand(DeviceDescriptor d, String c, Object p) { return true; }
    }

    private DeviceHealthMonitor monitorWith(DeviceRegistry registry) {
        Zigbee2MqttAdapter z2m = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        return new DeviceHealthMonitor(registry, z2m);
    }

    private DeviceDescriptor haDescriptor(String id, boolean enabled) {
        return new DeviceDescriptor(id, "Test HA Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "lock.test", enabled, "cabin");
    }

    @Test
    void disabledDeviceIsNotConfiguredAndNeverFlipsToOffline() {
        FakeHaAdapter ha = new FakeHaAdapter();
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of(ha));
        registry.registerDescriptor(haDescriptor("dev-1", false));
        registry.update(new DeviceStatus("dev-1", DeviceType.LOCK, "Test HA Lock", "UNKNOWN",
            Instant.now().minus(Duration.ofDays(2)), Map.of(), "cabin"));

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.NOT_CONFIGURED, monitor.getCheckinStatuses().get("dev-1"));
        assertEquals("UNKNOWN", registry.get("dev-1").state());
    }

    @Test
    void lateDeviceDoesNotFlipStateToOfflineYet() {
        FakeHaAdapter ha = new FakeHaAdapter(); // active fetch fails (respond=false)
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of(ha));
        registry.registerDescriptor(haDescriptor("dev-2", true));
        registry.update(new DeviceStatus("dev-2", DeviceType.LOCK, "Test HA Lock", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(20)), Map.of(), "cabin")); // past 15min threshold, within 45min missed

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.LATE, monitor.getCheckinStatuses().get("dev-2"));
        assertEquals("ONLINE", registry.get("dev-2").state(),
            "a device that's merely late shouldn't be relabeled OFFLINE — that's the exact bug this fixes");
    }

    @Test
    void missedDeviceFlipsToOfflineAfterActivePingAlsoFails() {
        FakeHaAdapter ha = new FakeHaAdapter(); // active fetch fails
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of(ha));
        registry.registerDescriptor(haDescriptor("dev-3", true));
        registry.update(new DeviceStatus("dev-3", DeviceType.LOCK, "Test HA Lock", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(46)), Map.of(), "cabin")); // past 45min missed threshold

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.MISSED, monitor.getCheckinStatuses().get("dev-3"));
        assertEquals("OFFLINE", registry.get("dev-3").state());
    }

    @Test
    void activePingSuccessRecoversTheDeviceEvenThoughItsPastThreshold() {
        FakeHaAdapter ha = new FakeHaAdapter();
        ha.respond = true;
        ha.respondState = "ONLINE";
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of(ha));
        registry.registerDescriptor(haDescriptor("dev-4", true));
        registry.update(new DeviceStatus("dev-4", DeviceType.LOCK, "Test HA Lock", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(50)), Map.of(), "cabin")); // well past both thresholds

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.ON_SCHEDULE, monitor.getCheckinStatuses().get("dev-4"),
            "an active HA poll that actually succeeds means the device is fine, not MISSED");
        assertEquals("ONLINE", registry.get("dev-4").state());
    }

    @Test
    void retainedOnlineAvailabilityRecoversStaleZigbeeDevice() {
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of());
        registry.registerDescriptor(new DeviceDescriptor("z2m-motion", "Motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/motion", true, "cabin"));
        registry.update(new DeviceStatus("z2m-motion", DeviceType.MOTION_SENSOR, "Motion", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(11)), Map.of(), "cabin")); // past 10min Zigbee threshold

        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        when(z2m.probeRetainedAvailability(eq("zigbee2mqtt/motion"), any(Duration.class)))
            .thenReturn(Optional.of(true));
        DeviceHealthMonitor monitor = new DeviceHealthMonitor(registry, z2m);
        monitor.checkHealth();

        assertEquals(CheckinStatus.ON_SCHEDULE, monitor.getCheckinStatuses().get("z2m-motion"));
        assertEquals("ONLINE", registry.get("z2m-motion").state());
    }

    @Test
    void noRetainedReplyDoesNotHideMissedZigbeeDevice() {
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of());
        registry.registerDescriptor(new DeviceDescriptor("z2m-motion", "Motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/motion", true, "cabin"));
        registry.update(new DeviceStatus("z2m-motion", DeviceType.MOTION_SENSOR, "Motion", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(31)), Map.of(), "cabin"));
        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        when(z2m.probeRetainedAvailability(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        DeviceHealthMonitor monitor = new DeviceHealthMonitor(registry, z2m);
        monitor.checkHealth();

        assertEquals(CheckinStatus.MISSED, monitor.getCheckinStatuses().get("z2m-motion"));
        assertEquals("OFFLINE", registry.get("z2m-motion").state());
    }
}
