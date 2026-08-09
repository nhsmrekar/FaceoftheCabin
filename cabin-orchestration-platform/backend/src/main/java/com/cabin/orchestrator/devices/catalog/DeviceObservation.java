package com.cabin.orchestrator.devices.catalog;

import java.util.Map;

/** One untrusted discovery or ingress observation before admission. */
public record DeviceObservation(
    String sourceType,
    String sourceIdentity,
    String proposedDeviceId,
    Map<String, Object> metadata
) {}
