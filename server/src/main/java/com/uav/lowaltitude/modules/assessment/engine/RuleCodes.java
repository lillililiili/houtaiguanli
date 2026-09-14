package com.uav.lowaltitude.modules.assessment.engine;

import java.util.List;

/** 规则编码与原因码常量：字符串只在这里出现一次，避免检查、决策、投影三处拼写漂移。 */
public final class RuleCodes {
    private RuleCodes() { }

    public static final String C01 = "C01";
    public static final String C02_1 = "C02-1";
    public static final String C02_2 = "C02-2";
    public static final String C02_3 = "C02-3";
    public static final String C02_4 = "C02-4";
    public static final String C02_5 = "C02-5";
    public static final String C02_6 = "C02-6";
    public static final String C02_7 = "C02-7";
    public static final String C02_8 = "C02-8";
    public static final String C03 = "C03";
    public static final String C06 = "C06";

    /** 命中即违法的空域类检查（C03 第 4 步）。 */
    public static final List<String> AIRSPACE_CHECKS = List.of(C02_1, C02_2, C02_8);
    /** 命中即异常的行为类检查（C03 第 5 步）。 */
    public static final List<String> BEHAVIOUR_CHECKS = List.of(C02_3, C02_4, C02_5, C02_7);

    /** 契约默认优先级；rule_set_member.priority 存在时以其为准。 */
    public static final int PRIORITY_C01 = 100;
    public static final int PRIORITY_C02_1 = 210;
    public static final int PRIORITY_C02_2 = 220;
    public static final int PRIORITY_C02_3 = 230;
    public static final int PRIORITY_C02_4 = 240;
    public static final int PRIORITY_C02_5 = 250;
    public static final int PRIORITY_C02_6 = 260;
    public static final int PRIORITY_C02_7 = 270;
    public static final int PRIORITY_C02_8 = 280;
    public static final int PRIORITY_C03 = 300;

    // FAIL 原因码
    public static final String INSIDE_RESTRICTED_AIRSPACE = "INSIDE_RESTRICTED_AIRSPACE";
    public static final String AIRSPACE_ALTITUDE_EXCEEDED = "AIRSPACE_ALTITUDE_EXCEEDED";
    public static final String ROUTE_DEVIATION = "ROUTE_DEVIATION";
    public static final String TIME_WINDOW_OVERRUN = "TIME_WINDOW_OVERRUN";
    public static final String NIGHT_FLIGHT = "NIGHT_FLIGHT";
    public static final String PLAN_ALTITUDE_EXCEEDED = "PLAN_ALTITUDE_EXCEEDED";
    public static final String TEMPORARY_RESTRICTION_ACTIVE = "TEMPORARY_RESTRICTION_ACTIVE";
    public static final String NO_AUTHORIZATION = "NO_AUTHORIZATION";

    // UNDETERMINED / NOT_APPLICABLE 原因码
    public static final String BOUNDARY_POLICY_UNKNOWN = "BOUNDARY_POLICY_UNKNOWN";
    public static final String POSITION_UNKNOWN = "POSITION_UNKNOWN";
    public static final String ALTITUDE_DATUM_OR_RANGE_UNKNOWN = "ALTITUDE_DATUM_OR_RANGE_UNKNOWN";
    public static final String VERSION_AMBIGUOUS = "VERSION_AMBIGUOUS";
    public static final String AIRSPACE_BOUNDARY_UNKNOWN = "AIRSPACE_BOUNDARY_UNKNOWN";
    public static final String CORRIDOR_WIDTH_UNKNOWN = "CORRIDOR_WIDTH_UNKNOWN";
    public static final String ROUTE_GEOMETRY_UNKNOWN = "ROUTE_GEOMETRY_UNKNOWN";
    public static final String PLAN_TIME_UNKNOWN = "PLAN_TIME_UNKNOWN";
    public static final String PILOT_POSITION_UNAVAILABLE = "PILOT_POSITION_UNAVAILABLE";
    /** 阶段 8.5：目标与飞手的大圆距离超过 C02-6 阈值，即超视距飞行。 */
    public static final String BVLOS_EXCEEDED = "BVLOS_EXCEEDED";
    public static final String NO_PLAN = "NO_PLAN";
    public static final String STATE_STALE = "STATE_STALE";
    public static final String NO_STATE = "NO_STATE";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String CONFIDENCE_UNKNOWN = "CONFIDENCE_UNKNOWN";
    public static final String TRACK_DEGRADED = "TRACK_DEGRADED";
    public static final String TRACK_BRIDGED = "TRACK_BRIDGED";
    public static final String PLAN_MATCH_UNDETERMINED = "PLAN_MATCH_UNDETERMINED";
    public static final String PLAN_MATCHER_UNAVAILABLE = "PLAN_MATCHER_UNAVAILABLE";

    /** 空间关系取值（与 AirspaceHit.relation 一致）。 */
    public static final String RELATION_COVERS = "COVERS";
    public static final String RELATION_TOUCHES = "TOUCHES";
    public static final String RELATION_DISJOINT = "DISJOINT";
    public static final String RELATION_UNKNOWN = "UNKNOWN";

    public static final String DATUM_AGL = "AGL";
    public static final String DATUM_AMSL = "AMSL";
    public static final String PARAM_STATUS_DEMO = "DEMO";
}
