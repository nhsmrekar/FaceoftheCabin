package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfig;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.security.DeviceLifecycleAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceController {

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter z2mAdapter;
    private final DeviceHealthMonitor healthMonitor;
    private final DeviceDisplayConfigService displayConfigService;
    private final DeviceLifecycleAccessPolicy lifecycleAccessPolicy;

    public DeviceController(DeviceRegistry registry,
                             Zigbee2MqttAdapter z2mAdapter,
                             DeviceHealthMonitor healthMonitor,
                             DeviceDisplayConfigService displayConfigService,
                             DeviceLifecycleAccessPolicy lifecycleAccessPolicy) {
        this.registry = registry;
        this.z2mAdapter = z2mAdapter;
        this.healthMonitor = healthMonitor;
        this.displayConfigService = displayConfigService;
        this.lifecycleAccessPolicy = lifecycleAccessPolicy;
    }

    /** List all registered devices with their current state */
    @GetMapping
    public List<DeviceStatus> listDevices() {
        return registry.all();
    }

    /** Get single device */
    @GetMapping("/{deviceId}")
    public DeviceStatus getDevice(@PathVariable String deviceId) {
        return registry.get(deviceId);
    }

    /**
     * Per-device checkin status (ON_SCHEDULE / LATE / MISSED) —
     * the "is it actually broken or just hasn't reported yet" signal, distinct
     * from DeviceStatus.state. See DeviceHealthMonitor's class comment.
     */
    @GetMapping("/checkin-status")
    public Map<String, String> checkinStatus() {
        Map<String, String> out = new LinkedHashMap<>();
        healthMonitor.getCheckinStatuses().forEach((id, status) -> out.put(id, status.name()));
        return out;
    }

    /** Register a new device (Device Manager UI → add device) */
    @PostMapping
    public DeviceDescriptor registerDevice(@RequestBody DeviceDescriptor descriptor) {
        throw lifecycleWorkflowRequired();
    }

    /** Update device config */
    @PutMapping("/{deviceId}")
    public DeviceDescriptor updateDevice(@PathVariable String deviceId,
                                           @RequestBody DeviceDescriptor descriptor) {
        throw lifecycleWorkflowRequired();
    }

    /** Remove device */
    @DeleteMapping("/{deviceId}")
    public Map<String, String> removeDevice(@PathVariable String deviceId) {
        throw lifecycleWorkflowRequired();
    }

    /** Send a command to a device */
    @PostMapping("/{deviceId}/command")
    public Map<String, Object> sendCommand(@PathVariable String deviceId,
                                            @RequestBody Map<String, Object> body) {
        String command = String.valueOf(body.get("command"));
        Object payload = body.get("payload");
        boolean ok = registry.sendCommand(deviceId, command, payload);
        return Map.of("deviceId", deviceId, "command", command, "accepted", ok);
    }

    /** List available device types and capabilities (Device Manager discovery) */
    @GetMapping("/meta/types")
    public Map<String, Object> deviceMeta() {
        return Map.of(
            "types", DeviceType.values(),
            "capabilities", DeviceCapability.values(),
            "adapters", List.of("mqtt", "ha_rest", "rtsp", "http_poll", "google_sdm")
        );
    }

    /**
     * Open or close the Zigbee pairing window.
     * Body: { "enable": true, "duration": 254 }
     * Duration is in seconds; 254 = max (~4m14s).
     */
    @PostMapping("/permit-join")
    public Map<String, Object> permitJoin(@RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        lifecycleAccessPolicy.requireActor(request);
        boolean enable = Boolean.TRUE.equals(body.get("enable"));
        int duration = body.containsKey("duration") ? ((Number) body.get("duration")).intValue() : 254;
        z2mAdapter.permitJoin(enable, duration);
        return Map.of("permitJoin", enable, "duration", duration);
    }

    /**
     * Get or update per-device config (DeviceDescriptor fields writable by the UI).
     * PATCH accepts a partial map; only 'name' and 'enabled' are mutable here.
     */
    @GetMapping("/{deviceId}/config")
    public Map<String, Object> getDeviceConfig(@PathVariable String deviceId,
                                                HttpServletRequest request) {
        lifecycleAccessPolicy.requireActor(request);
        return registry.descriptor(deviceId)
            .map(d -> Map.<String, Object>of(
                "deviceId", d.deviceId(),
                "name", d.name(),
                "type", d.type(),
                "capabilities", d.capabilities(),
                "protocolAdapter", d.protocolAdapter(),
                "connectionConfigured", !d.connectionString().isBlank(),
                "enabled", d.enabled(),
                "location", d.location()
            ))
            .orElse(Map.of("error", "not found"));
    }

    @PatchMapping("/{deviceId}/config")
    public Map<String, Object> patchDeviceConfig(@PathVariable String deviceId,
                                                   @RequestBody Map<String, Object> patch) {
        throw lifecycleWorkflowRequired();
    }

    private ResponseStatusException lifecycleWorkflowRequired() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Direct registration/configuration is disabled; use the authenticated device catalog lifecycle workflow");
    }

    // ── Display-config endpoints ───────────────────────────────────────────────

    /**
     * Bulk: all display configs for an active presence profile.
     * GET /api/devices/display-config?profile=AT_HOME
     */
    @GetMapping("/display-config")
    public List<DeviceDisplayConfig> bulkDisplayConfig(@RequestParam String profile) {
        return displayConfigService.allForProfile(profile);
    }

    /**
     * Single device, single profile.
     * GET /api/devices/{id}/display-config?profile=AT_HOME
     */
    @GetMapping("/{deviceId}/display-config")
    public Map<String, Object> getDisplayConfig(@PathVariable String deviceId,
                                                 @RequestParam String profile) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        return displayConfigService.get(deviceId, location, profile)
            .map(c -> Map.<String, Object>of(
                "deviceId",        c.deviceId(),
                "location",        c.location(),
                "presenceProfile", c.presenceProfile(),
                "displayName",     c.displayName() != null ? c.displayName() : "",
                "stateLabelMap",   c.stateLabelMap(),
                "severityOverride", c.severityOverride() != null ? c.severityOverride() : ""))
            .orElse(Map.of("deviceId", deviceId, "presenceProfile", profile,
                           "displayName", "", "stateLabelMap", Map.of(), "severityOverride", ""));
    }

    /**
     * Upsert display config.
     * PATCH /api/devices/{id}/display-config?profile=AT_HOME
     * Body: { displayName, stateLabelMap, severityOverride }
     */
    @PatchMapping("/{deviceId}/display-config")
    @SuppressWarnings("unchecked")
    public DeviceDisplayConfig patchDisplayConfig(@PathVariable String deviceId,
                                                   @RequestParam String profile,
                                                   @RequestBody Map<String, Object> body) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        String displayName     = (String) body.getOrDefault("displayName", null);
        Object labelRaw        = body.get("stateLabelMap");
        Map<String, String> labelMap = (labelRaw instanceof Map) ? (Map<String, String>) labelRaw : Map.of();
        String severityOverride = (String) body.getOrDefault("severityOverride", null);
        if (severityOverride != null && severityOverride.isBlank()) severityOverride = null;
        if (displayName      != null && displayName.isBlank())      displayName = null;
        return displayConfigService.upsert(
            new DeviceDisplayConfig(deviceId, location, profile, displayName, labelMap, severityOverride));
    }

    /**
     * Delete display config for one device+profile combination.
     * DELETE /api/devices/{id}/display-config?profile=AT_HOME
     */
    @DeleteMapping("/{deviceId}/display-config")
    public Map<String, String> deleteDisplayConfig(@PathVariable String deviceId,
                                                    @RequestParam String profile) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        displayConfigService.delete(deviceId, location, profile);
        return Map.of("deleted", deviceId, "profile", profile);
    }
}
