package com.uav.lowaltitude.modules.device.domain;

/** Protocol C edge-center identities. Public records never include broker passwords. */
public final class EoEdgeConfiguration {
    private EoEdgeConfiguration() { }

    public record Binding(String opsDeviceId, String deviceId, String opsSourceId, String sourceId,
                          String edgeId, String brokerId, String externalDeviceId, String sourceMode,
                          boolean enabled, Long lastHeartbeatAt, Integer workState,
                          String ownerOrgId, String districtId) {
        public String source() { return "eo-edge:" + edgeId; }
        public String reportingTopic() { return "iot-reporting/cmlc/edge/" + edgeId; }
        public String dispatcherTopic() { return "iot-dispatcher/cmlc/edge/" + externalDeviceId; }
    }
}
