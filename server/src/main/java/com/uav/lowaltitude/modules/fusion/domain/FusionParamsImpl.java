package com.uav.lowaltitude.modules.fusion.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;

/**
 * fusion_config.params 的只读视图。缺键、类型不符抛 IllegalStateException：参数目录随版本发布，
 * 缺项意味着部署/迁移错误，不是业务"未知"，不能用代码默认值把它掩盖成一个看似正常的融合结果。
 */
public final class FusionParamsImpl implements FusionParams {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String configVersion;
    private final JsonNode root;

    private FusionParamsImpl(String configVersion, JsonNode root) {
        this.configVersion = configVersion;
        this.root = root;
    }

    public static FusionParamsImpl fromJson(String configVersion, String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null ? "" : json);
            // H2 把 CAST(? AS JSON) 的字符串存成 JSON 文本，读回是带引号的字符串；PostgreSQL 直接是对象。两种形态都接受。
            if (node != null && node.isTextual()) node = MAPPER.readTree(node.textValue());
            if (node == null || !node.isObject()) throw new IllegalStateException("融合参数不是 JSON 对象（配置版本 " + configVersion + "）");
            return new FusionParamsImpl(configVersion, node);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("融合参数无法解析（配置版本 " + configVersion + "）", ex);
        }
    }

    @Override public String configVersion() { return configVersion; }

    @Override public double number(String group, String key) {
        JsonNode node = require(group, key);
        if (!node.isNumber()) throw new IllegalStateException("融合参数不是数值: " + group + "." + key + "（配置版本 " + configVersion + "）");
        return node.doubleValue();
    }

    @Override public int integer(String group, String key) {
        JsonNode node = require(group, key);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalStateException("融合参数不是整数: " + group + "." + key + "（配置版本 " + configVersion + "）");
        return node.intValue();
    }

    @Override public Map<String, Double> accuracyDefaults() {
        JsonNode node = require("filter", "accuracy_default_m");
        Map<String, Double> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isNumber()) throw new IllegalStateException("filter.accuracy_default_m." + e.getKey() + " 不是数值");
            out.put(e.getKey(), e.getValue().doubleValue());
        });
        return Map.copyOf(out);
    }

    @Override public Map<String, Map<String, Double>> weights() {
        JsonNode node = root.get("weights");
        if (node == null || !node.isObject()) throw new IllegalStateException("融合参数缺失: weights（配置版本 " + configVersion + "）");
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(source -> {
            Map<String, Double> attrs = new LinkedHashMap<>();
            source.getValue().fields().forEachRemaining(attr -> {
                if (!attr.getValue().isNumber()) throw new IllegalStateException("weights." + source.getKey() + "." + attr.getKey() + " 不是数值");
                attrs.put(attr.getKey(), attr.getValue().doubleValue());
            });
            out.put(source.getKey(), Map.copyOf(attrs));
        });
        return Map.copyOf(out);
    }

    private JsonNode require(String group, String key) {
        JsonNode groupNode = root.get(group);
        JsonNode node = groupNode == null ? null : groupNode.get(key);
        if (node == null || node.isNull()) throw new IllegalStateException("融合参数缺失: " + group + "." + key + "（配置版本 " + configVersion + "）");
        return node;
    }
}
