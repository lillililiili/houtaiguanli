package com.uav.lowaltitude.modules.fusion.application;

/** Device-owned task facts, read again when queued observations are consumed. */
public interface EoFusionTaskContextPort {
    record Context(String targetId, String exclusionReason) {
        public boolean accepted() { return exclusionReason == null && targetId != null; }
    }
    default Context lookupForFusion(String taskId, String sourceId, String deviceId, String sourceMode,
                   String ownerOrgId, String districtId, long observedMillis, long receivedMillis) {
        return lookup(taskId,sourceId,deviceId,sourceMode,ownerOrgId,districtId,observedMillis,receivedMillis);
    }
    Context lookup(String taskId, String sourceId, String deviceId, String sourceMode,
                   String ownerOrgId, String districtId, long observedMillis, long receivedMillis);
}
