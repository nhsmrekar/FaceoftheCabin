package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.catalog.*;
import com.cabin.orchestrator.devices.model.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all devices across all locations.
 * - DeviceDescriptor: static config (capabilities, adapter, connection, location)
 * - DeviceStatus: runtime state (updated by MQTT bridge and HA polling)
 * Dispatches commands to the correct ProtocolAdapter.
 */
@Component
public class DeviceRegistry {

    private final Map<String, DeviceStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, DeviceDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, ProtocolAdapter> adapters = new ConcurrentHashMap<>();
    private final DeviceCatalogService catalog;

    public DeviceRegistry(List<ProtocolAdapter> adapterList, DeviceCatalogService catalog) {
        this.catalog = catalog;
        adapterList.forEach(a -> adapters.put(a.adapterType(), a));
        seedDefaults();
    }

    private void seedDefaults() {
        // Catalog knowledge is not runtime registration. These records are
        // AVAILABLE, READY_TO_CONFIGURE, DISABLED, and unbound until an
        // observed immutable identity is explicitly admitted. The 13 cabin
        // Zigbee records preserve ontology semantics without grandfathering
        // the historical friendly names into operational authority.
        offer("z2m-motion_entry", "zigbee_motion_entry", "Entry Motion Sensor",
            DeviceType.MOTION_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.PRESENCE),
            "mqtt", "zigbee2mqtt/motion_entry", "cabin");
        offer("z2m-door_front_contact", "zigbee_door_front_contact", "Front Door Contact",
            DeviceType.CONTACT_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.PRESENCE),
            "mqtt", "zigbee2mqtt/door_front_contact", "cabin");
        offer("z2m-door_second_contact", "zigbee_door_second_contact", "Second Door Contact",
            DeviceType.CONTACT_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.PRESENCE),
            "mqtt", "zigbee2mqtt/door_second_contact", "cabin");
        offer("z2m-temp_outside_lowest", "zigbee_temp_outside_lowest", "Outside Low Temperature Probe",
            DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/temp_outside_lowest", "cabin");
        offer("z2m-temp_kitchen", "zigbee_temp_kitchen", "Kitchen Temperature/Humidity",
            DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/temp_kitchen", "cabin");
        offer("z2m-temp_mech_room", "zigbee_temp_mech_room", "Mechanical Room Temperature/Humidity",
            DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/temp_mech_room", "cabin");
        offer("z2m-leak_mech_room", "zigbee_leak_mech_room", "Mechanical Room Leak Sensor",
            DeviceType.WATER_LEAK_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "mqtt", "zigbee2mqtt/leak_mech_room", "cabin");
        offer("z2m-leak_alarm_fridge", "zigbee_leak_alarm_fridge", "Fridge Leak Alarm",
            DeviceType.WATER_LEAK_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM, DeviceCapability.COMMAND),
            "mqtt", "zigbee2mqtt/leak_alarm_fridge", "cabin");
        offer("z2m-leak_alarm_dishwasher", "zigbee_leak_alarm_dishwasher", "Dishwasher Leak Alarm",
            DeviceType.WATER_LEAK_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM, DeviceCapability.COMMAND),
            "mqtt", "zigbee2mqtt/leak_alarm_dishwasher", "cabin");
        offer("z2m-leak_alarm_bathroom", "zigbee_leak_alarm_bathroom", "Bathroom Leak Alarm",
            DeviceType.WATER_LEAK_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM, DeviceCapability.COMMAND),
            "mqtt", "zigbee2mqtt/leak_alarm_bathroom", "cabin");
        offer("z2m-heater_mech_room", "zigbee_heater_mech_room", "Mechanical Room Heater Plug",
            DeviceType.POWER_METER, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.POWER_MONITOR),
            "mqtt", "zigbee2mqtt/heater_mech_room", "cabin");
        offer("z2m-main_water_valve", "zigbee_main_water_valve", "Main Water Valve",
            DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND),
            "mqtt", "zigbee2mqtt/main_water_valve", "cabin");
        offer("z2m-smart_switch_breaker_box", "zigbee_smart_switch_breaker_box", "Breaker Box Smart Switch",
            DeviceType.POWER_METER, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.POWER_MONITOR),
            "mqtt", "zigbee2mqtt/smart_switch_breaker_box", "cabin");

        // Home inventory is described but not purchased/deployed. "Available"
        // is the ontology state; it is not PLANNED and creates no DeviceStatus.
        offer("home-cam-front", null, "Home Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "rtsp",
            "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.20:554/h264Preview_01_main", "home");
        offer("home-cam-driveway", null, "Home Driveway Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "rtsp",
            "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.21:554/h264Preview_01_main", "home");
        offer("home-cam-backyard", null, "Home Backyard Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "rtsp",
            "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.22:554/h264Preview_01_main", "home");
        offer("home-cam-garage", null, "Home Garage Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "rtsp",
            "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.23:554/h264Preview_01_main", "home");
        offer("home-cam-side", null, "Home Side Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "rtsp",
            "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.24:554/h264Preview_01_main", "home");
        offer("home-lock-front", null, "Home Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_front_door", "home");
        offer("home-lock-back", null, "Home Back Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_back_door", "home");
        offer("home-thermostat-main", null, "Home Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_thermostat", "home");
        offer("home-smoke-co-main", null, "Home Smoke/CO Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.home_kidde_smoke_co", "home");
        offer("home-energy-main", null, "Home Energy Monitor", DeviceType.POWER_METER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.POWER_MONITOR),
            "ha_rest", "sensor.home_emporia_total_power_w", "home");
        offer("home-lg-washer", null, "Home LG Washer", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_washer_state", "home");
        offer("home-lg-dryer", null, "Home LG Dryer", DeviceType.DRYER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_dryer_state", "home");
        offer("home-bosch-dishwasher", null, "Home Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_bosch_dishwasher_door", "home");
        offer("home-daikin-hvac", null, "Home Daikin Aurora HVAC", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_daikin_aurora", "home");
    }

    private void offer(String deviceId, String ontologyId, String name, DeviceType type,
                       Set<DeviceCapability> capabilities, String protocolAdapter,
                       String connectionString, String location) {
        catalog.ensureAvailableEntry(new DeviceCatalogEntry(deviceId, ontologyId, name, type,
            capabilities, protocolAdapter, connectionString, location,
            DeviceIdentityAssurance.UNRESOLVED, DeviceAdmissionStatus.AVAILABLE,
            DeviceConfigurationStatus.READY_TO_CONFIGURE, DeviceEnablementStatus.DISABLED,
            Instant.now()));
    }

    /** Activate only the exact descriptor from an operationally authorized catalog entry. */
    public boolean registerDescriptor(DeviceDescriptor desc) {
        Optional<DeviceCatalogEntry> authorized = catalog.authorizedEntryForDeviceId(desc.deviceId());
        if (authorized.isEmpty() || !authorized.get().matchesDescriptor(desc)) return false;
        descriptors.put(desc.deviceId(), desc);
        if (!statuses.containsKey(desc.deviceId())) {
            statuses.put(desc.deviceId(), new DeviceStatus(
                desc.deviceId(), desc.type(), desc.name(), "UNKNOWN",
                Instant.now(), Map.of(), desc.location()));
        }
        return true;
    }

    public boolean register(DeviceStatus status) {
        if (!operationallyAuthorized(status.deviceId())) return false;
        statuses.put(status.deviceId(), status);
        return true;
    }

    public boolean update(DeviceStatus status) {
        if (!operationallyAuthorized(status.deviceId())) return false;
        statuses.put(status.deviceId(), status);
        return true;
    }

    /** Re-check current catalog authority; runtime registration is not permanent permission. */
    public boolean operationallyAuthorized(String deviceId) {
        return descriptors.containsKey(deviceId)
            && catalog.authorizedEntryForDeviceId(deviceId).isPresent();
    }

    public void remove(String deviceId) {
        statuses.remove(deviceId);
        descriptors.remove(deviceId);
    }

    public List<DeviceStatus> all() {
        return statuses.values().stream()
            .filter(status -> operationallyAuthorized(status.deviceId()))
            .toList();
    }

    public List<DeviceStatus> byLocation(String location) {
        return all().stream()
            .filter(s -> location.equals(s.location()))
            .toList();
    }

    public DeviceStatus get(String deviceId) {
        return operationallyAuthorized(deviceId) ? statuses.get(deviceId) : null;
    }

    public Optional<DeviceDescriptor> descriptor(String deviceId) {
        return operationallyAuthorized(deviceId)
            ? Optional.ofNullable(descriptors.get(deviceId))
            : Optional.empty();
    }

    public boolean sendCommand(String deviceId, String command, Object payload) {
        if (!operationallyAuthorized(deviceId)) return false;
        DeviceDescriptor desc = descriptors.get(deviceId);
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return false;
        return adapter.sendCommand(desc, command, payload);
    }

    /**
     * Actively poll a device's adapter for its current state, bypassing the
     * passive last-seen cache. Empty means the device didn't answer (or has
     * no descriptor/adapter, or its adapter doesn't support polling — e.g.
     * MQTT devices are push-only and always return empty here).
     */
    public Optional<DeviceStatus> activeFetch(String deviceId) {
        if (!operationallyAuthorized(deviceId)) return Optional.empty();
        DeviceDescriptor desc = descriptors.get(deviceId);
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return Optional.empty();
        return adapter.fetchState(desc);
    }
}
