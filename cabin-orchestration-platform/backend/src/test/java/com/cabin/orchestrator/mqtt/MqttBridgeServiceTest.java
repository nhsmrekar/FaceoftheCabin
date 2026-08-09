package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogEntry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogService;
import com.cabin.orchestrator.devices.catalog.DeviceObservationCandidate;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static com.cabin.orchestrator.devices.catalog.DeviceCatalogTestSupport.authorize;

/**
 * Regression coverage for the 2026-08-07 finding: handleCameraTopic()
 * never called DeviceRegistry.update() for any camera MQTT message, so a
 * camera's lastSeen was set once (however it first got registered) and
 * never refreshed -- DeviceHealthMonitor's 5-minute camera stale
 * threshold then always fired exactly 5 minutes after that one
 * registration and the camera could never recover on its own.
 *
 * Also covers the 2026-08-08 finding: the active PresenceProfile was
 * only ever set manually from the toolbar, with no real signal behind
 * it despite AutomationRuleService using it for real security-severity
 * decisions -- see handlePresenceTopic's tests below.
 *
 * No Testcontainers here -- DeviceRegistry/PresenceSignalRegistry are
 * plain in-memory maps, EventPublisher safely no-ops when constructed
 * without @PostConstruct init() (producer stays null, publish() logs and
 * returns), and PresenceService's JdbcTemplate is mocked (Mockito, via
 * spring-boot-starter-test) rather than pointed at real Postgres --
 * these tests exercise the real derivation logic in PresenceService/
 * PresenceSignalRegistry, just without needing a live DB connection for
 * what's fundamentally in-memory "who's here right now" state (see
 * PresenceSignalRegistry's own comment on why it's not Postgres-backed).
 * This exercises MqttBridgeService.messageArrived() -- the real public
 * entry point Paho calls -- directly against real Kafka/DB.
 */
class MqttBridgeServiceTest {

    private DeviceRegistry registry;
    private DeviceCatalogService catalog;
    private PresenceService presenceService;
    private PresenceSignalRegistry presenceSignalRegistry;
    private SecurityStateRegistry securityStateRegistry;
    private MqttBridgeService bridge;
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        catalog = DeviceCatalogService.inMemory();
        registry = new DeviceRegistry(List.of(), catalog);
        presenceSignalRegistry = new PresenceSignalRegistry();
        presenceService = new PresenceService(mock(JdbcTemplate.class), presenceSignalRegistry);
        securityStateRegistry = new SecurityStateRegistry();
        eventPublisher = mock(EventPublisher.class);
        bridge = new MqttBridgeService(registry, catalog, eventPublisher,
            presenceService, presenceSignalRegistry, securityStateRegistry);
    }

    private void deliver(String topic, String payload) throws Exception {
        bridge.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    private DeviceCatalogEntry authorizeCamera(String cameraId) {
        String sourceIdentity = "cabin/camera/" + cameraId;
        return authorize(catalog, "FRIGATE_CAMERA", sourceIdentity,
            new DeviceDescriptor(cameraId, "Camera " + cameraId, DeviceType.CAMERA,
                Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
                "mqtt", sourceIdentity, true, "cabin"));
    }

    private void authorizePresence(String location, String personId) {
        String sourceIdentity = location + "/presence/" + personId;
        authorize(catalog, "MQTT_PRESENCE_SOURCE", sourceIdentity,
            new DeviceDescriptor("presence-" + location + "-" + personId,
                "Presence source", DeviceType.HOME_ASSISTANT_ENTITY,
                Set.of(DeviceCapability.PRESENCE), "mqtt", sourceIdentity, true, location));
    }

    private void authorizeSecurity(String location) {
        String sourceIdentity = location + "/security/armed_away";
        authorize(catalog, "MQTT_SECURITY_SOURCE", sourceIdentity,
            new DeviceDescriptor("security-" + location + "-armed-away",
                "Security state source", DeviceType.HOME_ASSISTANT_ENTITY,
                Set.of(DeviceCapability.TELEMETRY), "mqtt", sourceIdentity, true, location));
    }

    @Test
    void unknownCameraIsRetainedAsAvailableButCannotChangeStateOrPublish() throws Exception {
        assertNull(registry.get("driveway"));

        deliver("cabin/camera/driveway/motion", "ON");

        assertNull(registry.get("driveway"));
        DeviceObservationCandidate candidate = catalog.candidateForObservation(
            "FRIGATE_CAMERA", "cabin/camera/driveway").orElseThrow();
        assertEquals("AVAILABLE", candidate.disposition().name());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void perLabelCountTopicAlsoTouchesTheCamera() throws Exception {
        authorizeCamera("driveway");
        deliver("cabin/camera/driveway/car", "1");

        DeviceStatus status = registry.get("driveway");
        assertNotNull(status, "an authorized camera should refresh from a per-label count topic too");
        assertEquals("ONLINE", status.state());
    }

    @Test
    void repeatedMotionRefreshesLastSeenInsteadOfOnlyRegisteringOnce() throws Exception {
        authorizeCamera("driveway");
        deliver("cabin/camera/driveway/motion", "OFF");
        Instant firstSeen = registry.get("driveway").lastSeen();

        Thread.sleep(5);
        deliver("cabin/camera/driveway/motion", "ON");
        Instant secondSeen = registry.get("driveway").lastSeen();

        assertTrue(secondSeen.isAfter(firstSeen),
            "a later camera message must push lastSeen forward, or DeviceHealthMonitor's " +
            "5-minute stale threshold will fire and never recover, exactly like the 2026-08-07 incident");
    }

    @Test
    void touchingACameraPreservesItsExistingAttributes() throws Exception {
        authorizeCamera("driveway");
        deliver("cabin/camera/driveway/motion", "ON");
        // simulate an attribute a future enhancement might attach (e.g. resolution)
        DeviceStatus withAttrs = registry.get("driveway");
        registry.update(new DeviceStatus(withAttrs.deviceId(), withAttrs.type(), withAttrs.name(),
            withAttrs.state(), withAttrs.lastSeen(), java.util.Map.of("resolution", "1080p"), withAttrs.location()));

        deliver("cabin/camera/driveway/car", "2");

        assertEquals("1080p", registry.get("driveway").attributes().get("resolution"),
            "touchCamera() must not clobber attributes set by other paths");
    }

    @Test
    void availableTopicIsIgnoredSinceItDoesNotNameACamera() throws Exception {
        // cabin/camera/available is Frigate's single bridge-wide topic --
        // parts.length == 2, so handleCameraTopic's per-camera branches
        // must not misinterpret it as a camera named "available".
        deliver("cabin/camera/available", "online");

        assertNull(registry.get("available"),
            "the bridge-wide availability topic must never be registered as a camera device");
    }

    @Test
    void authorizedFrigateDetectionUsesCanonicalSeverityClassifier() throws Exception {
        authorizeCamera("driveway");

        deliver("cabin/camera/events", """
            {"type":"new","after":{"id":"evt-critical","camera":"driveway",
            "label":"person","score":0.91,"alarm":true}}
            """);

        ArgumentCaptor<CabinEvent> event = ArgumentCaptor.forClass(CabinEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertEquals("DETECTION_NEW", event.getValue().eventType());
        assertEquals("CRITICAL", event.getValue().severity(),
            "Frigate detections must use event_severity, not a hardcoded INFO literal");
    }

    @Test
    void unadmittedFrigateDetectionCannotPublishEvenWhenPayloadClassifiesCritical() throws Exception {
        deliver("cabin/camera/events", """
            {"type":"new","after":{"camera":"neighbor-camera",
            "label":"person","alarm":true}}
            """);

        assertNull(registry.get("neighbor-camera"));
        assertEquals("AVAILABLE", catalog.candidateForObservation(
            "FRIGATE_CAMERA", "cabin/camera/neighbor-camera").orElseThrow().disposition().name());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unknownGenericDeviceTopicCannotAllocateRuntimeStateOrPublish() throws Exception {
        deliver("cabin/device/neighbor-sensor/state", "{\"motion\":true}");

        assertNull(registry.get("neighbor-sensor"));
        assertEquals("AVAILABLE", catalog.candidateForObservation(
            "MQTT_DEVICE_TOPIC", "cabin/device/neighbor-sensor").orElseThrow().disposition().name());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unadmittedPresenceAndSecurityTopicsCannotChangeApplicationState() throws Exception {
        deliver("cabin/presence/intruder", "home");
        deliver("cabin/security/armed_away", "ON");

        assertTrue(presenceSignalRegistry.all().isEmpty());
        assertFalse(presenceService.isAutoDerived());
        assertTrue(securityStateRegistry.get("cabin").isEmpty());
    }

    @Test
    void singlePersonAtCabinDerivesAtCabin() throws Exception {
        authorizePresence("cabin", "nate");
        deliver("cabin/presence/nate", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get());
        assertTrue(presenceService.isAutoDerived());
    }

    @Test
    void singlePersonAtHomeDerivesAtHome() throws Exception {
        // Not cabin-only by design -- see PresenceSignalRegistry's comment.
        // home-hub isn't deployed yet, but the topic/derivation logic
        // itself makes no cabin-specific assumption.
        authorizePresence("home", "emma");
        deliver("home/presence/emma", "home");

        assertEquals(PresenceProfile.AT_HOME, presenceService.get());
    }

    @Test
    void onePersonAtEachLocationSimultaneouslyDerivesBothOccupied() throws Exception {
        authorizePresence("cabin", "nate");
        authorizePresence("home", "emma");
        deliver("cabin/presence/nate", "home");
        deliver("home/presence/emma", "home");

        assertEquals(PresenceProfile.BOTH_OCCUPIED, presenceService.get(),
            "one person at cabin AND a different person at home, simultaneously, must read as Both Occupied");
    }

    @Test
    void everyoneLeavingDerivesAway() throws Exception {
        authorizePresence("cabin", "nate");
        deliver("cabin/presence/nate", "home");
        deliver("cabin/presence/nate", "not_home");

        assertEquals(PresenceProfile.AWAY, presenceService.get());
    }

    @Test
    void secondPersonArrivingAtSameLocationStaysAtThatLocation() throws Exception {
        // Two people, one location -- must not require exactly one person
        // per location, or double-count into some other state.
        authorizePresence("cabin", "nate");
        authorizePresence("cabin", "emma");
        deliver("cabin/presence/nate", "home");
        deliver("cabin/presence/emma", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get());

        deliver("cabin/presence/nate", "not_home");
        assertEquals(PresenceProfile.AT_CABIN, presenceService.get(),
            "emma is still at cabin -- one person leaving must not clear the whole location");
    }

    @Test
    void manualOverrideIsSupersededByTheNextRealSignal() throws Exception {
        presenceService.set(PresenceProfile.AWAY); // manual override, e.g. no signal configured yet
        assertFalse(presenceService.isAutoDerived());

        authorizePresence("cabin", "nate");
        deliver("cabin/presence/nate", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get(),
            "a real signal must win over a stale manual override, not be silently ignored");
        assertTrue(presenceService.isAutoDerived());
    }

    @Test
    void presenceTopicIsNotMisroutedThroughTheJsonDeviceHandler() throws Exception {
        // {location}/presence/{personId} is plain text ("home"/"not_home"),
        // not JSON -- must be handled before the generic JSON-parse
        // fallback, or every presence message would throw and get
        // silently swallowed by messageArrived's catch block.
        authorizePresence("cabin", "nate");
        deliver("cabin/presence/nate", "home");

        assertEquals(1, presenceSignalRegistry.all().size());
        assertNull(registry.get("nate"), "a presence signal must never register a device");
    }

    @Test
    void armedAwayOnRecordsArmedForThatLocation() throws Exception {
        authorizeSecurity("cabin");
        deliver("cabin/security/armed_away", "ON");

        assertTrue(securityStateRegistry.get("cabin").orElseThrow().armed());
    }

    @Test
    void armedAwayOffRecordsDisarmedForThatLocation() throws Exception {
        authorizeSecurity("cabin");
        deliver("cabin/security/armed_away", "OFF");

        assertFalse(securityStateRegistry.get("cabin").orElseThrow().armed());
    }

    @Test
    void armedStateIsLocationAgnosticNotHardcodedToCabin() throws Exception {
        // home-hub isn't deployed yet, but this must still work today for
        // whatever location actually publishes -- see this class's own
        // javadoc on the +/security/armed_away subscription.
        authorizeSecurity("home");
        deliver("home/security/armed_away", "ON");

        assertTrue(securityStateRegistry.get("home").orElseThrow().armed());
        assertTrue(securityStateRegistry.get("cabin").isEmpty(),
            "a signal for one location must not be recorded against a different one");
    }

    @Test
    void armedTopicIsNotMisroutedThroughTheJsonDeviceHandler() throws Exception {
        // Plain text ("ON"/"OFF"), not JSON -- same reasoning as presence.
        authorizeSecurity("cabin");
        deliver("cabin/security/armed_away", "ON");

        assertNull(registry.get("armed_away"), "an armed-state signal must never register a device");
    }
}
