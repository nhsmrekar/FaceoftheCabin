package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.catalog.*;
import com.cabin.orchestrator.security.DeviceLifecycleAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Private review surface for retained candidates and independent lifecycle
 * axes. Every method requires both Google authentication (WebConfig) and the
 * narrower operator allowlist; no broad CORS policy is attached.
 */
@RestController
@RequestMapping("/api/device-catalog")
public class DeviceCatalogController {

    private final DeviceCatalogService catalog;
    private final DeviceLifecycleAccessPolicy accessPolicy;

    public DeviceCatalogController(DeviceCatalogService catalog,
                                   DeviceLifecycleAccessPolicy accessPolicy) {
        this.catalog = catalog;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/entries")
    public List<DeviceCatalogEntryView> entries(HttpServletRequest request) {
        accessPolicy.requireActor(request);
        return catalog.allEntries().stream().map(DeviceCatalogEntryView::from).toList();
    }

    @GetMapping("/candidates")
    public List<DeviceObservationCandidate> candidates(HttpServletRequest request) {
        accessPolicy.requireActor(request);
        return catalog.allCandidates();
    }

    @GetMapping("/candidates/{candidateId}/decisions")
    public List<DeviceLifecycleDecision> decisions(@PathVariable String candidateId,
                                                    HttpServletRequest request) {
        accessPolicy.requireActor(request);
        return catalog.decisionsForCandidate(candidateId);
    }

    @PostMapping("/candidates/{candidateId}/admit")
    public DeviceCatalogEntryView admit(@PathVariable String candidateId,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        return DeviceCatalogEntryView.from(catalog.admitCandidate(candidateId,
            required(body, "deviceId"), accessPolicy.requireActor(request),
            optional(body, "reason")));
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public DeviceObservationCandidate reject(@PathVariable String candidateId,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        return catalog.rejectCandidate(candidateId, accessPolicy.requireActor(request),
            optional(body, "reason"));
    }

    @PostMapping("/candidates/{candidateId}/reconsider")
    public DeviceObservationCandidate reconsider(@PathVariable String candidateId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return catalog.reconsiderCandidate(candidateId, accessPolicy.requireActor(request),
            optional(body, "reason"));
    }

    @PatchMapping("/entries/{deviceId}/configuration")
    public DeviceCatalogEntryView configuration(@PathVariable String deviceId,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        return DeviceCatalogEntryView.from(catalog.setConfiguration(deviceId,
            DeviceConfigurationStatus.valueOf(required(body, "status").toUpperCase()),
            "HUMAN", accessPolicy.requireActor(request), optional(body, "reason")));
    }

    @PatchMapping("/entries/{deviceId}/enablement")
    public DeviceCatalogEntryView enablement(@PathVariable String deviceId,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        return DeviceCatalogEntryView.from(catalog.setEnablement(deviceId,
            DeviceEnablementStatus.valueOf(required(body, "status").toUpperCase()),
            "HUMAN", accessPolicy.requireActor(request), optional(body, "reason")));
    }

    private String required(Map<String, Object> body, String key) {
        String value = optional(body, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String optional(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NoSuchElementException error) {
        return Map.of("error", message(error, "Catalog record not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("error", message(error, "Invalid lifecycle request"));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) {
        return Map.of("error", message(error, "Lifecycle transition is not allowed"));
    }

    private String message(RuntimeException error, String fallback) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? fallback : error.getMessage();
    }

    /** Operator-safe projection: connection material never crosses the API. */
    public record DeviceCatalogEntryView(
        String deviceId,
        String ontologyId,
        String name,
        com.cabin.orchestrator.devices.model.DeviceType type,
        Set<com.cabin.orchestrator.devices.model.DeviceCapability> capabilities,
        String protocolAdapter,
        boolean connectionConfigured,
        String location,
        DeviceIdentityAssurance identityAssurance,
        DeviceAdmissionStatus admissionStatus,
        DeviceConfigurationStatus configurationStatus,
        DeviceEnablementStatus enablementStatus,
        boolean operationallyAuthorized,
        Instant updatedAt
    ) {
        static DeviceCatalogEntryView from(DeviceCatalogEntry entry) {
            return new DeviceCatalogEntryView(entry.deviceId(), entry.ontologyId(),
                entry.name(), entry.type(), entry.capabilities(), entry.protocolAdapter(),
                !entry.connectionString().isBlank(), entry.location(),
                entry.identityAssurance(), entry.admissionStatus(),
                entry.configurationStatus(), entry.enablementStatus(),
                entry.operationallyAuthorized(), entry.updatedAt());
        }
    }
}
