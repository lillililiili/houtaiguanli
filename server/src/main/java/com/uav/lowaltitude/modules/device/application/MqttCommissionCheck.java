package com.uav.lowaltitude.modules.device.application;

import static com.uav.lowaltitude.integration.device.DeviceProtocolCodes.EO_EDGE_MQTT_20250826;
import static com.uav.lowaltitude.integration.device.DeviceProtocolCodes.LINGYUN_MQTT_V8_6;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.DeviceAdapterPort.AdapterResult;
import com.uav.lowaltitude.integration.DeviceAdapterPort.CommissionItem;
import com.uav.lowaltitude.integration.DeviceAdapterPort.CommissionResult;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceInformationRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * MQTT 设备的接入调测：协议 A（凌云 V8.6：雷达、5G-A、TDOA、反制、气象等）与协议 C（光电边端）。
 * 这些设备主动向 MQTT 服务器上报，平台只订阅：建立连接核对平台 MQTT 会话和设备主题订阅，
 * 协议调测按平台实际接收的工参/心跳、感知报文和拒收记录判定。全程不向设备下发控制、跟踪或镜头指令，
 * 因而不会触发反制发射或光电转动。设备模拟器（replay）设备走同一条平台接收链路，结果仍按模拟标记。
 */
@Component
public class MqttCommissionCheck {
    /** 协议 A 工参 30 秒未收到即判离线（MqttRepository.expire）；感知报文沿用同一时效。 */
    public static final long REPORT_FRESH_MILLIS = 30_000;
    /** 拒收与冲突报文的回看窗口。 */
    public static final long RECEIVE_WINDOW_MILLIS = 300_000;
    public static final String WARNING = "MQTT 接入链路调测结果：平台只核对已接收的上报，未向设备下发控制或跟踪指令";
    private static final Set<String> COUNTER_TYPES = Set.of("dec", "ifr", "bsc", "countermeasure");
    /** 设备当时已停用、光电跟踪任务已结束或协议外事件：记录但不判调测失败。 */
    private static final Set<String> NON_BLOCKING_REASONS = Set.of("DEVICE_DISABLED", "TRACK_NOT_OPEN",
            "TRACK_REPORT_TIME_INVALID", "PROTOCOL_EVENT_UNSUPPORTED");
    private static final Map<String, String> REASON_LABELS = Map.ofEntries(
            Map.entry("QOS1_REQUIRED", "未使用 QoS 1"), Map.entry("RETAINED_NOT_REALTIME", "保留消息不作实时数据"),
            Map.entry("IDENTITY_MISMATCH", "报文身份与主题不一致"), Map.entry("INVALID_JSON_UTF8", "不是 UTF-8 JSON"),
            Map.entry("INVALID_ENVELOPE", "报文结构不符合协议"), Map.entry("OBSERVATION_TIME_IN_FUTURE", "上报时间超前"),
            Map.entry("KEY_PAYLOAD_CONFLICT", "同一消息编号内容不同"), Map.entry("SOURCE_MODE_MISMATCH", "来源模式与连接不一致"),
            Map.entry("PAYLOAD_TOO_LARGE", "报文超过 1 MiB"), Map.entry("UNKNOWN_EVENT", "未知事件"),
            Map.entry("DEVICE_DISABLED", "设备已停用"), Map.entry("TRACK_NOT_OPEN", "无进行中的跟踪任务"),
            Map.entry("TRACK_REPORT_TIME_INVALID", "跟踪上报时间无效"),
            Map.entry("PROTOCOL_EVENT_UNSUPPORTED", "平台未接入的协议事件"),
            Map.entry("HEARTBEAT_FAILED", "心跳报告设备异常（codeStatus 非 200）"));
    private static final Map<String, String> LINGYUN_WORK_STATES = Map.of("0", "未工作", "1", "工作中", "2", "设备异常");
    private static final Map<String, String> EO_WORK_STATES = Map.of("0", "空闲", "1", "工作", "2", "自主探测");

    private final DeviceInformationRepository information;
    private final AppClock clock;
    private final long eoHeartbeatTimeoutMillis;

    public MqttCommissionCheck(DeviceInformationRepository information, AppClock clock,
            @Value("${app.eo-edge.heartbeat-timeout-millis:3000}") long eoHeartbeatTimeoutMillis) {
        this.information = information;
        this.clock = clock;
        this.eoHeartbeatTimeoutMillis = eoHeartbeatTimeoutMillis;
    }

    public static boolean supports(String protocolCode) {
        return LINGYUN_MQTT_V8_6.equals(protocolCode) || EO_EDGE_MQTT_20250826.equals(protocolCode);
    }

    /** 平台侧 MQTT 连接快照：只含连接名称、服务器地址、端口和 TLS，不含用户名、凭据引用和客户端 ID。 */
    public Map<String, Object> endpoint(String deviceId, String protocolCode) {
        Map<String, Object> binding = binding(deviceId, protocolCode);
        if (binding.isEmpty()) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transport", "MQTT");
        snapshot.put("broker_name", binding.get("broker_name"));
        snapshot.put("host", binding.get("host"));
        snapshot.put("port", binding.get("port"));
        snapshot.put("tls", binding.get("tls"));
        return snapshot;
    }

    /** 建立连接：平台与 MQTT 服务器的会话有效，且设备主题已收到订阅确认。 */
    public AdapterResult connect(String deviceId, String protocolCode) {
        Map<String, Object> binding = binding(deviceId, protocolCode);
        CommissionItem session = session(binding, clock.nowMillis());
        if (!"PASSED".equals(session.result())) return new AdapterResult(false, "MQTT_SESSION_UNAVAILABLE", session.value());
        CommissionItem subscription = subscription(binding, protocolCode);
        if (!"PASSED".equals(subscription.result()))
            return new AdapterResult(false, "MQTT_SUBSCRIPTION_PENDING", subscription.value());
        return new AdapterResult(true, "MQTT_CONNECTED", "平台 MQTT 会话已连接（" + endpointText(binding)
                + "），设备主题已订阅；设备主动上报，平台不与设备直连");
    }

    /** 协议调测：逐项判定后，任一项失败为 FAILED；只缺业务数据为 UNTESTABLE；全部通过为 PASSED。 */
    public CommissionResult run(String deviceId, String protocolCode) {
        boolean eo = EO_EDGE_MQTT_20250826.equals(protocolCode);
        Map<String, Object> binding = binding(deviceId, protocolCode);
        long now = clock.nowMillis();
        List<CommissionItem> items = new ArrayList<>();
        items.add(session(binding, now));
        items.add(subscription(binding, protocolCode));
        if (eo) eoReports(binding, now, items);
        else lingyunReports(deviceId, binding, now, items);
        items.add(receiveQuality(deviceId, binding, eo, now));
        CommissionItem failed = first(items, "FAILED");
        if (failed != null)
            return new CommissionResult(false, "MQTT_" + failed.code() + "_FAILED", failed.label() + "：" + failed.value(), items);
        CommissionItem untestable = first(items, "UNTESTABLE");
        if (untestable != null)
            return new CommissionResult(false, "MQTT_DATA_UNTESTABLE", "MQTT 会话、订阅和" + (eo ? "心跳" : "工参")
                    + "上报正常；" + untestable.label() + "：" + untestable.value(), items);
        return new CommissionResult(true, "MQTT_COMMISSION_PASSED", "MQTT 接入链路调测通过：会话已连接、主题已订阅、"
                + (eo ? "心跳" : "上报") + "在时效内，近期无拒收报文", items);
    }

    /** 写入判据快照的阈值，便于报告复核。 */
    public Map<String, Object> thresholds(String protocolCode) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (EO_EDGE_MQTT_20250826.equals(protocolCode)) values.put("heartbeat_fresh_millis", eoHeartbeatTimeoutMillis);
        else {
            values.put("work_parameter_fresh_millis", REPORT_FRESH_MILLIS);
            values.put("sense_fresh_millis", REPORT_FRESH_MILLIS);
        }
        values.put("receive_window_millis", RECEIVE_WINDOW_MILLIS);
        values.put("commands_sent", false);
        return values;
    }

    private Map<String, Object> binding(String deviceId, String protocolCode) {
        if (!supports(protocolCode)) return Map.of();
        Map<String, Object> binding = information.mqtt(deviceId, EO_EDGE_MQTT_20250826.equals(protocolCode));
        return binding == null ? Map.of() : binding;
    }

    private static CommissionItem session(Map<String, Object> b, long now) {
        String label = "平台 MQTT 会话";
        if (b.isEmpty()) return item("SESSION", label, "FAILED", "设备未绑定 MQTT 接入身份", "mqtt_device_binding");
        String endpoint = endpointText(b);
        if (!bool(b, "broker_enabled"))
            return item("SESSION", label, "FAILED", "MQTT 连接未启用（" + endpoint + "），请在 MQTT 连接管理中启用", "mqtt_broker.enabled");
        Long lease = number(b, "lease_until");
        String state = text(b, "connection_state");
        if (!"CONNECTED".equals(state) || lease == null || lease <= now) {
            String reason = "CONNECTED".equals(state) ? "会话租约已过期" : "状态 " + (state == null ? "未知" : state);
            String error = text(b, "last_error");
            return item("SESSION", label, "FAILED", "平台未与 MQTT 服务器建立会话（" + endpoint + "，" + reason + "）"
                    + (error == null || error.isBlank() ? "" : "；最近连接错误见 MQTT 连接管理"), "mqtt_session_lease");
        }
        return item("SESSION", label, "PASSED", "已连接 " + endpoint, "mqtt_session_lease");
    }

    private static CommissionItem subscription(Map<String, Object> b, String protocolCode) {
        String label = "设备主题订阅";
        if (b.isEmpty()) return item("SUBSCRIPTION", label, "FAILED", "设备未绑定 MQTT 接入身份", "mqtt_device_binding");
        if (!bool(b, "subscribed"))
            return item("SUBSCRIPTION", label, "FAILED", "平台尚未收到设备主题的订阅确认（SUBACK）", "subscribed");
        String topic = EO_EDGE_MQTT_20250826.equals(protocolCode)
                ? "iot-reporting/cmlc/edge/" + text(b, "edge_id")
                : "bridge/" + text(b, "provider_code") + "/device/" + text(b, "device_type_abbr") + "/" + text(b, "external_device_id");
        return item("SUBSCRIPTION", label, "PASSED", "已订阅 " + topic, "subscribed");
    }

    private void lingyunReports(String deviceId, Map<String, Object> b, long now, List<CommissionItem> items) {
        if (b.isEmpty()) return;
        String type = text(b, "device_type_abbr");
        Long lastStatic = number(b, "last_static_at");
        if (lastStatic == null) {
            items.add(item("WORK_PARAMETERS", "设备工参上报", "FAILED", "平台尚未收到该设备的有效工参", "last_static_at"));
        } else if (now - lastStatic > REPORT_FRESH_MILLIS) {
            items.add(item("WORK_PARAMETERS", "设备工参上报", "FAILED", "最近一次有效工参在 " + ago(now - lastStatic)
                    + "，超过 30 秒时效", "last_static_at"));
        } else {
            items.add(item("WORK_PARAMETERS", "设备工参上报", "PASSED", ago(now - lastStatic) + "收到有效工参", "last_static_at"));
            String state = information.reportedWorkState(deviceId);
            String text = "workState=" + state + "（" + LINGYUN_WORK_STATES.getOrDefault(state, "未定义") + "）";
            if (state == null) items.add(item("WORK_STATE", "设备自报工作状态", "UNTESTABLE", "工参中未取得 workState", "a_common.workState"));
            else if ("2".equals(state)) items.add(item("WORK_STATE", "设备自报工作状态", "FAILED", text, "a_common.workState"));
            else if ("0".equals(state) && !COUNTER_TYPES.contains(type))
                items.add(item("WORK_STATE", "设备自报工作状态", "UNTESTABLE", text + "，设备未工作时业务数据无法判定", "a_common.workState"));
            else items.add(item("WORK_STATE", "设备自报工作状态", "PASSED", text, "a_common.workState"));
        }
        if (!LingyunEnvelope.SENSING_TYPES.containsKey(type)) return;
        Long lastSense = number(b, "last_sense_at");
        if (lastSense != null && now - lastSense <= REPORT_FRESH_MILLIS) {
            items.add(item("SENSE", "感知报文上报", "PASSED", ago(now - lastSense) + "收到感知报文（msgCnt="
                    + text(b, "last_msg_cnt") + "）", "last_sense_at"));
        } else {
            items.add(item("SENSE", "感知报文上报", "UNTESTABLE", (lastSense == null ? "尚未收到感知报文"
                    : "最近一次感知报文在 " + ago(now - lastSense)) + "；设备待机或探测范围内无目标时不上报，感知链路不可判定",
                    "last_sense_at"));
        }
    }

    private void eoReports(Map<String, Object> b, long now, List<CommissionItem> items) {
        if (b.isEmpty()) return;
        Long heartbeat = number(b, "last_heartbeat_at");
        if (heartbeat == null) {
            items.add(item("HEARTBEAT", "光电心跳", "FAILED", "平台尚未收到该设备的 HeartBeat", "last_heartbeat_at"));
            return;
        }
        if (now - heartbeat > eoHeartbeatTimeoutMillis) {
            items.add(item("HEARTBEAT", "光电心跳", "FAILED", "最近一次 HeartBeat 在 " + ago(now - heartbeat) + "，超过 "
                    + eoHeartbeatTimeoutMillis + " 毫秒心跳时效", "last_heartbeat_at"));
            return;
        }
        items.add(item("HEARTBEAT", "光电心跳", "PASSED", ago(now - heartbeat) + "收到 HeartBeat", "last_heartbeat_at"));
        String state = text(b, "work_state");
        if (state != null) items.add(item("WORK_STATE", "光电工作状态", "PASSED", "workState=" + state + "（"
                + EO_WORK_STATES.getOrDefault(state, "未定义") + "）", "eo_heartbeat.workState"));
    }

    private CommissionItem receiveQuality(String deviceId, Map<String, Object> b, boolean eo, long now) {
        String label = "报文接收校验";
        List<String> blocking = new ArrayList<>(), noted = new ArrayList<>();
        long blockingCount = 0;
        for (Map<String, Object> row : information.receiveProblems(deviceId, now - RECEIVE_WINDOW_MILLIS)) {
            String reason = text(row, "reason");
            Long count = number(row, "message_count");
            String entry = reasonLabel(reason) + "（" + reason + "）×" + count;
            if (NON_BLOCKING_REASONS.contains(reason)) noted.add(entry);
            else { blocking.add(entry); blockingCount += count == null ? 0 : count; }
        }
        if (!blocking.isEmpty())
            return item("RECEIVE", label, "FAILED", "近 5 分钟有 " + blockingCount + " 条报文被拒收或与已收报文冲突："
                    + String.join("、", blocking), "mqtt_receive_diagnostic");
        String totals = b.isEmpty() ? "" : eo ? "；累计重复 " + text(b, "duplicate_count") + " 条"
                : "；累计疑似缺报 " + text(b, "suspected_gap_count") + " 次、重复 " + text(b, "duplicate_count") + " 条";
        return item("RECEIVE", label, "PASSED", "近 5 分钟无拒收或冲突报文" + totals
                + (noted.isEmpty() ? "" : "；另有不影响判定的记录：" + String.join("、", noted)), "mqtt_receive_diagnostic");
    }

    private static String reasonLabel(String reason) {
        if (reason == null) return "其他";
        if (REASON_LABELS.containsKey(reason)) return REASON_LABELS.get(reason);
        return reason.startsWith("INVALID_") ? "字段取值不符合协议" : "其他";
    }

    private static CommissionItem first(List<CommissionItem> items, String result) {
        return items.stream().filter(item -> result.equals(item.result())).findFirst().orElse(null);
    }

    private static CommissionItem item(String code, String label, String result, String value, String basis) {
        return new CommissionItem(code, label, result, value, null, basis);
    }

    private static String endpointText(Map<String, Object> b) {
        String name = text(b, "broker_name");
        return (name == null ? "" : name + " ") + text(b, "host") + ":" + text(b, "port") + (bool(b, "tls") ? "，TLS" : "，未加密");
    }

    private static String ago(long millis) {
        long value = Math.max(0, millis);
        return value < 10_000 ? String.format(Locale.ROOT, "%.1f 秒前", value / 1000.0) : value / 1000 + " 秒前";
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
