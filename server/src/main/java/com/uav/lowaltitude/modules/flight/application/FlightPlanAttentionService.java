package com.uav.lowaltitude.modules.flight.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.HitDetailDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.MatchDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.AttentionSummaryDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.DeviceTaskDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.PlanAttentionDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RiskKindDto;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanQuery;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskQuery;
import com.uav.lowaltitude.platform.api.ApiException;

import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.AVAILABLE;
import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.NO_EVALUATION;

/**
 * 飞行任务列表“需要处理的放在最上面”（2026-10-16 用户确认设计二）。
 *
 * 顺序：需要处理（任务偏离在最前，再是执行中的、新的在前，再是待执行的、开始时间近的在前）→ 其他执行中（新的在前）→ 待执行（开始时间近的在前）
 * → 已结束/已取消（新的在前）。需要处理只看还没结束的任务：
 * 执行中的看匹配结论（偏离、未找到对应飞机、还没对上飞机、部分匹配、不可判定）和未办结的周边设备处置单；
 * 执行中和待执行的都看待核验、待通知的风险。每一项只在当前用户有对应读权限时参与，
 * 没权限看研判或风险的人不会因为排序看出这些结论。
 */
@Service
public class FlightPlanAttentionService {

    public static final List<String> KINDS = List.of("DEVIATION", "AIRCRAFT", "DEVICE", "RISK");
    private static final List<String> ACTIVE = List.of("EXECUTING", "PENDING", "APPROVED");
    /** 只限制单次读取及关联事实查询的大小，不限制参与排序、筛选和统计的任务总数。 */
    private static final int ACTIVE_BATCH_SIZE = 500;
    private static final Set<String> MATCH_ISSUES = Set.of("NONE", "PARTIAL", "UNDETERMINED");
    /** 与飞行任务页“全部风险事件”同一口径：事件类风险，不含两类预设展示样例。 */
    private static final RiskQuery EVENT_RISKS = new RiskQuery(null, null, null, null, null, null, null, null, null, null, null,
            List.of("SPACE_OBJECT", "FOREIGN_OBJECT", "WEATHER"), true);

    private final FlightReadRepository plans;
    private final FlightActualsService actuals;
    private final RiskRepository risks;
    private final AccessControlService access;

    public FlightPlanAttentionService(FlightReadRepository plans, FlightActualsService actuals, RiskRepository risks,
            AccessControlService access) {
        this.plans = plans; this.actuals = actuals; this.risks = risks; this.access = access;
    }

    public record Ranked(List<PlanRow> rows, Map<String, PlanAttentionDto> attention, AttentionSummaryDto summary, long total) { }

    /** kind 为 null 时返回整份排好的列表；给了 kind 时只返回有这一种问题的任务。 */
    public Ranked page(PlanQuery query, AccessDecision flight, String kind, int offset, int size) {
        List<PlanRow> active = new ArrayList<>();
        Map<String, PlanAttentionDto> attention = new LinkedHashMap<>();
        AccessDecision assessment = actuals.assessmentAccess();
        AccessDecision risk = probe(PermissionCode.RISK_READ);
        // 调用方的只读可重复读事务覆盖各批任务、关联事实以及历史任务的计数和分页。
        // 先读完整个筛选范围，再计算全局优先级；后面批次的偏离任务也必须排到第一页。
        for (int batchOffset = 0; ; ) {
            List<PlanRow> batch = plans.listPlansInStatuses(query, flight, ACTIVE, true, batchOffset, ACTIVE_BATCH_SIZE);
            active.addAll(batch);
            attention.putAll(attentionOf(batch, assessment, risk));
            if (batch.size() < ACTIVE_BATCH_SIZE) break;
            batchOffset = Math.addExact(batchOffset, batch.size());
        }

        Comparator<PlanRow> newestFirst = Comparator.comparing(PlanRow::startAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
                .thenComparing(PlanRow::planId);
        Comparator<PlanRow> soonestFirst = Comparator.comparing(PlanRow::startAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(PlanRow::planId);
        List<PlanRow> needs = new ArrayList<>(), executing = new ArrayList<>(), pending = new ArrayList<>();
        for (PlanRow row : active) {
            PlanAttentionDto item = attention.get(row.planId());
            if (item != null && item.needsAction()) needs.add(row);
            else if ("EXECUTING".equals(row.statusCode())) executing.add(row);
            else pending.add(row);
        }
        // 需要处理内部：任务偏离 → 其余执行中（新的在前）→ 待执行（开始时间近的在前），未来的任务不压过正在飞的。
        AttentionSummaryDto summary = summary(attention, executing.size());
        needs.sort(Comparator.comparing((PlanRow row) -> !attention.get(row.planId()).kinds().contains("DEVIATION"))
                .thenComparing(row -> !"EXECUTING".equals(row.statusCode()))
                .thenComparing((a, b) -> "EXECUTING".equals(a.statusCode()) ? newestFirst.compare(a, b) : soonestFirst.compare(a, b)));
        executing.sort(newestFirst);
        pending.sort(soonestFirst);

        if (kind != null) {
            List<PlanRow> picked = needs.stream().filter(row -> attention.get(row.planId()).kinds().contains(kind)).toList();
            return new Ranked(slice(picked, offset, size), attention, summary, picked.size());
        }
        List<PlanRow> ordered = new ArrayList<>(needs);
        ordered.addAll(executing);
        ordered.addAll(pending);
        long ended = plans.countPlansInStatuses(query, flight, ACTIVE, false);
        List<PlanRow> rows = new ArrayList<>(slice(ordered, offset, size));
        int remaining = size - rows.size();
        if (remaining > 0 && ended > 0) {
            int endedOffset = Math.max(0, offset - ordered.size());
            rows.addAll(plans.listPlansInStatuses(query, flight, ACTIVE, false, endedOffset, remaining));
        }
        return new Ranked(rows, attention, summary, ordered.size() + ended);
    }

    private Map<String, PlanAttentionDto> attentionOf(List<PlanRow> active, AccessDecision assessment, AccessDecision risk) {
        List<String> executingIds = active.stream().filter(row -> "EXECUTING".equals(row.statusCode())).map(PlanRow::planId).toList();
        List<String> activeIds = active.stream().map(PlanRow::planId).toList();

        Map<String, String> matchIssue = new HashMap<>();
        if (assessment != null) for (String id : executingIds) {
            String issue = matchIssue(actuals.latestMatch(id, assessment));
            if (issue != null) matchIssue.put(id, issue);
        }

        Map<String, List<DeviceTaskDto>> devices = new HashMap<>();
        for (var task : plans.openDeviceTasks(executingIds))
            devices.computeIfAbsent(task.planId(), ignored -> new ArrayList<>())
                    .add(new DeviceTaskDto(task.taskId(), task.deviceName(), task.workflowState()));

        Map<String, long[]> riskCounts = new HashMap<>();
        Map<String, List<RiskKindDto>> riskKinds = new HashMap<>();
        if (risk != null) for (var count : risks.pendingCountsByPlan(EVENT_RISKS, activeIds, risk)) {
            long[] pair = riskCounts.computeIfAbsent(count.planId(), ignored -> new long[2]);
            pair["PENDING_VERIFICATION".equals(count.state()) ? 0 : 1] += count.total();
            RiskKindDto kind = new RiskKindDto(count.riskType(), "WEATHER".equals(count.riskType()) ? count.reasonCode() : null);
            List<RiskKindDto> kinds = riskKinds.computeIfAbsent(count.planId(), ignored -> new ArrayList<>());
            if (!kinds.contains(kind)) kinds.add(kind);
        }

        Map<String, PlanAttentionDto> result = new LinkedHashMap<>();
        for (PlanRow row : active) {
            String issue = matchIssue.get(row.planId());
            List<DeviceTaskDto> tasks = devices.getOrDefault(row.planId(), List.of());
            long[] pair = riskCounts.getOrDefault(row.planId(), new long[2]);
            List<String> kinds = new ArrayList<>();
            if ("DEVIATION".equals(issue)) kinds.add("DEVIATION");
            else if (issue != null) kinds.add("AIRCRAFT");
            if (!tasks.isEmpty()) kinds.add("DEVICE");
            if (pair[0] + pair[1] > 0) kinds.add("RISK");
            result.put(row.planId(), new PlanAttentionDto(List.copyOf(kinds), issue, pair[0], pair[1], List.copyOf(tasks),
                    List.copyOf(riskKinds.getOrDefault(row.planId(), List.of()))));
        }
        return result;
    }

    /** 与页面 hasRouteDeviation 同一判断：只认引擎已保存的走廊不匹配事实；PARTIAL 可能只是身份缺失，不算偏航。 */
    static String matchIssue(MatchDto match) {
        if (match == null) return null;
        if (NO_EVALUATION.equals(match.availability())) return NO_EVALUATION;
        if (!AVAILABLE.equals(match.availability())) return null;
        List<HitDetailDto> c01 = match.hitDetailsC01() == null ? List.of() : match.hitDetailsC01();
        for (HitDetailDto hit : c01) {
            Object dimensions = hit.facts() == null ? null : hit.facts().get("dimensions");
            if (dimensions instanceof Map<?, ?> map && "MISMATCH".equals(map.get("corridor"))) return "DEVIATION";
        }
        return MATCH_ISSUES.contains(match.planMatchCode()) ? match.planMatchCode() : null;
    }

    private static AttentionSummaryDto summary(Map<String, PlanAttentionDto> attention, long otherExecuting) {
        long needs = 0;
        long[] byKind = new long[KINDS.size()];
        for (PlanAttentionDto item : attention.values()) {
            if (!item.needsAction()) continue;
            needs++;
            for (String kind : item.kinds()) byKind[KINDS.indexOf(kind)]++;
        }
        return new AttentionSummaryDto(needs, byKind[0], byKind[1], byKind[2], byKind[3], otherExecuting);
    }

    private static List<PlanRow> slice(List<PlanRow> rows, int offset, int size) {
        if (offset >= rows.size()) return List.of();
        return rows.subList(offset, (int) Math.min(rows.size(), (long) offset + size));
    }

    private AccessDecision probe(PermissionCode permission) {
        try { return access.require(permission); } catch (ApiException ignored) { return null; }
    }
}
