package com.cabin.orchestrator.devices.catalog;

import java.time.Instant;
import java.util.Map;

/** Retained observation; rejection metadata survives every resurfacing. */
public record DeviceObservationCandidate(
    String candidateId,
    String sourceType,
    String sourceIdentity,
    String proposedDeviceId,
    Map<String, Object> metadata,
    DeviceCandidateDisposition disposition,
    Instant firstSeen,
    Instant lastSeen,
    long seenCount,
    String rejectionReason,
    String rejectedBy,
    Instant rejectedAt
) {
    public DeviceObservationCandidate {
        metadata = Map.copyOf(metadata);
    }
}
