package com.cabin.orchestrator.devices.catalog;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Durable catalog truth for one platform device. Lifecycle axes deliberately
 * remain independent; operational authority is the fail-closed conjunction.
 */
public record DeviceCatalogEntry(
    String deviceId,
    String ontologyId,
    String name,
    DeviceType type,
    Set<DeviceCapability> capabilities,
    String protocolAdapter,
    String connectionString,
    String location,
    DeviceIdentityAssurance identityAssurance,
    DeviceAdmissionStatus admissionStatus,
    DeviceConfigurationStatus configurationStatus,
    DeviceEnablementStatus enablementStatus,
    Instant updatedAt
) {
    public DeviceCatalogEntry {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        Objects.requireNonNull(protocolAdapter, "protocolAdapter");
        Objects.requireNonNull(connectionString, "connectionString");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(identityAssurance, "identityAssurance");
        Objects.requireNonNull(admissionStatus, "admissionStatus");
        Objects.requireNonNull(configurationStatus, "configurationStatus");
        Objects.requireNonNull(enablementStatus, "enablementStatus");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean operationallyAuthorized() {
        return identityAssurance == DeviceIdentityAssurance.VERIFIED
            && admissionStatus == DeviceAdmissionStatus.ADMITTED
            && configurationStatus == DeviceConfigurationStatus.CONFORMING
            && enablementStatus == DeviceEnablementStatus.ENABLED;
    }

    public DeviceDescriptor descriptor() {
        return new DeviceDescriptor(deviceId, name, type, capabilities, protocolAdapter,
            connectionString, enablementStatus == DeviceEnablementStatus.ENABLED, location);
    }

    public boolean matchesDescriptor(DeviceDescriptor descriptor) {
        return descriptor != null
            && deviceId.equals(descriptor.deviceId())
            && name.equals(descriptor.name())
            && type == descriptor.type()
            && capabilities.equals(descriptor.capabilities())
            && protocolAdapter.equals(descriptor.protocolAdapter())
            && connectionString.equals(descriptor.connectionString())
            && location.equals(descriptor.location())
            && descriptor.enabled() == (enablementStatus == DeviceEnablementStatus.ENABLED);
    }

    public boolean configurationComplete() {
        return !deviceId.isBlank() && !name.isBlank() && !protocolAdapter.isBlank()
            && !connectionString.isBlank() && !location.isBlank();
    }

    public DeviceCatalogEntry withLifecycle(DeviceIdentityAssurance identity,
                                             DeviceAdmissionStatus admission,
                                             DeviceConfigurationStatus configuration,
                                             DeviceEnablementStatus enablement) {
        return new DeviceCatalogEntry(deviceId, ontologyId, name, type, capabilities,
            protocolAdapter, connectionString, location, identity, admission,
            configuration, enablement, Instant.now());
    }
}
