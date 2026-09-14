package com.uav.lowaltitude.modules.fusion.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 观测 `quality` JSON 里那些被别的域读取的事实（决策 15-17）。
 *
 * 抽到一处是因为它原本在目标读侧与融合读侧各有一份：同一段解析写两遍，
 * 迟早一边改了另一边没改，而这种偏差表现为"列表上有方位线、详情里没有"，很难往解析上想。
 */
public final class QualityFacts {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** SQL 里用来粗筛"这条 quality 提到过方位"的键名。 */
    public static final String BEARING_KEY = "bearing_deg";

    private QualityFacts() { }

    /**
     * 取方位角；不是数字、没有该键、或整段读不出来都返回 null。
     *
     * 为什么不在 SQL 里用 `quality ->> 'bearing_deg'`：那是 PostgreSQL 独有写法，H2 直接语法错误，
     * 而同一条查询要在单测库与生产库上都成立。取值一律交给 Java。
     */
    public static BigDecimal bearingDeg(String qualityJson) {
        if (qualityJson == null || qualityJson.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(qualityJson);
            // H2 会把 CAST(? AS JSON) 的字符串再包一层，PostgreSQL 直接存对象；两种形态都要能读回。
            if (node != null && node.isTextual()) node = JSON.readTree(node.textValue());
            if (node == null || !node.isObject()) return null;
            JsonNode bearing = node.get(BEARING_KEY);
            return bearing == null || !bearing.isNumber() ? null : bearing.decimalValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * SQL 片段：粗筛"quality 里提到过方位"。
     *
     * **必须先 CAST 成 VARCHAR**（决策 15-18）：PostgreSQL 里 quality 是 JSONB，
     * 没有 `jsonb ~~ text` 这个运算符，裸 LIKE 会让整个查询 500；
     * 而 H2 把 JSONB 当字符串，裸 LIKE 在单测里是绿的——这正是"H2 绿、PG 红"最容易漏掉的一类。
     *
     * **columnAlias 只接受代码里写死的列名字面量，不得来自请求输入**：这个方法把参数直接拼进 SQL，
     * 传进来一段用户可控的文本就是一处注入点。要按请求内容筛方位时，改成绑定参数的谓词，别走这里。
     */
    public static String mentionsBearing(String columnAlias) {
        return "CAST(" + columnAlias + " AS VARCHAR) LIKE '%" + BEARING_KEY + "%'";
    }
}
