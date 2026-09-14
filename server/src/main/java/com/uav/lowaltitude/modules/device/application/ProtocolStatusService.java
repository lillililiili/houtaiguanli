package com.uav.lowaltitude.modules.device.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class ProtocolStatusService {

    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final ProtocolDataRepository protocolData;
    private final ObjectMapper mapper;
    private final MqttConfigurationService mqtt;

    public ProtocolStatusService(DeviceAccessPolicy access, DeviceRepository devices,
                                 ProtocolDataRepository protocolData, ObjectMapper mapper, MqttConfigurationService mqtt) {
        this.access = access;
        this.devices = devices;
        this.protocolData = protocolData;
        this.mapper = mapper;
        this.mqtt = mqtt;
    }

    public ProtocolStatus get(String deviceId) {
        access.requireMonitoringRead();
        Map<String, Object> device = devices.find(deviceId);
        if (device == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        String protocolCode = text(device, "protocol_code");
        if (DeviceProtocolCodes.LINGYUN_MQTT_V8_6.equals(protocolCode)
                || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(protocolCode)) {
            Map<String,Object> details=mqtt.status(deviceId);
            String version=DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(protocolCode)?"20250826":"8.6";
            return new ProtocolStatus(deviceId,protocolCode,version,text(device,"source_mode"),
                    text(details,"connection_state"),text(details,"last_error"),details);
        }
        if (protocolCode == null) return new ProtocolStatus(deviceId, null, null, text(device, "source_mode"),
                "NOT_CONFIGURED", "设备未绑定协议来源", Map.of());
        Map<String, Object> runtime = protocolData.runtime(deviceId);
        if (runtime == null) return new ProtocolStatus(deviceId, protocolCode, text(device, "protocol_version"),
                text(device, "source_mode"), "DISCONNECTED",
                bool(device, "source_enabled") ? "协议会话尚未建立" : "live 来源尚未启用", Map.of());
        Map<String, Object> details = new LinkedHashMap<>();
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocolCode)) {
            details.put("login_state", text(runtime, "login_state"));
            details.put("last_valid_frame_at", value(runtime, "last_valid_frame_at"));
            details.put("last_frame_id", text(runtime, "last_frame_id"));
            details.put("crc_error_count", number(runtime, "crc_error_count"));
            details.put("reconnect_count", number(runtime, "reconnect_count"));
            details.put("active_track_count", number(runtime, "active_track_count"));
            details.put("coordinate_reference_state", text(runtime, "coordinate_reference_state"));
            Map<String, Object> reference = protocolData.siteReference(deviceId);
            if (reference != null) details.put("coordinate_reference", Map.of(
                    "sample_count", number(reference, "sample_count"), "verified", bool(reference, "verified"),
                    "latitude_deg", value(reference, "latitude_deg"), "longitude_deg", value(reference, "longitude_deg"),
                    "heading_deg", value(reference, "heading_deg"), "altitude", "UNAVAILABLE_BY_PROTOCOL"));
        } else {
            details.put("detected_wire_encoding", text(runtime, "detected_wire_encoding"));
            details.put("raw_status_word", value(runtime, "raw_status_word"));
            details.put("channels", json(text(runtime, "channel_state_json")));
            details.put("last_query_at", value(runtime, "last_query_at"));
        }
        return new ProtocolStatus(deviceId, protocolCode, text(device, "protocol_version"), text(device, "source_mode"),
                text(runtime, "connection_state"), text(runtime, "blocking_reason"), details);
    }

    private Map<String, Object> json(String value) {
        if (value == null) return Map.of();
        try { return mapper.readValue(value, new TypeReference<>() { }); }
        catch (Exception ex) { return Map.of(); }
    }

    private static Object value(Map<String, Object> row, String key) { return row.get(key); }
    private static String text(Map<String, Object> row, String key) { Object v = row.get(key); return v == null ? null : String.valueOf(v); }
    private static long number(Map<String, Object> row, String key) { Object v = row.get(key); return v instanceof Number n ? n.longValue() : 0; }
    private static boolean bool(Map<String, Object> row, String key) { Object v = row.get(key); return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v)); }

    public record ProtocolStatus(String deviceId, String protocolCode, String protocolVersion, String sourceMode,
                                 String connectionState, String blockingReason, Map<String, Object> details) { }
}
