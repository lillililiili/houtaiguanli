package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;

/**
 * pingStation3 通过 UDP 推送的 JSON 报文（uAvionix pingStation ICD Rev F）。
 *
 * 航班报文：{"aircraft":[{...}]}，一条一架；状态报文：{"status":{...}}，每 30 秒一条。
 * 字段换算：altitudeMM 毫米→米，headingDE2 百分之一度→度，horVelocityCMS 厘米/秒→米/秒，timeStamp 为 ISO-8601（UTC）。
 */
public record PingStationMessage(String stationGuid, List<FlightCheck.Aircraft> aircraft, boolean status) {

    /** 航班号前三位是航空公司的 ICAO 代码；只收常见的国内航空公司，其余不标航空公司。 */
    static final Map<String, String> AIRLINES = Map.ofEntries(
            Map.entry("CCA", "中国国航"), Map.entry("CES", "东方航空"), Map.entry("CSN", "南方航空"), Map.entry("CHH", "海南航空"),
            Map.entry("CDG", "山东航空"), Map.entry("CXA", "厦门航空"), Map.entry("CSZ", "深圳航空"), Map.entry("CSC", "四川航空"),
            Map.entry("CQH", "春秋航空"), Map.entry("DKH", "吉祥航空"), Map.entry("CUA", "中国联航"), Map.entry("GCR", "天津航空"),
            Map.entry("CBJ", "首都航空"), Map.entry("CGZ", "长龙航空"), Map.entry("CQN", "重庆航空"), Map.entry("CYZ", "邮政航空"),
            Map.entry("CSH", "上海航空"), Map.entry("OKA", "奥凯航空"), Map.entry("CHB", "西部航空"), Map.entry("UEA", "成都航空"),
            Map.entry("HBH", "河北航空"), Map.entry("LKE", "龙江航空"), Map.entry("DLH", "汉莎航空"), Map.entry("KAL", "大韩航空"),
            Map.entry("JAL", "日本航空"), Map.entry("ANA", "全日空"), Map.entry("CPA", "国泰航空"), Map.entry("CKK", "中货航"));

    /** 解析一条报文；不是 pingStation 的格式或没有识别码时返回 null。observedAt 缺失用 now。 */
    public static PingStationMessage parse(JsonNode root, long now) {
        if (root == null || !root.isObject()) return null;
        JsonNode status = root.get("status");
        if (status != null && status.isObject()) {
            String guid = guid(status);
            return guid == null ? null : new PingStationMessage(guid, List.of(), true);
        }
        JsonNode list = root.get("aircraft");
        List<JsonNode> items = new ArrayList<>();
        if (list != null && list.isArray()) list.forEach(items::add);
        else if (root.has("icaoAddress")) items.add(root);
        else return null;
        String guid = null;
        List<FlightCheck.Aircraft> aircraft = new ArrayList<>();
        for (JsonNode item : items) {
            if (guid == null) guid = guid(item);
            FlightCheck.Aircraft a = aircraft(item, now);
            if (a != null) aircraft.add(a);
        }
        return guid == null ? null : new PingStationMessage(guid, aircraft, false);
    }

    private static String guid(JsonNode node) {
        String guid = text(node, "pingStationGuid");
        return guid != null && guid.matches("[0-9A-Fa-f]{16}") ? guid.toLowerCase() : null;
    }

    private static FlightCheck.Aircraft aircraft(JsonNode n, long now) {
        String icao = text(n, "icaoAddress");
        if (icao == null || !icao.matches("[0-9A-Fa-f]{6}") || !n.hasNonNull("latDD") || !n.hasNonNull("lonDD")) return null;
        double lat = n.get("latDD").asDouble(), lon = n.get("lonDD").asDouble();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        String callsign = text(n, "callsign");
        if (callsign != null && callsign.length() > 16) callsign = callsign.substring(0, 16);
        String airline = callsign != null && callsign.length() >= 3 ? AIRLINES.get(callsign.substring(0, 3).toUpperCase()) : null;
        Integer altitude = n.hasNonNull("altitudeMM") ? (int) Math.round(n.get("altitudeMM").asDouble() / 1000.0) : null;
        Double speed = n.hasNonNull("horVelocityCMS") ? n.get("horVelocityCMS").asDouble() / 100.0 : null;
        Double track = n.hasNonNull("headingDE2") ? ((n.get("headingDE2").asDouble() / 100.0) % 360 + 360) % 360 : null;
        return new FlightCheck.Aircraft(icao.toUpperCase(), callsign, airline, lon, lat, altitude, speed, track, observedAt(n, now));
    }

    private static long observedAt(JsonNode n, long now) {
        String stamp = text(n, "timeStamp");
        if (stamp == null) return now;
        try { return Instant.parse(stamp).toEpochMilli(); } catch (DateTimeParseException e) { return now; }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
