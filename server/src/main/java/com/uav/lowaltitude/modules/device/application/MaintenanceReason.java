package com.uav.lowaltitude.modules.device.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow;

/**
 * 运维待办的“异常说明”（CDX-P09，2026-10-08）：先写设备现在真正的问题，再写这台设备还没关的旧异常记录。
 *
 * <p>系统只为心跳超时生成异常记录，设备自报故障不生成；心跳超时记录又要人工核验才关。只抄未关记录时，
 * 雷达报了故障，待办里写的却是上一轮停报留下的“心跳超时”。所以当前问题从设备状态写出来，
 * 设备已恢复上报的心跳超时记录只注一句待核验，不再当成当前原因。</p>
 */
final class MaintenanceReason {
    static final String HEARTBEAT_RECOVERED = "此前心跳超时，已恢复上报，待核验";
    static final String UNEXPLAINED = "设备自动检查发现异常，尚无未关闭告警说明，请运维核查。";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("M月d日 HH:mm:ss");

    private MaintenanceReason() { }

    /** row 是生成待办时刚做的设备检查；hasAlarm 是设备当前是否自报告警。 */
    static String text(DeviceRow row, boolean hasAlarm, long now) {
        List<String> parts = new ArrayList<>();
        String connectivity = row.connectivity(), health = row.healthCode();
        // 只有确实还在上报的设备，才能说旧的心跳超时“已恢复上报”；状态未知时照抄原记录。
        boolean reporting = Set.of("ONLINE", "ABNORMAL", "DEGRADED").contains(connectivity);
        if ("DISABLED".equals(connectivity)) {
            parts.add("设备已停用");
        } else if ("OFFLINE".equals(connectivity)) {
            Long last = latest(row.lastHeartbeatAt(), row.observedAt());
            parts.add(last == null ? "设备离线：尚未收到有效上报" : "设备离线：最近一次有效上报 " + time(last, now));
        } else {
            String at = row.observedAt() == null ? "" : "，状态上报时间 " + time(row.observedAt(), now);
            if ("BAD".equals(health)) parts.add("设备自报故障（工作状态：故障）" + at);
            else if ("DEGRADED".equals(health) || "DEGRADED".equals(connectivity)) parts.add("设备自报运行不稳定" + at);
            else if ("ABNORMAL".equals(connectivity)) parts.add("设备状态异常" + at);
            if (hasAlarm) parts.add("设备上报告警" + at);
        }
        for (var incident : row.incidents()) {
            if (incident.closedAt() != null) continue;
            String text = reporting && "MQTT_HEARTBEAT_TIMEOUT".equals(incident.incidentType())
                    ? HEARTBEAT_RECOVERED : incident.reason();
            if (text != null && !text.isBlank() && !parts.contains(text)) parts.add(text);
        }
        return parts.isEmpty() ? UNEXPLAINED : String.join("；", parts);
    }

    private static Long latest(Long a, Long b) {
        if (a == null) return b;
        return b == null ? a : Math.max(a, b);
    }

    /** 当天只写时分秒，别的日子带上日期。 */
    private static String time(long at, long now) {
        var moment = Instant.ofEpochMilli(at).atZone(ZONE);
        boolean today = moment.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate());
        return (today ? CLOCK : DAY_CLOCK).format(moment);
    }
}
