package com.uav.lowaltitude.modules.device.domain;

/** Public configuration contains credential references only, never resolved passwords. */
public final class MqttConfiguration {
    private MqttConfiguration() { }
    public record Broker(String brokerId, String name, String host, int port, boolean tls, String clientId,
                         String username, String credentialRef, String allowedCidrs, String sourceMode,
                         String ownerOrgId, String districtId, boolean enabled, long version,
                         String connectionState, String lastError) { }
    public record BrokerInput(String name, String host, Integer port, Boolean tls, String username,
                              String credentialRef, String allowedCidrs, String sourceMode,
                              String ownerOrgId, String districtId, Long version) { }
    public record Registration(String protocolCode, String brokerId, String providerCode, String externalDeviceId,
                               String deviceTypeAbbr, String sourceMode, String ownerOrgId, String districtId,
                               String deviceNo, String name, String vendor, String model, Long version, String edgeId) {
        public Registration(String protocolCode, String brokerId, String providerCode, String externalDeviceId,
                            String deviceTypeAbbr, String sourceMode, String ownerOrgId, String districtId,
                            String deviceNo, String name, String vendor, String model, Long version) {
            this(protocolCode, brokerId, providerCode, externalDeviceId, deviceTypeAbbr, sourceMode, ownerOrgId,
                    districtId, deviceNo, name, vendor, model, version, null);
        }
    }
    public record Binding(String opsDeviceId, String deviceId, String opsSourceId, String sourceId,
                          String brokerId, String providerCode, String deviceTypeAbbr, String externalDeviceId,
                          String sourceMode, boolean enabled, Long lastStaticPtTime, Long lastPtTime,
                          Long lastMsgCnt, String ownerOrgId, String districtId) {
        public String topic(boolean sensing) {
            return "bridge/" + providerCode + (sensing ? "/device_data/" : "/device/") + deviceTypeAbbr + "/" + externalDeviceId;
        }
        public String controlTopic() {
            return "bridge/" + providerCode + "/device_control/" + deviceTypeAbbr + "/" + externalDeviceId;
        }
        public String controlRespTopic() {
            return "bridge/" + providerCode + "/device_control_resp/" + deviceTypeAbbr + "/" + externalDeviceId;
        }
        public String source() { return "lingyun:" + deviceTypeAbbr + ":" + deviceId; }
    }
}
