package com.uav.lowaltitude.modules.evidence.domain;

/** Read access to command evidence, independent of permission to issue a command. */
public record EvidenceCommandVisibility(boolean monitoring, boolean disposal, boolean tracking) {
    public static final EvidenceCommandVisibility ALL = new EvidenceCommandVisibility(true, true, true);
    public boolean any() { return monitoring || disposal || tracking; }
}
