package com.cabin.orchestrator.devices.catalog;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.cabin.orchestrator.devices.catalog.DeviceCatalogTestSupport.authorize;
import static org.junit.jupiter.api.Assertions.*;

class DeviceCatalogServiceTest {

    private final DeviceDescriptor descriptor = new DeviceDescriptor(
        "z2m-motion_entry", "Entry Motion Sensor", DeviceType.MOTION_SENSOR,
        Set.of(DeviceCapability.TELEMETRY, DeviceCapability.PRESENCE),
        "mqtt", "zigbee2mqtt/motion_entry", true, "cabin");

    @Test
    void observationIsAvailableButHasNoOperationalAuthority() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        catalog.ensureAvailableEntry(entryFor(descriptor));

        DeviceObservationCandidate candidate = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "0x00124b0012345678", descriptor.deviceId(),
            Map.of("model", "SNZB-03PR2"))).orElseThrow();

        assertEquals(DeviceCandidateDisposition.AVAILABLE, candidate.disposition());
        assertTrue(catalog.authorizedEntryForObservation(
            "ZIGBEE2MQTT", "0x00124b0012345678").isEmpty());
        assertEquals(DeviceAdmissionStatus.AVAILABLE,
            catalog.entry(descriptor.deviceId()).orElseThrow().admissionStatus());
    }

    @Test
    void authorityRequiresAdmissionThenConformanceThenEnablement() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        catalog.ensureAvailableEntry(entryFor(descriptor));
        DeviceObservationCandidate candidate = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "0x00124b0012345678", descriptor.deviceId(), Map.of())).orElseThrow();

        DeviceCatalogEntry admitted = catalog.admitCandidate(candidate.candidateId(),
            descriptor.deviceId(), "owner@example.com", "recognized installation");
        assertEquals(DeviceAdmissionStatus.ADMITTED, admitted.admissionStatus());
        assertEquals(DeviceEnablementStatus.DISABLED, admitted.enablementStatus());
        assertFalse(admitted.operationallyAuthorized());
        assertThrows(IllegalStateException.class, () -> catalog.setEnablement(
            descriptor.deviceId(), DeviceEnablementStatus.ENABLED,
            "HUMAN", "owner@example.com", "too early"));

        DeviceCatalogEntry conforming = catalog.setConfiguration(descriptor.deviceId(),
            DeviceConfigurationStatus.CONFORMING, "HUMAN", "owner@example.com", "validated");
        assertFalse(conforming.operationallyAuthorized());

        DeviceCatalogEntry enabled = catalog.setEnablement(descriptor.deviceId(),
            DeviceEnablementStatus.ENABLED, "HUMAN", "owner@example.com", "activate");
        assertTrue(enabled.operationallyAuthorized());
        assertTrue(catalog.authorizedEntryForObservation(
            "ZIGBEE2MQTT", "0x00124b0012345678").isPresent());
    }

    @Test
    void rejectedCandidateResurfacesWithoutBecomingAvailableOrAdmitted() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        DeviceObservationCandidate first = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "0x00124b00bad00001", "z2m-neighbor",
            Map.of("friendly_name", "neighbor"))).orElseThrow();
        catalog.rejectCandidate(first.candidateId(), "owner@example.com", "not ours");

        DeviceObservationCandidate resurfaced = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "0x00124b00bad00001", "z2m-neighbor",
            Map.of("friendly_name", "renamed_neighbor"))).orElseThrow();

        assertEquals(first.candidateId(), resurfaced.candidateId());
        assertEquals(2, resurfaced.seenCount());
        assertEquals(DeviceCandidateDisposition.REJECTED, resurfaced.disposition());
        assertEquals("not ours", resurfaced.rejectionReason());
        assertTrue(catalog.authorizedEntryForObservation(
            "ZIGBEE2MQTT", "0x00124b00bad00001").isEmpty());

        DeviceObservationCandidate reconsidered = catalog.reconsiderCandidate(
            first.candidateId(), "owner@example.com", "possible false negative");
        assertEquals(DeviceCandidateDisposition.AVAILABLE, reconsidered.disposition());
        assertTrue(catalog.authorizedEntryForObservation(
            "ZIGBEE2MQTT", "0x00124b00bad00001").isEmpty(),
            "reconsideration must not silently admit");
    }

    @Test
    void rejectingAnAdmittedDeviceRevokesAndDisablesButKeepsItsBinding() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        authorize(catalog, "ZIGBEE2MQTT", "0x00124b0012345678", descriptor);
        String candidateId = catalog.candidateForObservation(
            "ZIGBEE2MQTT", "0x00124b0012345678").orElseThrow().candidateId();

        catalog.rejectCandidate(candidateId, "owner@example.com", "removed from property");

        DeviceCatalogEntry revoked = catalog.entry(descriptor.deviceId()).orElseThrow();
        assertEquals(DeviceAdmissionStatus.REVOKED, revoked.admissionStatus());
        assertEquals(DeviceEnablementStatus.DISABLED, revoked.enablementStatus());
        assertTrue(catalog.authorizedEntryForDeviceId(descriptor.deviceId()).isEmpty());
        assertEquals(DeviceCandidateDisposition.REJECTED,
            catalog.candidateForObservation("ZIGBEE2MQTT", "0x00124b0012345678")
                .orElseThrow().disposition());
    }

    @Test
    void oneCatalogDeviceCannotBeAllocatedToTwoObservedIdentities() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        authorize(catalog, "ZIGBEE2MQTT", "0x00124b0012345678", descriptor);
        DeviceObservationCandidate second = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "0x00124b0099999999", descriptor.deviceId(), Map.of())).orElseThrow();

        assertThrows(IllegalStateException.class, () -> catalog.admitCandidate(
            second.candidateId(), descriptor.deviceId(), "owner@example.com", "collision"));
    }

    @Test
    void friendlyNameFallbackCannotBecomeAVerifiedZigbeeBinding() {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        catalog.ensureAvailableEntry(entryFor(descriptor));
        DeviceObservationCandidate weakIdentity = catalog.observe(new DeviceObservation(
            "ZIGBEE2MQTT", "unverified-friendly:motion_entry", descriptor.deviceId(),
            Map.of("friendly_name", "motion_entry"))).orElseThrow();

        assertThrows(IllegalStateException.class, () -> catalog.admitCandidate(
            weakIdentity.candidateId(), descriptor.deviceId(), "owner@example.com",
            "friendly name alone is not immutable identity"));
        assertEquals(DeviceCandidateDisposition.AVAILABLE,
            catalog.candidate(weakIdentity.candidateId()).orElseThrow().disposition());
        assertEquals(DeviceAdmissionStatus.AVAILABLE,
            catalog.entry(descriptor.deviceId()).orElseThrow().admissionStatus());
    }

    private DeviceCatalogEntry entryFor(DeviceDescriptor value) {
        return new DeviceCatalogEntry(value.deviceId(), "zigbee_motion_entry", value.name(),
            value.type(), value.capabilities(), value.protocolAdapter(), value.connectionString(),
            value.location(), DeviceIdentityAssurance.PROBABLE, DeviceAdmissionStatus.AVAILABLE,
            DeviceConfigurationStatus.READY_TO_CONFIGURE, DeviceEnablementStatus.DISABLED,
            java.time.Instant.now());
    }
}
