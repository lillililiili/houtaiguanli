package com.uav.lowaltitude.modules.device.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceInformationRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.security.AuthUser;
import static com.uav.lowaltitude.integration.device.DeviceProtocolCodes.*;

@Service
public class CommissionDeviceInformationService {
    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final DeviceInformationRepository information;
    private final ProtocolDataRepository protocolData;
    private final DeviceInformationAssembler assembler;
    private final ObjectMapper mapper;
    private final AppClock clock;

    public CommissionDeviceInformationService(DeviceAccessPolicy access, DeviceRepository devices,
            DeviceInformationRepository information, ProtocolDataRepository protocolData,
            DeviceInformationAssembler assembler, ObjectMapper mapper, AppClock clock) {
        this.access = access; this.devices = devices; this.information = information;
        this.protocolData = protocolData; this.assembler = assembler; this.mapper = mapper; this.clock = clock;
    }

    public Information get(String id) {
        return get(id, access.requireCommissionRead());
    }

    public Information getForMonitoring(String id) {
        return get(id, access.requireMonitoringRead());
    }

    private Information get(String id, AuthUser actor) {
        Map<String, Object> device = devices.find(id);
        if (device == null || !information.inScope(id, actor.userId(), actor.scopeMode()))
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在或不在授权范围内");
        long now = clock.nowMillis();
        String protocol = text(device, "protocol_code");
        List<DeviceInformationAssembler.Section> sections = new ArrayList<>(), samples = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        add(sections, "catalog", "设备档案", "平台登记信息", "CATALOG", node(device), null, null, now, "catalog");
        boolean connectionVisible = access.canOperateDevices(actor);
        boolean mqttProtocol = LINGYUN_MQTT_V8_6.equals(protocol) || EO_EDGE_MQTT_20250826.equals(protocol);
        if (!mqttProtocol) {
            add(sections, "connection", "连接配置", "平台接入配置", connectionVisible ? "CATALOG" : "REDACTED",
                    connectionVisible ? node(devices.findProfile(id)) : null, null, null, now, "connection");
            if (RADAR_TCP_V3_0_0.equals(protocol) || COUNTERMEASURE_TCP_4CH_V2_0.equals(protocol))
                add(sections, "protocol_configuration", "协议采集配置", "平台接入配置", connectionVisible ? "CATALOG" : "REDACTED",
                        connectionVisible ? node(devices.findProtocolProfile(id, protocol)) : null, null, null, now,
                        RADAR_TCP_V3_0_0.equals(protocol) ? "radar_configuration" : "relay_configuration");
        } else {
            add(sections, "mqtt_endpoint", "MQTT 连接配置", "平台接入配置", connectionVisible ? "CATALOG" : "REDACTED",
                    connectionVisible ? node(information.mqtt(id, EO_EDGE_MQTT_20250826.equals(protocol))) : null,
                    null, null, now, "mqtt_endpoint");
        }
        add(sections, "link", "连接与数据时效", "平台接收记录", "CATALOG", node(device), null, null, now, "link");
        if (LINGYUN_MQTT_V8_6.equals(protocol)) {
            Map<String, Object> binding = new LinkedHashMap<>(information.mqtt(id, false));
            String type = text(binding, "device_type_abbr");
            String prefix = "bridge/" + text(binding, "provider_code");
            String suffix = "/" + type + "/" + text(binding, "external_device_id");
            binding.put("static_topic", prefix + "/device" + suffix);
            boolean sensing = List.of("radar", "5ga", "aoa", "tdoa", "dcd", "rid").contains(type);
            if (sensing) binding.put("sense_topic", prefix + "/device_data" + suffix);
            if (List.of("dec", "ifr", "bsc").contains(type)) {
                binding.put("control_topic", prefix + "/device_control" + suffix);
                binding.put("control_resp_topic", prefix + "/device_control_resp" + suffix);
            }
            expireLease(binding, now);
            add(sections, "mqtt", "MQTT 接入与接收诊断", "平台 MQTT 接入记录", "CATALOG", node(binding), null, null, now, "mqtt");
            List<String> definitions = new ArrayList<>(List.of("a_common"));
            if (List.of("radar", "aoa", "tdoa", "dcd", "rid").contains(type)) {
                definitions.add("coverage"); definitions.add("range");
                if (!"radar".equals(type)) definitions.add("frequency");
            }
            if (List.of("dec", "ifr", "bsc").contains(type)) {
                definitions.add("counter");
                if (!"bsc".equals(type)) { definitions.add("antenna"); definitions.add(type); }
            }
            add(sections, "work_parameters", "设备上报工参", "协议 A V8.6 · 第四节", "PROTOCOL",
                    json(device.get("metrics_json")), millis(binding, "last_static_pt_time"), millis(binding, "last_static_at"), now,
                    definitions.toArray(String[]::new));
            if (sensing) {
                var sense = information.latestSense("lingyun:" + type + ":" + text(binding, "device_id"),
                        millis(binding, "last_pt_time"), millis(binding, "last_msg_cnt"));
                ObjectNode payload = object(json(sense.get("payload_json")));
                if (payload.path("objects").isArray()) payload.put("object_count", payload.path("objects").size());
                Long received = millis(sense, "received_at");
                add(sections, "sensing", "最近感知报文", "协议 A V8.6 · 第三节", "PROTOCOL", payload,
                        longNode(payload.get("ptTime")), received, now, "sense_header");
                int i = 0;
                for (JsonNode target : payload.path("objects")) {
                    if (i++ >= 20) break;
                    List<String> targetFields = new ArrayList<>(List.of("sense_common"));
                    if (!"aoa".equals(type)) targetFields.add("sense_position");
                    if (List.of("radar", "5ga").contains(type)) targetFields.add("sense_radar");
                    if ("5ga".equals(type)) targetFields.add("sense_5ga");
                    if (List.of("aoa", "tdoa", "dcd").contains(type)) targetFields.add("dcd".equals(type) ? "sense_dcd" : "sense_radio");
                    if ("aoa".equals(type)) targetFields.add("sense_aoa");
                    if ("rid".equals(type)) targetFields.add("sense_rid");
                    add(samples, "sense_" + i, target.path("objectId").asText("目标 " + i), "协议 A · 最近报文目标样本", "PROTOCOL",
                            target, longNode(target.get("time")), received, now, targetFields.toArray(String[]::new));
                }
                if ("aoa".equals(type)) notes.add("AOA 使用方位角；协议明确其目标经纬度和高度无效，因此不展示为有效位置。");
            }
            notes.add("MQTT 工参为设备主动上报；30 秒未收到视为过期。当前支持查看接入信息，未提供 TCP 式调测任务。");
        } else if (EO_EDGE_MQTT_20250826.equals(protocol)) {
            Map<String, Object> binding = new LinkedHashMap<>(information.mqtt(id, true));
            binding.put("reporting_topic", "iot-reporting/cmlc/edge/" + text(binding, "edge_id"));
            binding.put("dispatcher_topic", "iot-dispatcher/cmlc/edge/" + text(binding, "external_device_id"));
            expireLease(binding, now);
            add(sections, "eo_link", "光电边端接入", "平台 MQTT 接入记录", "CATALOG", node(binding), null, null, now, "eo_link");
            JsonNode heartbeat = json(binding.get("heartbeat_json"));
            add(sections, "eo_heartbeat", "光电心跳", "协议 C · 2.2", "PROTOCOL", heartbeat,
                    longNode(heartbeat.get("timestamp")), millis(binding, "last_heartbeat_at"), now, "eo_heartbeat");
            add(sections, "eo_camera", "镜头实时状态", "协议 C · cameraStatus", "PROTOCOL", json(binding.get("camera_status_json")),
                    null, millis(binding, "camera_received_at"), now, "eo_camera");
            notes.add("光电 workState=2 表示自主探测，不是设备异常。心跳应至少每秒一次；镜头状态超过 30 秒显示过期。");
            notes.add("协议 C 对 CameraStatus 支持情况表述不一致，现场支持仍需确认；本页读取已接收心跳和状态回执，不自动发送跟踪或镜头移动指令。");
        } else if (RADAR_TCP_V3_0_0.equals(protocol)) {
            Map<String, Object> runtime = safe(protocolData.runtime(id));
            add(sections, "radar_runtime", "雷达协议链路", "雷达 TCP V3.0.0", "CATALOG", node(runtime), null, null, now, "radar_runtime");
            add(sections, "radar_registers", "雷达工作模式与检测参数", "GETREG · 0x440 / 0x401", "PROTOCOL", json(runtime.get("radar_registers_json")),
                    null, millis(runtime, "radar_registers_at"), now, "radar_registers");
            var rtk = information.latestRtk(id);
            add(sections, "radar_rtk", "RTK 坐标与航向", "UPLOAD_RTK · 已启用 RTK 的设备", "PROTOCOL", node(rtk),
                    null, millis(rtk, "received_at"), now, "radar_rtk");
            var points = information.pointSummary(id);
            ObjectNode pointValues = object(node(points)); pointValues.set("summary", json(points.get("summary_json")));
            add(sections, "radar_point", "最近点迹", "UPLOAD_TARGET_V3", "PROTOCOL", pointValues,
                    millis(points, "observed_at"), millis(points, "received_at"), now, "radar_point");
            int i = 0;
            for (var target : protocolData.targets(id, true, null, null, 0, 20)) {
                add(samples, "track_" + (++i), text(target, "external_track_id"), "UPLOAD_TRACK_V3 · 活动航迹样本", "PROTOCOL",
                        node(target), millis(target, "observed_at"), millis(target, "received_at"), now, "radar_target");
            }
            notes.add("RTK 高程字段在原协议中保留且无效；不作为安装高度或目标海拔。原协议转速 RPM 与角速度栏存在矛盾，保留编码并仅显示 °/s。");
        } else if (COUNTERMEASURE_TCP_4CH_V2_0.equals(protocol)) {
            Map<String, Object> runtime = safe(protocolData.runtime(id));
            ObjectNode relay = object(node(runtime)); relay.set("channels", json(runtime.get("channel_state_json")));
            add(sections, "relay", "四通道继电器反馈", "网络控制器 V2.0 / 四通道继电器协议", "PROTOCOL", relay,
                    null, millis(runtime, "last_query_at"), now, "relay");
            notes.add("通道开关反馈只证明继电器状态；协议未提供实际射频输出功率、驻波、电压、电流或温度的读取字段。");
        } else notes.add("当前设备尚未绑定本次资料对应的采集协议。");
        var receipt = information.latestControlResponse(id);
        if (!receipt.isEmpty()) add(sections, "control_receipt", "最近指令回执（历史）", "设备回执记录", "CATALOG", node(receipt),
                millis(receipt, "occurred_at"), millis(receipt, "received_at"), now, "control_receipt");
        return new Information(id, text(device, "device_no"), text(device, "name"), protocol, text(device, "model"),
                text(device, "source_mode"), Boolean.TRUE.equals(device.get("simulated")), now,
                !LINGYUN_MQTT_V8_6.equals(protocol) && !EO_EDGE_MQTT_20250826.equals(protocol), List.copyOf(sections), List.copyOf(samples), List.copyOf(notes));
    }

    private void add(List<DeviceInformationAssembler.Section> sections, String code, String title, String source, String kind,
            JsonNode values, Long observed, Long received, long now, String... groups) {
        sections.add(assembler.section(code, title, source, kind, values, observed, received, now, groups));
    }
    private JsonNode json(Object raw) {
        if (raw == null) return mapper.createObjectNode();
        try { return mapper.readTree(String.valueOf(raw)); }
        catch (Exception ex) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DEVICE_INFORMATION_INVALID", "已保存的设备信息无法解析，请检查采集记录"); }
    }
    private JsonNode node(Object value) { return mapper.valueToTree(value); }
    private ObjectNode object(JsonNode value) { return value != null && value.isObject() ? ((ObjectNode) value).deepCopy() : mapper.createObjectNode(); }
    private static Map<String, Object> safe(Map<String, Object> value) { return value == null ? Map.of() : value; }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? "" : String.valueOf(value); }
    private static Long millis(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Number n ? n.longValue() : null; }
    private static Long longNode(JsonNode value) { return value != null && value.isIntegralNumber() ? value.longValue() : null; }
    private static void expireLease(Map<String, Object> binding, long now) {
        Long lease = millis(binding, "lease_until");
        if (!Boolean.TRUE.equals(binding.get("broker_enabled")) || lease == null || lease <= now) {
            binding.put("connection_state", "DISCONNECTED"); binding.put("subscribed", false);
        }
    }
    public record Information(String deviceId, String deviceNo, String name, String protocolCode, String model,
            String sourceMode, boolean simulated, long generatedAt, boolean taskSupported,
            List<DeviceInformationAssembler.Section> sections, List<DeviceInformationAssembler.Section> sampleSections, List<String> notes) { }
}
