package com.cabin.orchestrator.devices.catalog;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;

import java.time.Instant;
import java.util.Map;

public final class DeviceCatalogTestSupport {
    private DeviceCatalogTestSupport() {}

    public static DeviceCatalogEntry authorize(DeviceCatalogService catalog,
                                                String sourceType,
                                                String sourceIdentity,
                                                DeviceDescriptor descriptor) {
        catalog.ensureAvailableEntry(new DeviceCatalogEntry(
            descriptor.deviceId(), "test_" + descriptor.deviceId().replace('-', '_'),
            descriptor.name(), descriptor.type(), descriptor.capabilities(),
            descriptor.protocolAdapter(), descriptor.connectionString(), descriptor.location(),
            DeviceIdentityAssurance.UNRESOLVED, DeviceAdmissionStatus.AVAILABLE,
            DeviceConfigurationStatus.READY_TO_CONFIGURE, DeviceEnablementStatus.DISABLED,
            Instant.now()));
        DeviceObservationCandidate candidate = catalog.observe(new DeviceObservation(
            sourceType, sourceIdentity, descriptor.deviceId(), Map.of("test", true))).orElseThrow();
        catalog.admitCandidate(candidate.candidateId(), descriptor.deviceId(), "owner@example.com", "test admission");
        catalog.setConfiguration(descriptor.deviceId(), DeviceConfigurationStatus.CONFORMING,
            "HUMAN", "owner@example.com", "test conformance");
        return catalog.setEnablement(descriptor.deviceId(), DeviceEnablementStatus.ENABLED,
            "HUMAN", "owner@example.com", "test enablement");
    }
}
