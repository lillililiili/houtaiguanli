package com.uav.lowaltitude.modules.automationrule.application;

import java.util.List;
import java.util.Map;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.CatalogItem;

/** Configuration vocabulary only. No catalog entry grants authority or dispatches an action. */
public final class AutomationRuleCatalog {
    private AutomationRuleCatalog() { }
    public static final Map<String, List<CatalogItem>> GROUPS = Map.of(
        "verify", List.of(
            number("confidence", "识别置信度", "识别置信度要求", "不低于", "%", 0, 100, true, "融合目标识别结果"),
            number("freshness", "最新观测时效", "观测数据保持最新", "不超过", "秒", 1, 300, false, "观测采集时间，与系统当前时间比较"),
            fixed("consistency", "多源身份一致性", "多源身份信息一致", "同一目标的身份信息一致", true, "关联到同一目标的识别记录"),
            number("sourceCount", "有效数据源数量", "有效数据源数量要求", "不少于", "个", 1, 10, false, "在有效期内、去重后的独立数据源"),
            number("accuracy", "定位误差", "定位精度要求", "不超过", "米", 1, 500, true, "定位记录提供的误差估计值"),
            fixed("identity", "目标身份关联", "目标身份可关联", "身份标识与当前目标明确关联", false, "当前目标的身份关联记录"),
            number("trackDuration", "连续观测时长", "连续观测时长要求", "不少于", "秒", 1, 300, false, "同一轨迹内连续有效的观测记录")),
        "counter", List.of(
            number("counterFreshness", "目标观测时效", "反制依据保持最新", "不超过", "秒", 1, 300, false, "当前目标的最新有效观测"),
            fixed("device", "执行设备状态", "执行设备在线可用", "在线且无故障", false, "执行设备的实时健康状态"),
            fixed("receipt", "回执通道状态", "执行回执通道可用", "可用", false, "执行通道连接及健康状态"),
            fixed("conflictTask", "执行任务冲突", "无冲突执行任务", "当前目标无冲突任务", false, "执行任务调度记录"),
            riskLevel(), fixed("position", "目标位置状态", "目标当前位置有效", "位置已确认且仍有效", false, "最新定位及区域关系研判")),
        "dispose", List.of(
            fixed("riskActive", "风险状态", "当前风险仍有效", "未解除且未排除", false, "当前风险事件状态"),
            number("disposeFreshness", "通知依据时效", "通知依据保持最新", "不超过", "秒", 1, 300, false, "当前风险对应的最新有效事实"),
            fixed("eventLink", "事件与目标关联", "事件关联目标明确", "风险与当前目标已关联", false, "风险事件和目标关联记录"),
            riskLevel(), fixed("sourceKnown", "事实来源", "通知事实可追溯", "来源与采集时间齐全", false, "原始观测与证据元数据")));

    private static CatalogItem number(String code, String label, String name, String op, String unit,
            int min, int max, boolean hold, String source) {
        return new CatalogItem(code, label, name, "NUMBER", op, unit, min, max, null, List.of(), hold, source);
    }
    private static CatalogItem fixed(String code, String label, String name, String value, boolean hold, String source) {
        return new CatalogItem(code, label, name, "FIXED", "等于", "", null, null, value, List.of(), hold, source);
    }
    private static CatalogItem riskLevel() {
        return new CatalogItem("riskLevel", "风险等级", "达到指定风险等级", "SELECT", "属于", "", null, null,
                null, List.of("高风险", "中风险或高风险"), true, "当前有效风险研判结果");
    }
}
