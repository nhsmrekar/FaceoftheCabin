package com.cabin.orchestrator.devices.catalog;

/** Strength of identity evidence, from a role/name only through approved binding. */
public enum DeviceIdentityAssurance {
    UNRESOLVED,
    PROBABLE,
    CORROBORATED,
    VERIFIED
}
