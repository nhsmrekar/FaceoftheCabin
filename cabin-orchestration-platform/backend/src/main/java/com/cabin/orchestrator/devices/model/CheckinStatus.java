package com.cabin.orchestrator.devices.model;

/**
 * Whether a device has been heard from lately — distinct from {@link DeviceStatus#state()}.
 * "Offline" conflates two very different situations: a device that's actually
 * unreachable, and one that simply hasn't reported since the last poll window.
 * This is the UI-facing axis that keeps those apart; see DeviceHealthMonitor.
 */
public enum CheckinStatus {
    /** Reported within its expected interval. */
    ON_SCHEDULE,
    /** Past its expected interval but not yet confirmed unreachable — grace tier. */
    LATE,
    /** Past the grace tier, and (where an active check is possible) a live probe failed too. */
    MISSED
}
