package com.cabin.orchestrator.devices.catalog;

import java.time.Instant;

/** Append-only decision history for admission, configuration, and enablement. */
public record DeviceLifecycleDecision(
    String decisionId,
    String candidateId,
    String deviceId,
    String action,
    String actorType,
    String actorId,
    String reason,
    Instant decidedAt
) {}
