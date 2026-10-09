package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * 没有报备任务、但按规定不用申请的飞行（2026-10-08 业务决定，新-28，确认书 2-2）。
 * 国家规定小型及以下无人机在真高 120 米以下的适飞空域飞行无需申请；系统分不出机型大小，按下面三条认定：
 * <ul>
 *   <li>完全没有报备任务：C01 对不上任何任务，也没有挂上本机编号的任务（有任务却飞出时段或航线的照旧按任务查）；</li>
 *   <li>设备报的离地高度（AGL）不超过 120 米；没有离地高度不推算，照旧报；</li>
 *   <li>不在禁飞、管制、限高和生效中的临时管控空域里；压在边界上或空域关系不明的照旧报。</li>
 * </ul>
 * “看得准”（置信度、轨迹点数、断点）由 C03 第 2 步质量门把关，这里不重复。120 米是法规数值，不是演示参数，
 * 和 2-12 的禁飞区定级一样写在代码里，已发布的规则集版本不重新发布也照此执行。
 */
public final class NoPlanExemption {
    /** 真高 120 米：条例划定适飞空域的高度上限，含 120 米本身。 */
    public static final BigDecimal MAX_HEIGHT_AGL_M = BigDecimal.valueOf(120);
    /** C01 明细 facts 里的标记：页面据此把“没有报备任务”的合法结论讲清楚。 */
    public static final String FACT_EXEMPT = "no_plan_exempt";
    public static final String FACT_HEIGHT_AGL_M = "height_agl_m";
    private static final String TEMPORARY_CONTROL = "TEMPORARY_CONTROL";
    /** 禁飞、管制、限高三类随时都算；各规则集版本在 C02-1/C02-2 的 kinds 里另配的类型一并算。 */
    private static final List<String> STANDING_KINDS = List.of("PROHIBITED", "RESTRICTED", "ALTITUDE_LIMIT");

    private NoPlanExemption() { }

    public static boolean applies(EvaluationContext context, RuleParams params) {
        if (context == null) return false;
        PlanMatch match = context.planMatch();
        if (match == null || match.code() != PlanMatchCode.NONE || match.plan() != null) return false;
        TargetState state = context.state();
        if (state == null || state.longitude() == null || state.latitude() == null) return false;
        if (state.heightAglM() == null || state.heightAglM().compareTo(MAX_HEIGHT_AGL_M) > 0) return false;
        Set<String> standing = kinds(params, RuleCodes.C02_1, RuleCodes.C02_2);
        standing.addAll(STANDING_KINDS);
        // 临时管控（含 C02-8 kinds 另配的类型）只在生效窗口内算；同一类型也配在 C02-1/C02-2 里的，那两项检查随时都看，这里也随时算。
        Set<String> temporary = kinds(params, RuleCodes.C02_8);
        temporary.add(TEMPORARY_CONTROL);
        for (AirspaceHit hit : context.airspaces() == null ? List.<AirspaceHit>of() : context.airspaces()) {
            // 同一空域两个版本同时生效：不知道该用哪个边界，不能说它在管控空域外面。
            if (RuleCodes.RELATION_UNKNOWN.equals(hit.relation()) && RuleCodes.VERSION_AMBIGUOUS.equals(hit.unknownReason())) return false;
            String kind = hit.kindCode();
            boolean controlled = kind != null && (standing.contains(kind) || (temporary.contains(kind) && active(hit, context.asOf())));
            if (controlled && !RuleCodes.RELATION_DISJOINT.equals(hit.relation())) return false;
        }
        return true;
    }

    /** 研判页、C01 明细上的说明：离地高度取整到米。 */
    public static String explanation(BigDecimal heightAgl) {
        return "没有报备任务；离地约 " + heightAgl.setScale(0, RoundingMode.HALF_UP).toPlainString()
                + " 米，不超过 120 米，不在禁飞区、管制区、限高区、临时管控区内，按规定无需申请";
    }

    /** 与 C02-8 同一口径：生效窗口 [valid_from, valid_to) 之外的临时管控不算。 */
    private static boolean active(AirspaceHit hit, OffsetDateTime asOf) {
        if (asOf == null || hit.validFrom() == null) return false;
        return !asOf.isBefore(hit.validFrom()) && (hit.validTo() == null || asOf.isBefore(hit.validTo()));
    }

    private static Set<String> kinds(RuleParams params, String... ruleCodes) {
        Set<String> kinds = new LinkedHashSet<>();
        for (String code : ruleCodes) {
            if (params != null && params.has(code, "kinds")) kinds.addAll(params.list(code, "kinds"));
        }
        return kinds;
    }
}
