package com.cabin.orchestrator.devices.catalog;

import java.time.Instant;

/** Explicit source identity to platform-device association. */
public record DeviceIdentityBinding(
    String sourceType,
    String sourceIdentity,
    String deviceId,
    String actorId,
    Instant boundAt
) {}
