package com.cabin.orchestrator.devices.model;

import java.time.Instant;

/**
 * Receipt for a user-requested liveness check.
 *
 * <p>The outcome reports what the adapter could actually establish. It must
 * not turn a timeout into a claim that the device is offline, and it must not
 * imply that a sleeping battery device can be remotely forced awake.</p>
 */
public record DeviceLivenessCheckResult(
    String deviceId,
    String action,
    Outcome outcome,
    CheckinStatus previousStatus,
    CheckinStatus checkinStatus,
    Instant checkedAt,
    String message,
    String receiptId
) {
    public DeviceLivenessCheckResult(
        String deviceId,
        String action,
        Outcome outcome,
        CheckinStatus previousStatus,
        CheckinStatus checkinStatus,
        Instant checkedAt,
        String message
    ) {
        this(deviceId, action, outcome, previousStatus, checkinStatus,
            checkedAt, message, null);
    }

    public DeviceLivenessCheckResult withReceiptId(String receiptId) {
        return new DeviceLivenessCheckResult(deviceId, action, outcome,
            previousStatus, checkinStatus, checkedAt, message, receiptId);
    }

    public enum Outcome {
        REACHABLE,
        REPORTED_OFFLINE,
        NO_REPLY,
        UNSUPPORTED
    }
}
