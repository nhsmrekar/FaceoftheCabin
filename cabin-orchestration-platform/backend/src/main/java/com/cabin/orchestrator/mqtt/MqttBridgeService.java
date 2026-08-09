package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogEntry;
import com.cabin.orchestrator.devices.catalog.DeviceCatalogService;
import com.cabin.orchestrator.devices.catalog.DeviceConfigurationStatus;
import com.cabin.orchestrator.devices.catalog.DeviceObservation;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Subscribes to all cabin/device/# and cabin/camera/# MQTT topics.
 * Routes telemetry → DeviceRegistry.update() and → Kafka cabin.events.raw.
 *
 * Topic contract (from docs/device-topic-contract.md):
 *   cabin/device/{deviceId}/state      — full state snapshot
 *   cabin/device/{deviceId}/telemetry  — metric payload (psi, temp, etc.)
 *   cabin/event/{severity}             — direct event injection
 *   cabin/camera/{cameraId}/motion     — Frigate motion events
 *   cabin/system/health                — watchdog health reports
 *   {location}/presence/{personId}     — "home"/"not_home", per-person
 *                                         WiFi presence (see
 *                                         handlePresenceTopic) — one of two
 *                                         subscriptions here that isn't
 *                                         cabin/-prefixed, deliberately:
 *                                         PresenceService's derivation
 *                                         needs signals from every
 *                                         location this instance manages,
 *                                         not just cabin's own.
 *   {location}/security/armed_away     — "ON"/"OFF", real HA-published,
 *                                         retained, self-healing armed
 *                                         state (see handleArmedTopic) —
 *                                         the other location-agnostic
 *                                         subscription, same reasoning.
 */
@Service
public class MqttBridgeService implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttBridgeService.class);
    private static final int MAX_INGRESS_PAYLOAD_BYTES = 65_536;

    @Value("${cabin.mqtt.brokerUrl:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${cabin.mqtt.clientId:cabin-orchestrator}")
    private String clientId;

    private MqttClient client;
    private final DeviceRegistry registry;
    private final DeviceCatalogService catalog;
    private final EventPublisher eventPublisher;
    private final PresenceService presenceService;
    private final PresenceSignalRegistry presenceSignalRegistry;
    private final SecurityStateRegistry securityStateRegistry;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public MqttBridgeService(DeviceRegistry registry, DeviceCatalogService catalog,
                              EventPublisher eventPublisher,
                              PresenceService presenceService, PresenceSignalRegistry presenceSignalRegistry,
                              SecurityStateRegistry securityStateRegistry) {
        this.registry = registry;
        this.catalog = catalog;
        this.eventPublisher = eventPublisher;
        this.presenceService = presenceService;
        this.presenceSignalRegistry = presenceSignalRegistry;
        this.securityStateRegistry = securityStateRegistry;
    }

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(brokerUrl, clientId + "-" + UUID.randomUUID());
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            client.connect(opts);
            client.subscribe("cabin/device/#", 1);
            client.subscribe("cabin/camera/#", 1);
            client.subscribe("cabin/event/#", 1);
            client.subscribe("cabin/system/#", 0);
            // Wildcarded on location (+), not hardcoded to cabin/ -- see
            // this class's javadoc. Only cabin/presence/nate exists live
            // today (home-hub isn't deployed yet), but this subscription
            // and everything downstream of it (PresenceSignalRegistry,
            // PresenceService.recomputeFromSignals) already treat
            // location as data, not an assumption, so a future
            // home/presence/{anyone} publisher needs zero backend changes
            // to be picked up.
            client.subscribe("+/presence/#", 1);
            // Same wildcard reasoning as presence above. Only
            // cabin/security/armed_away is real today (home-hub isn't
            // deployed), but nothing downstream (SecurityStateRegistry,
            // the /api/security endpoint) assumes cabin-only.
            client.subscribe("+/security/armed_away", 1);
            log.info("MQTT bridge connected to {}", brokerUrl);
        } catch (MqttException e) {
            log.error("MQTT connect failed: {}", e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            if (message.getPayload().length > MAX_INGRESS_PAYLOAD_BYTES) {
                log.warn("Dropped oversized MQTT message on {} ({} bytes)", topic, message.getPayload().length);
                return;
            }
            String payload = new String(message.getPayload());
            log.debug("MQTT message arrived: topic={} bytes={}", topic, message.getPayload().length);
            String[] parts = topic.split("/");

            if (parts.length >= 3 && "camera".equals(parts[1])) {
                // Frigate's camera/# topics are NOT uniformly JSON — `available`
                // and `{camera}/motion` are plain text ("online"/"ON"), only
                // `events` is JSON. Parsing happens per-branch below, not here.
                handleCameraTopic(parts, payload);
                return;
            }

            if (parts.length == 3 && "presence".equals(parts[1])) {
                // Plain text ("home"/"not_home"), same reasoning as camera
                // topics above -- must be handled before the generic
                // JSON-parse fallback below, not through it.
                handlePresenceTopic(parts, payload);
                return;
            }

            if (parts.length == 3 && "security".equals(parts[1]) && "armed_away".equals(parts[2])) {
                // Plain text ("ON"/"OFF"), same reasoning as presence above.
                handleArmedTopic(parts[0], payload);
                return;
            }

            Map<String, Object> data = mapper.readValue(payload, Map.class);
            if (parts.length >= 3 && "device".equals(parts[1])) {
                String deviceId = parts[2];
                String msgType = parts.length >= 4 ? parts[3] : "state";
                handleDeviceMessage(deviceId, msgType, data);
            } else if (parts.length >= 3 && "event".equals(parts[1])) {
                handleDirectEvent(parts[2], data);
            }
        } catch (Exception e) {
            log.warn("Failed to process MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    private void handleDeviceMessage(String deviceId, String msgType, Map<String, Object> data) {
        String sourceIdentity = "cabin/device/" + deviceId;
        catalog.observe(new DeviceObservation("MQTT_DEVICE_TOPIC", sourceIdentity, deviceId,
            Map.of("message_type", msgType, "payload_keys", data.keySet().stream().sorted().toList())));
        Optional<DeviceCatalogEntry> authorized = catalog.authorizedEntryForObservation(
            "MQTT_DEVICE_TOPIC", sourceIdentity);
        if (authorized.isEmpty()) return;
        DeviceCatalogEntry entry = authorized.get();
        if (!"mqtt".equals(entry.protocolAdapter()) || !sourceIdentity.equals(entry.connectionString())) {
            regress(entry, "Observed MQTT topic no longer matches the admitted catalog descriptor");
            return;
        }
        registry.registerDescriptor(entry.descriptor());
        DeviceStatus existing = registry.get(entry.deviceId());
        if (existing == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>(existing.attributes());
        attrs.putAll(data);
        String state = determineState(data);
        registry.update(new DeviceStatus(entry.deviceId(), existing.type(), existing.name(), state,
            Instant.now(), attrs, existing.location()));

        // Publish to Kafka for rules engine consumption
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), entry.deviceId(), "TELEMETRY",
            AlertSeverityClassifier.classify(data), Instant.now(), data);
        eventPublisher.publish(event);
    }

    private void regress(DeviceCatalogEntry entry, String reason) {
        if (entry.configurationStatus() == DeviceConfigurationStatus.CONFORMING) {
            catalog.setConfiguration(entry.deviceId(), DeviceConfigurationStatus.REGRESSED,
                "SYSTEM", "mqtt-bridge", reason);
        }
    }

    // Frigate's real topic shapes under cabin/camera/ (confirmed against a
    // live Frigate deployment 2026-08-02, not assumed from docs alone):
    //   cabin/camera/available          -- "online"/"offline", plain text
    //   cabin/camera/{name}/motion      -- "ON"/"OFF", plain text
    //   cabin/camera/{name}/{label}     -- object count, plain text number
    //   cabin/camera/events             -- the rich JSON detection stream;
    //                                       camera/label live INSIDE the
    //                                       payload, not the topic path
    // The previous version of this bridge treated whatever followed
    // cabin/camera/ as a camera ID and JSON-parsed every payload — wrong
    // for all four of these (parts[2]=="events" isn't a camera name, and
    // three of the four payloads aren't JSON at all).
    //
    // Found 2026-08-07: none of these branches ever called
    // registry.update() for the camera itself -- only handleDeviceMessage
    // (cabin/device/#) did. A camera's DeviceStatus.lastSeen was therefore
    // set once (whenever/however it first got registered) and never
    // refreshed, so DeviceHealthMonitor's 5-minute camera stale threshold
    // always fired exactly 5 minutes after that one registration and the
    // camera could never recover -- matching the reported "loads, then
    // disappears after ~5 min" symptom. Motion and per-label count topics
    // both name a real camera and both fire far more often than every 5
    // minutes whenever Frigate is actually seeing frames, so either is a
    // good liveness signal; touchCamera() is called from both.
    private void handleCameraTopic(String[] parts, String payload) {
        if (parts.length == 3 && "events".equals(parts[2])) {
            handleFrigateDetectionEvent(payload);
        } else if (parts.length == 4 && "motion".equals(parts[3])) {
            String cameraId = parts[2];
            touchCamera(cameraId, "motion").ifPresent(authorizedId -> {
                String state = "ON".equalsIgnoreCase(payload.trim()) ? "MOTION_ON" : "MOTION_OFF";
                eventPublisher.publish(new CabinEvent(
                    UUID.randomUUID().toString(), authorizedId, state,
                    "INFO", Instant.now(), Map.of("camera", authorizedId)));
            });
        } else if (parts.length == 4) {
            // per-label object-count topic, e.g. cabin/camera/driveway/car —
            // not published as a CabinEvent (fires too often to be useful,
            // see below), but still real evidence the camera is alive.
            touchCamera(parts[2], "object_count");
        }
        // `available` is deliberately still not handled here: it's
        // Frigate's single bridge-wide topic (parts.length==2), doesn't
        // name any one camera, and per-label count topics still aren't
        // published as CabinEvents — they'd mirror the JSON stream
        // cabin/camera/events already sends and fire far too often to be
        // useful "events", even though they're useful for touchCamera().
    }

    /** Retain any camera label, but touch runtime state only after admission. */
    private Optional<String> touchCamera(String cameraId, String signal) {
        String sourceIdentity = "cabin/camera/" + cameraId;
        catalog.observe(new DeviceObservation("FRIGATE_CAMERA", sourceIdentity, cameraId,
            Map.of("signal", signal)));
        Optional<DeviceCatalogEntry> authorized = catalog.authorizedEntryForObservation(
            "FRIGATE_CAMERA", sourceIdentity);
        if (authorized.isEmpty()) return Optional.empty();
        DeviceCatalogEntry entry = authorized.get();
        if (!"mqtt".equals(entry.protocolAdapter()) || !sourceIdentity.equals(entry.connectionString())
            || entry.type() != DeviceType.CAMERA) {
            regress(entry, "Observed Frigate camera source no longer matches the admitted catalog descriptor");
            return Optional.empty();
        }
        registry.registerDescriptor(entry.descriptor());
        DeviceStatus existing = registry.get(entry.deviceId());
        if (existing == null) return Optional.empty();
        registry.update(new DeviceStatus(existing.deviceId(), existing.type(), existing.name(),
            "ONLINE", Instant.now(), existing.attributes(), existing.location()));
        return Optional.of(entry.deviceId());
    }

    /**
     * {location}/presence/{personId} -- plain text "home"/"not_home",
     * same payload convention the underlying HA automation already
     * publishes (see docs/ontology.yaml's automation_cabin_security_
     * publish_nate_presence_from_phone). location comes from the topic
     * itself, not a constant -- this is what makes presence derivation
     * N-people x M-locations rather than assuming a single person at a
     * single site. Every message here both records the raw signal and
     * immediately re-derives the aggregate PresenceProfile, so the
     * toolbar's presence pin reflects reality within one MQTT round trip
     * of a phone joining or leaving the WiFi, not just whatever was last
     * manually picked.
     */
    private void handlePresenceTopic(String[] parts, String payload) {
        String location = parts[0];
        String personId = parts[2];
        String sourceIdentity = location + "/presence/" + personId;
        catalog.observe(new DeviceObservation("MQTT_PRESENCE_SOURCE", sourceIdentity,
            "presence-" + location + "-" + personId, Map.of("location", location, "person_id", personId)));
        if (catalog.authorizedEntryForObservation("MQTT_PRESENCE_SOURCE", sourceIdentity).isEmpty()) return;
        boolean present = "home".equalsIgnoreCase(payload.trim());
        presenceSignalRegistry.record(location, personId, present);
        presenceService.recomputeFromSignals();
    }

    /**
     * {location}/security/armed_away -- plain text "ON"/"OFF", published
     * by a real HA automation (see docs/ontology.yaml's automation_
     * cabin_security_publish_arm_state) that republishes on every toggle
     * AND on every HA restart, so this is already self-healing on the
     * publisher side -- cabin-backend just needs to actually listen.
     * Found 2026-08-08: nothing here ever did, despite this being exactly
     * the kind of "is this actually armed" answer a user asking about an
     * ambiguous alert needs and couldn't get from the UI at all.
     */
    private void handleArmedTopic(String location, String payload) {
        String sourceIdentity = location + "/security/armed_away";
        catalog.observe(new DeviceObservation("MQTT_SECURITY_SOURCE", sourceIdentity,
            "security-" + location + "-armed-away", Map.of("location", location)));
        if (catalog.authorizedEntryForObservation("MQTT_SECURITY_SOURCE", sourceIdentity).isEmpty()) return;
        boolean armed = "ON".equalsIgnoreCase(payload.trim());
        securityStateRegistry.record(location, armed);
    }

    @SuppressWarnings("unchecked")
    private void handleFrigateDetectionEvent(String payload) {
        try {
            Map<String, Object> data = mapper.readValue(payload, Map.class);
            String type = String.valueOf(data.getOrDefault("type", "unknown"));
            Map<String, Object> after = (Map<String, Object>) data.getOrDefault("after", Map.of());
            String camera = String.valueOf(after.getOrDefault("camera", "unknown"));
            Optional<String> authorizedCameraId = touchCamera(camera, "detection_event");
            if (authorizedCameraId.isEmpty()) return;
            String label = String.valueOf(after.getOrDefault("label", "object"));
            // Frigate's own event id (TrackedObject.to_dict()'s "id" field) --
            // required to fetch that specific event's snapshot/clip via
            // Frigate's /api/events/{id}/... endpoints. Not the same as this
            // CabinEvent's own random UUID below.
            Object frigateEventId = after.get("id");
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("label", label);
            eventPayload.put("score", after.getOrDefault("score", 0));
            eventPayload.put("type", type);
            if (frigateEventId != null) eventPayload.put("frigateEventId", frigateEventId);
            eventPayload.put("hasSnapshot", after.getOrDefault("has_snapshot", false));
            eventPayload.put("hasClip", after.getOrDefault("has_clip", false));
            CabinEvent event = new CabinEvent(
                UUID.randomUUID().toString(), authorizedCameraId.get(),
                "DETECTION_" + type.toUpperCase(),
                "INFO", Instant.now(), eventPayload);
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to parse Frigate detection event: {}", e.getMessage());
        }
    }

    private void handleDirectEvent(String severity, Map<String, Object> data) {
        String proposedDeviceId = String.valueOf(data.getOrDefault("deviceId", "system"));
        String sourceIdentity = "cabin/event/" + severity + "/" + proposedDeviceId;
        catalog.observe(new DeviceObservation("MQTT_DIRECT_EVENT", sourceIdentity,
            proposedDeviceId, Map.of("severity", severity,
                "payload_keys", data.keySet().stream().sorted().toList())));
        Optional<DeviceCatalogEntry> authorized = catalog.authorizedEntryForObservation(
            "MQTT_DIRECT_EVENT", sourceIdentity);
        if (authorized.isEmpty()) return;
        String deviceId = authorized.get().deviceId();
        String eventType = String.valueOf(data.getOrDefault("event", "UNKNOWN"));
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), deviceId, eventType,
            severity.toUpperCase(), Instant.now(), data);
        eventPublisher.publish(event);
    }

    private String determineState(Map<String, Object> data) {
        Object alarm = data.get("alarm");
        if (Boolean.TRUE.equals(alarm)) return "ALARM";
        return "ONLINE";
    }

    @Override public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void disconnect() {
        try { if (client != null && client.isConnected()) client.disconnect(); }
        catch (MqttException ignored) {}
    }
}
