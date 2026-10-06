package com.uav.lowaltitude.integration;

import java.util.List;
import java.util.Iterator;
import java.net.URI;
import java.net.HttpURLConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** Logical commissioning route for devices created by the map/device simulator. */
@Profile(SimulationPolicy.PROFILE)
@Component
public class ReplaySimulatorAdapter implements DeviceAdapterPort {

    private static final int DEFAULT_PORT = 8766;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String protocolCode() { return DeviceProtocolCodes.SIMULATOR_REPLAY; }

    @Override
    public SourceMode mode() { return SourceMode.replay; }

    @Override
    public AdapterResult reboot(RebootWork work) {
        return new AdapterResult(false, "DEVICE_NOT_OPERABLE", "设备模拟器仅支持逻辑调测，不执行重启");
    }

    @Override
    public AdapterResult connect(CommissionWork work) {
        Probe probe = probe(work);
        if (!probe.ready()) return new AdapterResult(false, probe.code(), probe.detail());
        return new AdapterResult(true, "SIMULATOR_CONNECTED",
                "已读取本地模拟器运行态：场景运行中、MQTT 已连接并持续发布；不代表现场设备连通");
    }

    @Override
    public CommissionResult commission(CommissionWork work) {
        Probe probe = probe(work);
        if (!probe.ready()) {
            return new CommissionResult(false, probe.code(), probe.detail(), List.of(
                    new CommissionItem("SIMULATOR_RUNTIME", "模拟器运行态", "UNTESTABLE", probe.detail(), null,
                            "GET /api/status")));
        }
        return new CommissionResult(true, "SIMULATOR_COMMISSION_PASSED",
                "已读取本地模拟器运行态和 MQTT 发布事实；结果不代表现场协议验收",
                List.of(
                        new CommissionItem("SIMULATION", "模拟器场景", "PASSED", "RUNNING", null, "GET /api/status"),
                        new CommissionItem("MQTT", "MQTT 发布链路", "PASSED", "connected", null, "GET /api/status"),
                        new CommissionItem("IDENTITY", "设备身份", "PASSED", "设备编号与模拟器绑定一致", null,
                                "manifest.devices.external_id"),
                        new CommissionItem("PUBLISH", "最近发布", "PASSED", "已收到模拟器发布时间", null,
                                "last_published_at")));
    }

    private Probe probe(CommissionWork work) {
        Endpoint endpoint;
        try {
            endpoint = endpoint(work.configurationJson());
            HttpURLConnection connection = (HttpURLConnection) endpoint.uri().toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(800);
            connection.setReadTimeout(1500);
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return Probe.notReady("SIMULATOR_UNAVAILABLE", "本地模拟器返回 HTTP " + responseCode);
            }
            JsonNode status;
            try (var reader = new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                status = json.readTree(reader);
            } finally {
                connection.disconnect();
            }
            String phase = status.path("phase").asText("");
            boolean mqtt = status.path("mqtt_connected").asBoolean(false);
            boolean published = status.path("last_published_at").isNumber()
                    && status.path("last_published_at").asLong() > 0;
            boolean registered = registered(status.path("manifest").path("devices"), work.deviceNo());
            if (!"RUNNING".equals(phase)) {
                return Probe.notReady("SIMULATOR_NOT_RUNNING", "本地模拟器当前未运行场景（" + (phase.isBlank() ? "未知" : phase)
                        + "），请先启动场景并保持 MQTT 发布");
            }
            if (!mqtt) return Probe.notReady("SIMULATOR_MQTT_OFFLINE", "本地模拟器场景运行中，但 MQTT 未连接");
            if (!registered) return Probe.notReady("SIMULATOR_DEVICE_NOT_REGISTERED", "模拟器当前批次未登记设备 " + work.deviceNo());
            if (!published) return Probe.notReady("SIMULATOR_NO_PUBLISH", "模拟器尚未产生可回读的 MQTT 发布事实");
            return new Probe(true, "SIMULATOR_READY", "ready", endpoint);
        } catch (Exception ex) {
            return Probe.notReady("SIMULATOR_UNAVAILABLE", "无法读取本地模拟器运行态，请检查 8766 服务：" + ex.getMessage());
        }
    }

    private Endpoint endpoint(String configurationJson) throws Exception {
        JsonNode root = configurationJson == null || configurationJson.isBlank()
                ? json.createObjectNode() : json.readTree(configurationJson);
        JsonNode connection = root.path("connection");
        String host = connection.path("host").asText("simulator");
        int port = connection.path("port").asInt(DEFAULT_PORT);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("模拟器端口无效");
        if ("simulator".equalsIgnoreCase(host)) host = "127.0.0.1";
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))) {
            throw new IllegalArgumentException("模拟调测仅允许访问本机模拟器");
        }
        return new Endpoint(URI.create("http://" + host + ":" + port + "/api/status"));
    }

    private static boolean registered(JsonNode devices, String deviceNo) {
        if (deviceNo == null || !devices.isObject()) return false;
        Iterator<JsonNode> values = devices.elements();
        while (values.hasNext()) {
            if (deviceNo.equals(values.next().path("external_id").asText(null))) return true;
        }
        return false;
    }

    private record Endpoint(URI uri) { }
    private record Probe(boolean ready, String code, String detail, Endpoint endpoint) {
        static Probe notReady(String code, String detail) { return new Probe(false, code, detail, null); }
    }
}
