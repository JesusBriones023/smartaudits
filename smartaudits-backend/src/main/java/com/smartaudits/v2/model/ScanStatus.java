package com.smartaudits.v2.model;

/** ADR-0001 Scan states. PENDING and RUNNING are the only non-terminal (active) states. */
public enum ScanStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
