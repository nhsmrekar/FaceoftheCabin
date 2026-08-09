package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogEntry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogService;
import com.cabin.orchestrator.devices.catalog.DeviceObservationCandidate;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.cabin.orchestrator.devices.catalog.DeviceCatalogTestSupport.authorize;

/**
 * Targeted coverage for the 2026-08-08 SignalQualityRegistry wiring --
 * see that class's own comment for the full prototype reasoning. Not a
 * full test suite for Zigbee2MqttAdapter (that class had zero test
 * coverage before this and gaining it wholesale is a separate task);
 * this only covers the one new integration point this session added.
 */
class Zigbee2MqttAdapterTest {

    private DeviceRegistry registry;
    private DeviceCatalogService catalog;
    private SignalQualityRegistry signalQualityRegistry;
    private Zigbee2MqttAdapter adapter;
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        catalog = DeviceCatalogService.inMemory();
        registry = new DeviceRegistry(List.of(), catalog);
        signalQualityRegistry = new SignalQualityRegistry();
        eventPublisher = mock(EventPublisher.class);
        adapter = new Zigbee2MqttAdapter(registry, catalog, eventPublisher, signalQualityRegistry);
    }

    private void deliver(String topic, String payload) throws Exception {
        adapter.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    /** Matches real Z2M startup order: bridge/devices always arrives before any device state message. */
    private void observeDevice(String friendlyName, String ieeeAddress) throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"%s","ieee_address":"%s","type":"EndDevice",
              "interview_completed":true,"disabled":false,"definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF",
              "exposes":[{"type":"binary","property":"occupancy","access":1}]}}]
            """.formatted(friendlyName, ieeeAddress));
    }

    private void registerDevice(String friendlyName) throws Exception {
        String ieeeAddress = "0x00124b0012345678";
        observeDevice(friendlyName, ieeeAddress);
        DeviceDescriptor descriptor = new DeviceDescriptor("z2m-" + friendlyName,
            "Entry Motion Sensor", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.PRESENCE),
            "mqtt", "zigbee2mqtt/" + friendlyName, true, "cabin");
        DeviceCatalogEntry entry = authorize(catalog, "ZIGBEE2MQTT", ieeeAddress, descriptor);
        observeDevice(friendlyName, ieeeAddress); // roster refresh activates the admitted entry
        assertTrue(registry.descriptor(entry.deviceId()).isPresent());
    }

    @Test
    void deviceStateWithLinkqualityIsRecordedInSignalQualityRegistry() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 160, \"battery\": 100}");

        var a = signalQualityRegistry.assess("z2m-motion_entry");
        assertTrue(a.isPresent(), "a device message with linkquality must be recorded");
        assertEquals(160, a.get().current());
    }

    @Test
    void repeatedMessagesAccumulateHistoryNotJustTheLatestValue() throws Exception {
        registerDevice("motion_entry");

        for (int i = 0; i < 6; i++) {
            deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 200}");
        }

        var a = signalQualityRegistry.assess("z2m-motion_entry").orElseThrow();
        assertEquals(6, a.sampleCount(), "each message must add to history, not overwrite it");
    }

    @Test
    void aMessageWithoutLinkqualityIsNotRecorded() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"battery\": 100}");

        assertTrue(signalQualityRegistry.assess("z2m-motion_entry").isEmpty(),
            "a message with no linkquality field must not create a bogus reading");
    }

    @Test
    void anUnregisteredDevicesStateMessageIsIgnoredEntirely() throws Exception {
        // No registerDevice() call -- messageArrived's own routing requires
        // the friendly name to already be known (bridge/devices), matching
        // real Z2M behavior where bridge/devices always arrives first.
        deliver("zigbee2mqtt/never_registered", "{\"linkquality\": 160}");

        assertTrue(signalQualityRegistry.assess("z2m-never_registered").isEmpty());
    }

    @Test
    void rosterObservationCreatesAvailableCandidateButNoRuntimeStateOrEvent() throws Exception {
        String ieee = "0x00124b00aaa00001";
        observeDevice("neighbor_motion", ieee);

        DeviceObservationCandidate candidate = catalog.candidateForObservation(
            "ZIGBEE2MQTT", ieee).orElseThrow();
        assertEquals("AVAILABLE", candidate.disposition().name());
        assertNull(registry.get("z2m-neighbor_motion"));

        deliver("zigbee2mqtt/neighbor_motion", "{\"linkquality\":160,\"occupancy\":true}");

        assertNull(registry.get("z2m-neighbor_motion"));
        assertTrue(signalQualityRegistry.assess("z2m-neighbor_motion").isEmpty());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectedIeeeResurfacesAsRejectedAndStillCannotRegister() throws Exception {
        String ieee = "0x00124b00aaa00002";
        observeDevice("intrusive_motion", ieee);
        DeviceObservationCandidate first = catalog.candidateForObservation(
            "ZIGBEE2MQTT", ieee).orElseThrow();
        catalog.rejectCandidate(first.candidateId(), "owner@example.com", "not part of this property");

        observeDevice("intrusive_motion", ieee);
        DeviceObservationCandidate resurfaced = catalog.candidateForObservation(
            "ZIGBEE2MQTT", ieee).orElseThrow();

        assertEquals(first.candidateId(), resurfaced.candidateId());
        assertEquals("REJECTED", resurfaced.disposition().name());
        assertEquals(2, resurfaced.seenCount());
        assertNull(registry.get("z2m-intrusive_motion"));
    }

    @Test
    void availabilityParserAcceptsOnlyCanonicalOnlineAndOfflineValues() {
        assertEquals(Boolean.TRUE, Zigbee2MqttAdapter.parseAvailability("{\"state\":\"online\"}").orElseThrow());
        assertEquals(Boolean.FALSE, Zigbee2MqttAdapter.parseAvailability("offline").orElseThrow());
        assertTrue(Zigbee2MqttAdapter.parseAvailability("unknown").isEmpty());
        assertTrue(Zigbee2MqttAdapter.parseAvailability("not-json{").isEmpty());
    }

    private MqttClient installConnectedClient() throws Exception {
        MqttClient client = mock(MqttClient.class);
        when(client.isConnected()).thenReturn(true);
        Field field = Zigbee2MqttAdapter.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(adapter, client);
        return client;
    }

    @Test
    void activeProbeAcceptsRetainedOnlineReplay() throws Exception {
        MqttClient client = installConnectedClient();
        doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            MqttMessage message = new MqttMessage("online".getBytes());
            message.setRetained(true);
            adapter.messageArrived(topic, message);
            return null;
        }).when(client).subscribe(anyString(), anyInt());

        assertEquals(Boolean.TRUE, adapter.probeRetainedAvailability(
            "zigbee2mqtt/motion_entry", Duration.ofMillis(100)).orElseThrow());
    }

    @Test
    void activeProbeRejectsNonRetainedTrafficAsNoReply() throws Exception {
        MqttClient client = installConnectedClient();
        doAnswer(invocation -> {
            adapter.messageArrived(invocation.getArgument(0), new MqttMessage("online".getBytes()));
            return null;
        }).when(client).subscribe(anyString(), anyInt());

        assertTrue(adapter.probeRetainedAvailability(
            "zigbee2mqtt/motion_entry", Duration.ofMillis(10)).isEmpty());
    }
}
