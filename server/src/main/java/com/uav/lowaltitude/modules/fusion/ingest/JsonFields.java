package com.uav.lowaltitude.modules.fusion.ingest;

import com.fasterxml.jackson.databind.JsonNode;

/** 报文取值的共用小工具：类型不符一律当作"没给"，不做隐式转换——把 "12" 当成 12 会让坏数据静默进库。 */
final class JsonFields {
    private JsonFields() { }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.textValue().trim();
        return text.isEmpty() ? null : text;
    }

    static Double number(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.doubleValue();
    }

    static Long integer(JsonNode node, String field) {
        Double value = number(node, field);
        return value == null ? null : value.longValue();
    }

    /** 目标号在各协议里可能是数字或字符串，落库统一成字符串。 */
    static String identifier(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) { String text = value.textValue().trim(); return text.isEmpty() ? null : text; }
        if (value.isNumber()) return value.numberValue().toString();
        return null;
    }
}
