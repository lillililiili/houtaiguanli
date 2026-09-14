package com.uav.lowaltitude.platform.query;

/** 严重程度的排序口径（决策 15-30）。告警与风险共用，免得两张表"按等级排序"排出不一样的顺序。 */
public final class SeverityOrder {

    private SeverityOrder() { }

    /**
     * SQL 片段：把 severity 翻成可排序的序号（CRITICAL 4 &gt; HIGH 3 &gt; MEDIUM 2 &gt; LOW 1）。
     *
     * 直接按字符串排得到的是字典序 CRITICAL &lt; HIGH &lt; LOW &lt; MEDIUM——最严重的和最轻的挨在一起。
     * 这种错**不会报任何错**，页面上只表现为"按等级排序"排出来的顺序没有规律，很难被当成缺陷发现。
     *
     * 未知取值排到 0（比 LOW 还靠后）：宁可让没见过的等级沉底，也不要让它冒充高危排在最前面。
     *
     * <b>columnAlias 只接受代码里写死的列名字面量，不得来自请求输入</b>——这里把参数直接拼进 SQL。
     */
    public static String rank(String columnAlias) {
        return "CASE " + columnAlias + " WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3"
                + " WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END";
    }
}
