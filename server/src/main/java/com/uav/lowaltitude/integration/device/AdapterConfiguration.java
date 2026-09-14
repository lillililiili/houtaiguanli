package com.uav.lowaltitude.integration.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record AdapterConfiguration(String host, int port, int timeoutMillis, String allowedCidrs,
                                   String credentialRef, JsonNode protocol) {

    public static AdapterConfiguration parse(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            JsonNode connection = root.has("connection") ? root.path("connection") : root;
            String host = text(connection, "host");
            int port = connection.path("port").asInt(0);
            int timeout = Math.max(500, Math.min(connection.path("timeout_millis").asInt(3000), 30_000));
            String credential = text(connection, "credential_ref");
            if (credential == null) credential = text(root, "credential_ref");
            return new AdapterConfiguration(host, port, timeout, text(root, "allowed_cidrs"), credential,
                    root.path("protocol_configuration"));
        } catch (Exception ex) {
            throw new ProtocolException("PROTOCOL_NOT_CONFIGURED", "设备协议配置 JSON 无效");
        }
    }

    public void validateEndpoint() {
        if (host == null || host.isBlank() || port < 1 || port > 65535)
            throw new ProtocolException("PROTOCOL_NOT_CONFIGURED", "设备 TCP 主机和端口未完整配置");
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }
}
