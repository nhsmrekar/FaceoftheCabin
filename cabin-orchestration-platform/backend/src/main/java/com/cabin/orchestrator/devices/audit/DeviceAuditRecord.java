package com.cabin.orchestrator.devices.audit;

/** Append-only evidence for one authenticated device action. */
public record DeviceAuditRecord(
    String id,
    String deviceId,
    String actionType,
    String actorEmail,
    String outcome,
    String detail,
    long createdAt
) {}
