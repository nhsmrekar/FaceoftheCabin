package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogEntry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogService;
import com.cabin.orchestrator.devices.catalog.DeviceIdentityAssurance;
import com.cabin.orchestrator.devices.catalog.DeviceAdmissionStatus;
import com.cabin.orchestrator.devices.catalog.DeviceConfigurationStatus;
import com.cabin.orchestrator.devices.catalog.DeviceEnablementStatus;
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
import static com.cabin.orchestrator.devices.catalog.DeviceCatalogTestSupport.authorize;

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
    void classifyReturnsOnScheduleWithinThreshold() {
        assertEquals(CheckinStatus.ON_SCHEDULE,
            DeviceHealthMonitor.classify(Duration.ofMinutes(5), THRESHOLD, MISSED));
    }

    @Test
    void classifyReturnsLatePastThresholdButWithinMissedMultiple() {
        assertEquals(CheckinStatus.LATE,
            DeviceHealthMonitor.classify(Duration.ofMinutes(20), THRESHOLD, MISSED));
    }

    @Test
    void classifyReturnsMissedPastTheGraceMultiple() {
        assertEquals(CheckinStatus.MISSED,
            DeviceHealthMonitor.classify(Duration.ofMinutes(46), THRESHOLD, MISSED));
    }

    // ── Full cycle behavior ────────────────────────────────────────────────

    /** Controllable stand-in for HomeAssistantAdapter — no real HTTP call. */
    private static class FakeHaAdapter implements ProtocolAdapter {
        boolean respond = false;
        String respondState = "ONLINE";
        int fetchCalls;
        int commandCalls;

        @Override public String adapterType() { return "ha_rest"; }

        @Override public Optional<DeviceStatus> fetchState(DeviceDescriptor d) {
            fetchCalls++;
            if (!respond) return Optional.empty();
            return Optional.of(new DeviceStatus(
                d.deviceId(), d.type(), d.name(), respondState, Instant.now(), Map.of(), d.location()));
        }

        @Override public boolean sendCommand(DeviceDescriptor d, String c, Object p) {
            commandCalls++;
            return true;
        }
    }

    private DeviceCatalogService catalog;

    private DeviceRegistry registryWith(ProtocolAdapter... adapters) {
        catalog = DeviceCatalogService.inMemory();
        return new DeviceRegistry(java.util.List.of(adapters), catalog);
    }

    private void authorizeAndRegister(DeviceRegistry registry, String sourceType,
                                      String sourceIdentity, DeviceDescriptor descriptor) {
        DeviceCatalogEntry entry = authorize(catalog, sourceType, sourceIdentity, descriptor);
        assertTrue(registry.registerDescriptor(entry.descriptor()));
    }

    private DeviceHealthMonitor monitorWith(DeviceRegistry registry) {
        Zigbee2MqttAdapter z2m = new Zigbee2MqttAdapter(registry, catalog,
            new EventPublisher(), new SignalQualityRegistry());
        return new DeviceHealthMonitor(registry, z2m);
    }

    private DeviceDescriptor haDescriptor(String id) {
        return new DeviceDescriptor(id, "Test HA Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "lock.test", true, "cabin");
    }

    @Test
    void availableDisabledDeviceHasNoRuntimeOrCheckinState() {
        FakeHaAdapter ha = new FakeHaAdapter();
        DeviceRegistry registry = registryWith(ha);
        DeviceDescriptor disabled = new DeviceDescriptor("dev-1", "Test HA Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "lock.test", false, "cabin");
        catalog.ensureAvailableEntry(new DeviceCatalogEntry(disabled.deviceId(), "test_dev_1",
            disabled.name(), disabled.type(), disabled.capabilities(), disabled.protocolAdapter(),
            disabled.connectionString(), disabled.location(), DeviceIdentityAssurance.PROBABLE,
            DeviceAdmissionStatus.AVAILABLE, DeviceConfigurationStatus.READY_TO_CONFIGURE,
            DeviceEnablementStatus.DISABLED, Instant.now()));
        assertFalse(registry.registerDescriptor(disabled));

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertNull(registry.get("dev-1"));
        assertFalse(monitor.getCheckinStatuses().containsKey("dev-1"));
        assertFalse(registry.sendCommand("dev-1", "unlock", null));
        assertTrue(registry.activeFetch("dev-1").isEmpty());
        assertEquals(0, ha.commandCalls, "an unavailable device cannot reach its network adapter");
        assertEquals(0, ha.fetchCalls, "an unavailable device cannot trigger an active probe");
    }

    @Test
    void lateDeviceDoesNotFlipStateToOfflineYet() {
        FakeHaAdapter ha = new FakeHaAdapter(); // active fetch fails (respond=false)
        DeviceRegistry registry = registryWith(ha);
        authorizeAndRegister(registry, "TEST_HA", "dev-2", haDescriptor("dev-2"));
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
        DeviceRegistry registry = registryWith(ha);
        authorizeAndRegister(registry, "TEST_HA", "dev-3", haDescriptor("dev-3"));
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
        DeviceRegistry registry = registryWith(ha);
        authorizeAndRegister(registry, "TEST_HA", "dev-4", haDescriptor("dev-4"));
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
        DeviceRegistry registry = registryWith();
        DeviceDescriptor descriptor = new DeviceDescriptor("z2m-motion", "Motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/motion", true, "cabin");
        authorizeAndRegister(registry, "ZIGBEE2MQTT", "0x00124b0000000001", descriptor);
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

    private static class FakeRtspAdapter implements ProtocolAdapter {
        boolean respond;

        @Override public String adapterType() { return "rtsp"; }

        @Override public Optional<DeviceStatus> fetchState(DeviceDescriptor d) {
            if (!respond) return Optional.empty();
            return Optional.of(new DeviceStatus(
                d.deviceId(), d.type(), d.name(), "ONLINE", Instant.now(),
                Map.of("rtspSocketReachable", true), d.location()));
        }

        @Override public boolean sendCommand(DeviceDescriptor d, String c, Object p) { return false; }
    }

    @Test
    void noRetainedReplyDoesNotHideMissedZigbeeDevice() {
        DeviceRegistry registry = registryWith();
        DeviceDescriptor descriptor = new DeviceDescriptor("z2m-motion", "Motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/motion", true, "cabin");
        authorizeAndRegister(registry, "ZIGBEE2MQTT", "0x00124b0000000001", descriptor);
        registry.update(new DeviceStatus("z2m-motion", DeviceType.MOTION_SENSOR, "Motion", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(31)), Map.of(), "cabin"));
        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        when(z2m.probeRetainedAvailability(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        DeviceHealthMonitor monitor = new DeviceHealthMonitor(registry, z2m);
        monitor.checkHealth();

        assertEquals(CheckinStatus.MISSED, monitor.getCheckinStatuses().get("z2m-motion"));
        assertEquals("OFFLINE", registry.get("z2m-motion").state());
    }

    @Test
    void rtspSocketSuccessRecoversStaleCamera() {
        FakeRtspAdapter rtsp = new FakeRtspAdapter();
        rtsp.respond = true;
        DeviceRegistry registry = registryWith(rtsp);
        DeviceDescriptor descriptor = new DeviceDescriptor("camera-test", "Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "rtsp", "rtsp://camera:554/stream", true, "home");
        authorizeAndRegister(registry, "RTSP_ENDPOINT", "camera-serial-1", descriptor);
        registry.update(new DeviceStatus("camera-test", DeviceType.CAMERA, "Camera", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(20)), Map.of("cameraFps", 12.0), "home"));

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.ON_SCHEDULE, monitor.getCheckinStatuses().get("camera-test"));
        assertEquals("ONLINE", registry.get("camera-test").state());
        assertEquals(12.0, registry.get("camera-test").attributes().get("cameraFps"),
            "the reachability fact complements rather than replaces stream-health attributes");
        assertEquals(Boolean.TRUE, registry.get("camera-test").attributes().get("rtspSocketReachable"));
    }

    @Test
    void rtspSocketFailureDoesNotHideMissedCamera() {
        FakeRtspAdapter rtsp = new FakeRtspAdapter();
        DeviceRegistry registry = registryWith(rtsp);
        DeviceDescriptor descriptor = new DeviceDescriptor("camera-test", "Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "rtsp", "rtsp://camera:554/stream", true, "home");
        authorizeAndRegister(registry, "RTSP_ENDPOINT", "camera-serial-1", descriptor);
        registry.update(new DeviceStatus("camera-test", DeviceType.CAMERA, "Camera", "ONLINE",
            Instant.now().minus(Duration.ofMinutes(16)), Map.of(), "home"));

        DeviceHealthMonitor monitor = monitorWith(registry);
        monitor.checkHealth();

        assertEquals(CheckinStatus.MISSED, monitor.getCheckinStatuses().get("camera-test"));
        assertEquals("OFFLINE", registry.get("camera-test").state());
    }

    @Test
    void availableDisabledDeviceCannotEnterDiagnosticOrAlertCounts() {
        DeviceRegistry registry = registryWith();
        DeviceHealthMonitor monitor = monitorWith(registry);

        monitor.checkHealth();
        Map<String, Object> health = monitor.getSystemHealth();

        assertEquals(0, health.get("total"));
        assertEquals(0L, health.get("offline"));
        assertEquals(0L, health.get("alertEligibleOffline"));
        assertFalse(monitor.getCheckinStatuses().containsKey("camera-disabled"));
    }

    @Test
    void enabledOfflineDeviceIsAlertEligible() {
        DeviceRegistry registry = registryWith();
        DeviceDescriptor descriptor = new DeviceDescriptor("device-enabled", "Expected Device", DeviceType.ROUTER,
            Set.of(DeviceCapability.TELEMETRY), "unknown", "test://device-enabled", true, "cabin");
        authorizeAndRegister(registry, "TEST_DEVICE", "device-enabled", descriptor);
        registry.update(new DeviceStatus("device-enabled", DeviceType.ROUTER, "Expected Device", "OFFLINE",
            Instant.now().minus(Duration.ofHours(2)), Map.of(), "cabin"));
        DeviceHealthMonitor monitor = monitorWith(registry);

        monitor.checkHealth();

        assertEquals(1L, monitor.getSystemHealth().get("alertEligibleOffline"));
    }
}
