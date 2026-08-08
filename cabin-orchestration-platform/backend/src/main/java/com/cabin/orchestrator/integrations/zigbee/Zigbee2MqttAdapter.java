package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bridges Zigbee2MQTT into the DeviceRegistry.
 *
 * Subscriptions:
 *   zigbee2mqtt/bridge/devices        — device list (device discovery)
 *   zigbee2mqtt/bridge/state          — bridge health
 *   zigbee2mqtt/{friendly_name}       — per-device state updates
 *
 * Publishes:
 *   zigbee2mqtt/bridge/request/permit_join   — open/close pairing window
 *   zigbee2mqtt/{friendly_name}/set          — commands to device
 *
 * Device IDs are prefixed with "z2m-" to avoid collision with MQTT/HA devices.
 * Location is always "cabin" (Z2M is cabin-only for now; extend if home-hub
 * gets a coordinator).
 */
@Service
public class Zigbee2MqttAdapter implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(Zigbee2MqttAdapter.class);
    private static final String Z2M_PREFIX = "zigbee2mqtt/";
    private static final String DEVICE_ID_PREFIX = "z2m-";

    @Value("${cabin.mqtt.brokerUrl:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${cabin.zigbee.location:cabin}")
    private String zigbeeLocation;

    private MqttClient client;
    private final DeviceRegistry registry;
    private final EventPublisher eventPublisher;
    private final SignalQualityRegistry signalQualityRegistry;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Tracks friendly names seen via bridge/devices so we know which topics are Z2M devices
    private final Set<String> knownFriendlyNames = ConcurrentHashMap.newKeySet();
    // Tracks whether bridge is online
    private volatile String bridgeState = "offline";
    private final Map<String, CompletableFuture<Boolean>> availabilityProbes = new ConcurrentHashMap<>();

    public Zigbee2MqttAdapter(DeviceRegistry registry, EventPublisher eventPublisher,
                               SignalQualityRegistry signalQualityRegistry) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.signalQualityRegistry = signalQualityRegistry;
    }

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(brokerUrl, "z2m-adapter-" + UUID.randomUUID());
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            client.connect(opts);
            client.subscribe(Z2M_PREFIX + "bridge/devices", 1);
            client.subscribe(Z2M_PREFIX + "bridge/state", 1);
            client.subscribe(Z2M_PREFIX + "#", 0);
            log.info("Zigbee2MQTT adapter connected to {}", brokerUrl);
        } catch (MqttException e) {
            log.warn("Zigbee2MQTT adapter connect failed (Z2M may not be running): {}", e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            completeAvailabilityProbe(topic, payload, message.isRetained());
            if (topic.equals(Z2M_PREFIX + "bridge/devices")) {
                handleBridgeDeviceList(payload);
            } else if (topic.equals(Z2M_PREFIX + "bridge/state")) {
                handleBridgeState(payload);
            } else if (topic.startsWith(Z2M_PREFIX) && !topic.contains("/set") && !topic.contains("/get")) {
                String friendlyName = topic.substring(Z2M_PREFIX.length());
                if (friendlyName.endsWith("/availability")) {
                    // Z2M availability is the authoritative online/offline signal — use it directly
                    String name = friendlyName.substring(0, friendlyName.lastIndexOf("/availability"));
                    if (knownFriendlyNames.contains(name)) handleAvailability(name, payload);
                } else if (!friendlyName.startsWith("bridge/") && knownFriendlyNames.contains(friendlyName)) {
                    handleDeviceState(friendlyName, payload);
                }
            }
        } catch (Exception e) {
            log.warn("Z2M message processing error on {}: {}", topic, e.getMessage());
        }
    }

    private void completeAvailabilityProbe(String topic, String payload, boolean retained) {
        CompletableFuture<Boolean> probe = availabilityProbes.get(topic);
        if (probe == null || !retained) return;
        parseAvailability(payload).ifPresentOrElse(probe::complete, () -> probe.completeExceptionally(
            new IllegalArgumentException("Malformed retained availability")));
    }

    static Optional<Boolean> parseAvailability(String payload) {
        try {
            String raw = payload.trim();
            if ("online".equalsIgnoreCase(raw)) return Optional.of(true);
            if ("offline".equalsIgnoreCase(raw)) return Optional.of(false);
            JsonNode node = new ObjectMapper().readTree(payload);
            String state = node.has("state") ? node.get("state").asText() : "";
            if ("online".equalsIgnoreCase(state)) return Optional.of(true);
            if ("offline".equalsIgnoreCase(state)) return Optional.of(false);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Re-subscribe and require a retained authoritative availability replay. */
    public Optional<Boolean> probeRetainedAvailability(String deviceTopic, Duration timeout) {
        if (client == null || !client.isConnected() || deviceTopic == null || deviceTopic.isBlank()) {
            return Optional.empty();
        }
        String topic = deviceTopic + "/availability";
        CompletableFuture<Boolean> probe = new CompletableFuture<>();
        availabilityProbes.put(topic, probe);
        try {
            client.subscribe(topic, 1);
            return Optional.of(probe.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.debug("No retained Z2M availability reply for {}: {}", deviceTopic, e.getMessage());
            return Optional.empty();
        } finally {
            availabilityProbes.remove(topic, probe);
        }
    }

    private void handleBridgeState(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            bridgeState = node.has("state") ? node.get("state").asText() : payload.trim();
            log.debug("Z2M bridge state: {}", bridgeState);
        } catch (Exception e) {
            bridgeState = payload.trim();
        }
    }

    private void handleAvailability(String friendlyName, String payload) {
        String deviceId = DEVICE_ID_PREFIX + friendlyName.replace(" ", "_");
        try {
            JsonNode node = mapper.readTree(payload);
            String avail = node.has("state") ? node.get("state").asText() : payload.trim();
            DeviceStatus existing = registry.get(deviceId);
            if (existing == null) return;
            String newState = "online".equalsIgnoreCase(avail) ? "ONLINE" : "OFFLINE";
            // Only update state, preserve existing attributes
            registry.update(new DeviceStatus(
                deviceId, existing.type(), existing.name(), newState,
                Instant.now(), existing.attributes(), existing.location()));
            log.debug("Z2M availability: {} -> {}", friendlyName, newState);
        } catch (Exception e) {
            log.warn("Failed to parse Z2M availability for {}: {}", friendlyName, e.getMessage());
        }
    }

    /**
     * Parses the zigbee2mqtt/bridge/devices array and registers any new devices.
     * Each element has: ieee_address, friendly_name, type, definition.exposes[]
     */
    private void handleBridgeDeviceList(String payload) {
        try {
            JsonNode devices = mapper.readTree(payload);
            if (!devices.isArray()) return;
            for (JsonNode device : devices) {
                String friendlyName = device.path("friendly_name").asText(null);
                if (friendlyName == null || friendlyName.equals("Coordinator")) continue;
                knownFriendlyNames.add(friendlyName);
                String deviceId = DEVICE_ID_PREFIX + friendlyName.replace(" ", "_");
                if (registry.descriptor(deviceId).isPresent()) continue; // already registered

                JsonNode definition = device.path("definition");
                Set<DeviceCapability> caps = inferCapabilities(definition);
                DeviceType type = inferType(definition, caps);

                DeviceDescriptor desc = new DeviceDescriptor(
                    deviceId,
                    friendlyName,
                    type,
                    caps,
                    "mqtt",
                    Z2M_PREFIX + friendlyName,
                    true,
                    zigbeeLocation
                );
                registry.registerDescriptor(desc);
                log.info("Z2M registered device: {} ({})", friendlyName, type);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Z2M device list: {}", e.getMessage());
        }
    }

    /**
     * Handles per-device state messages. The Z2M payload is a flat JSON object
     * with property names matching the 'property' fields from definition.exposes.
     * Unknown properties are stored in attributes as-is.
     */
    private void handleDeviceState(String friendlyName, String payload) {
        String deviceId = DEVICE_ID_PREFIX + friendlyName.replace(" ", "_");
        try {
            JsonNode node = mapper.readTree(payload);
            if (!node.isObject()) return;

            DeviceStatus existing = registry.get(deviceId);
            if (existing == null) return; // not registered yet; bridge/devices will handle it

            Map<String, Object> attrs = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> attrs.put(e.getKey(), jsonNodeToValue(e.getValue())));

            // PROTOTYPE, 2026-08-08 -- see SignalQualityRegistry's own
            // comment. linkquality was already parsed into attrs on every
            // message (deriveState below already reads it); this just
            // also trends it so a future evaluation pass has real history
            // to look at, not just the always-overwritten latest value.
            if (attrs.get("linkquality") instanceof Number lqi) {
                signalQualityRegistry.record(deviceId, lqi.intValue());
            }

            String state = deriveState(attrs, existing.type());
            registry.update(new DeviceStatus(
                deviceId, existing.type(), existing.name(), state,
                Instant.now(), attrs, existing.location()));

            // Same publish MqttBridgeService.handleDeviceMessage() does for
            // non-Zigbee devices — this adapter was updating DeviceRegistry
            // (live state) without ever writing to cabin_event, so Zigbee
            // motion/contact/etc. activity never showed up in event history.
            CabinEvent event = new CabinEvent(
                UUID.randomUUID().toString(), deviceId, "TELEMETRY",
                AlertSeverityClassifier.classify(attrs), Instant.now(), attrs);
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to parse Z2M state for {}: {}", friendlyName, e.getMessage());
        }
    }

    /**
     * Infers DeviceCapabilities from definition.exposes[].
     * Handles generic feature types ('binary', 'numeric', 'enum') and
     * composite features that have sub-features (e.g. water_leak_buzzer
     * nested under a composite).
     */
    private Set<DeviceCapability> inferCapabilities(JsonNode definition) {
        Set<DeviceCapability> caps = new HashSet<>();
        if (definition.isMissingNode()) {
            caps.add(DeviceCapability.TELEMETRY);
            return caps;
        }
        JsonNode exposes = definition.path("exposes");
        if (!exposes.isArray()) {
            caps.add(DeviceCapability.TELEMETRY);
            return caps;
        }
        for (JsonNode expose : exposes) {
            processExpose(expose, caps);
        }
        if (caps.isEmpty()) caps.add(DeviceCapability.TELEMETRY);
        return caps;
    }

    private void processExpose(JsonNode expose, Set<DeviceCapability> caps) {
        String type = expose.path("type").asText("");
        String property = expose.path("property").asText("");
        String name = expose.path("name").asText("");
        int access = expose.path("access").asInt(1);
        boolean writable = (access & 2) != 0;

        switch (type) {
            case "binary" -> {
                if (property.contains("water_leak") || property.contains("smoke") ||
                    property.contains("alarm") || property.contains("tamper")) {
                    caps.add(DeviceCapability.ALARM);
                } else if (property.contains("occupancy") || property.contains("presence")) {
                    caps.add(DeviceCapability.PRESENCE);
                } else if (property.contains("contact") || property.contains("lock")) {
                    if (writable) caps.add(DeviceCapability.COMMAND);
                    caps.add(DeviceCapability.ACCESS_CONTROL);
                } else {
                    caps.add(DeviceCapability.TELEMETRY);
                    if (writable) caps.add(DeviceCapability.COMMAND);
                }
            }
            case "numeric" -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
                if (property.contains("power") || property.contains("energy") ||
                    property.contains("current") || property.contains("voltage")) {
                    caps.add(DeviceCapability.POWER_MONITOR);
                }
            }
            case "enum" -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
            }
            case "climate" -> {
                caps.add(DeviceCapability.TELEMETRY);
                caps.add(DeviceCapability.COMMAND);
                caps.add(DeviceCapability.CLIMATE);
            }
            case "lock" -> {
                caps.add(DeviceCapability.COMMAND);
                caps.add(DeviceCapability.ACCESS_CONTROL);
            }
            case "composite" -> {
                // Recurse into sub-features (e.g. water_leak_buzzer on THIRDREALITY)
                JsonNode features = expose.path("features");
                if (features.isArray()) {
                    for (JsonNode sub : features) processExpose(sub, caps);
                }
            }
            default -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
            }
        }
    }

    private DeviceType inferType(JsonNode definition, Set<DeviceCapability> caps) {
        String model = definition.path("model").asText("").toLowerCase();
        String desc = definition.path("description").asText("").toLowerCase();
        String vendor = definition.path("vendor").asText("").toLowerCase();

        if (model.contains("snzb-05") || desc.contains("water leak")) return DeviceType.WATER_LEAK_SENSOR;
        if (model.contains("snzb-03") || desc.contains("motion"))      return DeviceType.MOTION_SENSOR;
        if (model.contains("snzb-04") || desc.contains("contact"))     return DeviceType.CONTACT_SENSOR;
        if (model.contains("snzb-02") || desc.contains("temperature")) return DeviceType.TEMPERATURE_SENSOR;
        if (model.contains("zbminir") || desc.contains("switch"))      return DeviceType.HOME_ASSISTANT_ENTITY;
        if (desc.contains("smoke") || desc.contains("co alarm"))       return DeviceType.SMOKE_ALARM;
        if (desc.contains("smart plug") || desc.contains("outlet"))    return DeviceType.POWER_METER;
        if (caps.contains(DeviceCapability.CLIMATE))                   return DeviceType.THERMOSTAT;
        if (caps.contains(DeviceCapability.ACCESS_CONTROL))            return DeviceType.LOCK;
        if (caps.contains(DeviceCapability.ALARM))                     return DeviceType.WATER_LEAK_SENSOR;
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    private String deriveState(Map<String, Object> attrs, DeviceType type) {
        // Water leak / alarm sensors
        Object waterLeak = attrs.get("water_leak");
        if (Boolean.TRUE.equals(waterLeak)) return "ALARM";
        Object smoke = attrs.get("smoke");
        if (Boolean.TRUE.equals(smoke)) return "ALARM";
        // Generic alarm property
        Object alarm = attrs.get("alarm");
        if (Boolean.TRUE.equals(alarm)) return "ALARM";
        // Contact/motion: report as ONLINE but leave state informational in attrs
        Object linkquality = attrs.get("linkquality");
        if (linkquality == null) return "UNKNOWN";
        return "ONLINE";
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber())  return node.numberValue();
        if (node.isTextual()) return node.textValue();
        return node.toString();
    }

    /** Open or close the Zigbee pairing window. duration=254 = max (4m14s). */
    public void permitJoin(boolean enable, int duration) {
        if (client == null || !client.isConnected()) {
            log.warn("Z2M not connected — cannot permit_join");
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("value", enable);
            if (enable) body.put("time", duration);
            String json = mapper.writeValueAsString(body);
            client.publish(Z2M_PREFIX + "bridge/request/permit_join",
                new MqttMessage(json.getBytes()));
            log.info("Z2M permit_join={} duration={}s", enable, duration);
        } catch (Exception e) {
            log.error("Z2M permit_join failed: {}", e.getMessage());
        }
    }

    /** Send a property update to a Zigbee device (e.g. water_leak_buzzer: true). */
    public boolean sendCommand(String friendlyName, Map<String, Object> payload) {
        if (client == null || !client.isConnected()) return false;
        try {
            String json = mapper.writeValueAsString(payload);
            client.publish(Z2M_PREFIX + friendlyName + "/set",
                new MqttMessage(json.getBytes()));
            return true;
        } catch (Exception e) {
            log.error("Z2M sendCommand to {} failed: {}", friendlyName, e.getMessage());
            return false;
        }
    }

    public String getBridgeState() { return bridgeState; }
    public Set<String> getKnownFriendlyNames() { return Collections.unmodifiableSet(knownFriendlyNames); }

    @Override public void connectionLost(Throwable cause) {
        log.warn("Z2M adapter connection lost: {}", cause.getMessage());
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void disconnect() {
        try { if (client != null && client.isConnected()) client.disconnect(); }
        catch (MqttException ignored) {}
    }
}
