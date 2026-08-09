package com.cabin.orchestrator.devices.catalog;

/** Explicit acceptance lifecycle; never inferred from visibility or prior use. */
public enum DeviceAdmissionStatus {
    AVAILABLE,
    ADMITTED,
    REJECTED,
    REVOKED
}
