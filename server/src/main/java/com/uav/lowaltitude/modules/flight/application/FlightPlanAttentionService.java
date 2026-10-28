package com.uav.lowaltitude.modules.flight.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;

import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.HitDetailDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.MatchDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.AttentionSummaryDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.DeviceTaskDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.DeviceCheckAttentionDto;
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
 * 顺序：需要处理（执行中的、新的在前，再是待执行的、开始时间近的在前）→ 其他执行中（新的在前）→ 待执行（开始时间近的在前）
 * → 已结束/已取消（新的在前）。需要处理只看还没结束的任务：
 * 执行中的共用航线覆盖设备检查，当前异常或未办结处置单均计入；2026-10-18 起匹配结论不再作为需要处理的类别。
 * 2026-10-28 用户确认：已关联感知目标（飞机已被设备看到）的任务不再计设备异常，设备本身仍在后台设备异常处理办理。
 * 执行中和待执行的都看待核验、待通知的风险。每一项只在当前用户有对应读权限时参与，
 * 没权限看研判或风险的人不会因为排序看出这些结论。
 */
@Service
public class FlightPlanAttentionService {

    public static final List<String> KINDS = List.of("DEVICE", "RISK");
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
    private final FlightDeviceCheckService deviceChecks;
    private final DeviceMaintenanceService maintenance;

    public FlightPlanAttentionService(FlightReadRepository plans, FlightActualsService actuals, RiskRepository risks,
            AccessControlService access, FlightDeviceCheckService deviceChecks, DeviceMaintenanceService maintenance) {
        this.plans = plans; this.actuals = actuals; this.risks = risks; this.access = access; this.deviceChecks = deviceChecks;
        this.maintenance = maintenance;
    }

    public record Ranked(List<PlanRow> rows, Map<String, PlanAttentionDto> attention, AttentionSummaryDto summary, long total) { }

    /** kind 为 null 时返回整份排好的列表；给了 kind 时只返回有这一种问题的任务。 */
    public Ranked page(PlanQuery query, AccessDecision flight, String kind, int offset, int size) {
        List<PlanRow> active = new ArrayList<>();
        Map<String, PlanAttentionDto> attention = new LinkedHashMap<>();
        AccessDecision assessment = actuals.assessmentAccess();
        AccessDecision risk = probe(PermissionCode.RISK_READ);
        // 调用方的只读可重复读事务覆盖各批任务、关联事实以及历史任务的计数和分页。
        // 先读完整个筛选范围，再计算全局优先级；后面批次的待处理任务也必须参与。
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
        // 需要处理内部：执行中（新的在前）→ 待执行（开始时间近的在前）。
        AttentionSummaryDto summary = summary(attention, executing.size());
        needs.sort(Comparator.comparing((PlanRow row) -> !"EXECUTING".equals(row.statusCode()))
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
        Set<String> seen = new HashSet<>();
        if (assessment != null) for (String id : executingIds) {
            MatchDto match = actuals.latestMatch(id, assessment);
            String issue = matchIssue(match);
            if (issue != null) matchIssue.put(id, issue);
            // 与设备检查弹窗同一口径：最近研判已关联感知目标，飞机已被看到，不必再看周围设备。
            if (match != null && match.targetId() != null) seen.add(id);
        }

        List<String> unseenIds = executingIds.stream().filter(id -> !seen.contains(id)).toList();
        Map<String, List<DeviceTaskDto>> devices = new HashMap<>();
        for (var task : maintenance.openForPlans(unseenIds))
            devices.computeIfAbsent(task.planId(), ignored -> new ArrayList<>())
                    .add(new DeviceTaskDto(task.taskId(), task.deviceName(), task.workflowState()));
        var currentChecks = deviceChecks.readForAttention(active.stream()
                .filter(row -> "EXECUTING".equals(row.statusCode()) && !seen.contains(row.planId())).toList());

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
            var check = currentChecks.get(row.planId());
            List<DeviceCheckAttentionDto> checked = check == null ? List.of() : check.rows().stream()
                    .filter(device -> device.abnormal() || device.maintenance() != null)
                    .map(device -> new DeviceCheckAttentionDto(device.deviceId(),device.name(),device.abnormal(),
                            device.connectivity(),device.healthCode(),device.maintenance()==null?null:device.maintenance().taskId(),
                            device.maintenance()==null?null:device.maintenance().workflowState())).toList();
            long[] pair = riskCounts.getOrDefault(row.planId(), new long[2]);
            List<String> kinds = new ArrayList<>();
            if (!tasks.isEmpty() || !checked.isEmpty()) kinds.add("DEVICE");
            if (pair[0] + pair[1] > 0) kinds.add("RISK");
            result.put(row.planId(), new PlanAttentionDto(List.copyOf(kinds), issue, pair[0], pair[1], List.copyOf(tasks),
                    List.copyOf(riskKinds.getOrDefault(row.planId(), List.of())), checked));
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
        // 旧字段保留为零以兼容响应结构，不再计入待处理数量。
        return new AttentionSummaryDto(needs, 0, 0, byKind[0], byKind[1], otherExecuting);
    }

    private static List<PlanRow> slice(List<PlanRow> rows, int offset, int size) {
        if (offset >= rows.size()) return List.of();
        return rows.subList(offset, (int) Math.min(rows.size(), (long) offset + size));
    }

    private AccessDecision probe(PermissionCode permission) {
        try { return access.require(permission); } catch (ApiException ignored) { return null; }
    }
}
