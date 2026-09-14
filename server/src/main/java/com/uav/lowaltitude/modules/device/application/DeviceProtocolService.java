package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

@Service
public class DeviceProtocolService {

    private final DeviceAccessPolicy access;

    public DeviceProtocolService(DeviceAccessPolicy access) { this.access = access; }

    public List<ProtocolDescriptor> catalog() {
        access.requireDevicesRead();
        return List.of(
                new ProtocolDescriptor(DeviceProtocolCodes.LINGYUN_MQTT_V8_6,
                        "凌云协议 A MQTT（雷达 / 5G-A / TDOA / AOA / 协议破解 / RemoteID）", "8.6",
                        List.of("DEVICE_STATIC_RECEIVE", "SENSE_INBOX_RECEIVE", "LINGYUN_CONTROL"),
                        List.of(new Field("broker_id", "id", true, "选择已登记 MQTT 连接"),
                                new Field("source_mode", "enum", true, "replay 模拟回放 / live 真实来源，待联调"),
                                new Field("emergency_stop", "note", false, "急停：设备协议未提供")),
                        Map.of("transport", "MQTT", "qos", 1, "control_protocol", "B V2.4"), true),
                new ProtocolDescriptor(DeviceProtocolCodes.EO_EDGE_MQTT_20250826, "凌云协议 C 光电边端协同", "20250826",
                        List.of("HEARTBEAT_RECEIVE", "TRACK_BEGIN", "TRACK_END", "CAMERA_STATUS"),
                        List.of(new Field("broker_id", "id", true, "选择已登记 MQTT 连接"),
                                new Field("edge_id", "string", true, "平台作为边缘中心的 edgeId"),
                                new Field("external_device_id", "string", true, "协议中的光电 deviceId"),
                                new Field("unsupported", "note", false, "角度移动 / home 点 / 辅助识别开关：设备协议未提供")),
                        Map.of("transport", "MQTT", "qos", 1, "unsupported", "设备协议未提供"), false),
                new ProtocolDescriptor(DeviceProtocolCodes.RADAR_TCP_V3_0_0, "T02/兼容机扫雷达 TCP", "3.0.0",
                        List.of("LOGIN_DATA", "HEARTBEAT", "TARGET_RECEIVE", "TRACK_RECEIVE", "RTK", "STATUS_READ"),
                        List.of(new Field("login_role", "enum", true, "固定为 DATA"),
                                new Field("recognition_code_ref", "credential_ref", false, "非零识别码的凭据引用"),
                                new Field("rtk_enabled", "boolean", true, "是否采集 RTK"),
                                new Field("coordinate_transform_enabled", "boolean", true, "具备验证参考值后才派生经纬度")),
                        Map.of("host", "192.168.8.168", "port", 5001), false),
                new ProtocolDescriptor(DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, "固定式四通道网络控制器", "2.0",
                        List.of("SAFE_STATUS_QUERY", "WIRE_ENCODING_DETECTION", "FOUR_CHANNEL_NORMALIZATION", "RELAY_SET"),
                        List.of(new Field("device_address", "integer", true, "1–244，禁止广播地址 245"),
                                new Field("wire_encoding", "enum", true, "AUTO/RAW_BYTES/ASCII_HEX_SPACED/ASCII_HEX_COMPACT"),
                                new Field("poll_interval_millis", "integer", true, "1000–60000"),
                                new Field("relay_set", "note", false, "0x11/0x12/0x13 经 REST 与处置通道下发；调测/轮询仍只发 0x10；停止=全关 0x00，不是急停")),
                        Map.of("host", "192.168.0.7", "port", 10006), true));
    }

    public record ProtocolDescriptor(String protocolCode, String name, String version,
                                     List<String> capabilities, List<Field> configurationFields,
                                     Map<String, Object> connectionHints, boolean controlEnabled) { }
    public record Field(String name, String type, boolean required, String description) { }
}
