package com.cabin.orchestrator.devices.catalog;

/** Ontology-derived configuration conformance, independent of enablement. */
public enum DeviceConfigurationStatus {
    READY_TO_CONFIGURE,
    CONFIGURING,
    CONFORMING,
    NONCONFORMING,
    WITHDRAWN,
    REGRESSED
}
