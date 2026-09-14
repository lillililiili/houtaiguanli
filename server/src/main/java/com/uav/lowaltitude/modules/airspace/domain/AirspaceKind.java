package com.uav.lowaltitude.modules.airspace.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 空域种类字典（决策 9-3）。写接口只接受这五个规范值：页面的图层映射（禁飞 / 限高 / 适飞）依赖稳定字典，
 * 自由文本会让同一类空域在不同批次里有两三种写法，图层就画不出来。
 * 迁移 061 的 CHECK 曾容忍两个历史写法（HEIGHT_LIMIT / TEMPORARY），那是阶段 3/7 种子留下的存量数据：
 * PostgreSQL 上由 db/postgresql/V202609050065 归一存量行，阶段 10 的迁移 074 把 ck_stage9_airspace_kind_code 收紧为
 * 这五个值（决策 10-2/10-5），阶段 7 种子同步改写规范值；此后库里、种子里、规则参数里都只有这五个值。
 */
public final class AirspaceKind {
    public static final String PROHIBITED = "PROHIBITED";
    public static final String RESTRICTED = "RESTRICTED";
    public static final String ALTITUDE_LIMIT = "ALTITUDE_LIMIT";
    public static final String PERMITTED = "PERMITTED";
    public static final String TEMPORARY_CONTROL = "TEMPORARY_CONTROL";

    /** 保持声明顺序，便于错误信息里给出稳定的可选值列表。 */
    public static final Set<String> CODES = new LinkedHashSet<>(
            java.util.List.of(PROHIBITED, RESTRICTED, ALTITUDE_LIMIT, PERMITTED, TEMPORARY_CONTROL));

    private AirspaceKind() { }

    public static boolean supported(String kindCode) {
        return kindCode != null && CODES.contains(kindCode);
    }

    public static String options() {
        return String.join(" / ", CODES);
    }
}
