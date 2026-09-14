package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.engine.C03Decision.Decision;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvidenceRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TrackQuality;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.EvaluationOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.HookResult;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.AssessmentInsert;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.EvaluationInsert;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.EvaluationLink;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.MemberRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RunRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.StateRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.TargetRow;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 一条研判一个事务：取运行与版本 → 加载参数 → 收集输入（最新状态、轨迹质量、候选计划、空间事实）→ 新鲜度
 * → C01 → C02-x（按 rule_set_member.priority）→ C03 → 写 rule_evaluation → 投影 assessment_result → 钩子（复核行 / C06）→ 回填告警关联。
 *
 * 为什么 SHADOW 不投影也不告警：影子版本是"新参数会得出什么"的验证，assessment_result 是对外的计划维度事实，
 * 把影子结论投影出去等于在没有激活的情况下让新规则生效；告警同理。影子只落 rule_evaluation(mode=SHADOW)。
 */
@Service
public class LegalityEvaluationService {
    static final String TRIGGER_SCHEDULED = "SCHEDULED", TRIGGER_MANUAL = "MANUAL", TRIGGER_RECOMPUTE = "RECOMPUTE", TRIGGER_REPLAY = "REPLAY";
    static final String PARAM_FRESH_SECONDS = "fresh_seconds", PARAM_TRACK_POINTS = "track_points", PARAM_GAP_SECONDS = "gap_seconds";
    static final String PARAM_TIME_WINDOW_MIN = "time_window_min";
    static final String ALARM_SUPPRESSED_SHADOW = "SUPPRESSED_SHADOW";
    private final RuleEngineRepository repository;
    private final RuleParamLoader params;
    private final SpatialFactPort spatial;
    private final PlanMatcher planMatcher;
    private final List<RuleCheck> checks;
    private final RuleEngineHooks hooks;
    private final AppClock clock;
    private final ObjectMapper json;
    private final C03Decision decision = new C03Decision();

    public LegalityEvaluationService(RuleEngineRepository repository, RuleParamLoader params, SpatialFactPort spatial, PlanMatcher planMatcher,
            List<RuleCheck> checks, RuleEngineHooks hooks, AppClock clock, ObjectMapper json) {
        this.repository = repository; this.params = params; this.spatial = spatial; this.planMatcher = planMatcher;
        this.checks = List.copyOf(checks); this.hooks = hooks; this.clock = clock; this.json = json;
    }

    @Transactional
    public EvaluationResult evaluate(Subject subject, RunMode mode, OffsetDateTime asOf, String runId) {
        return evaluateInCurrentTransaction(subject, mode, asOf, runId, null);
    }

    /** 重算：新研判 supersedes 旧研判，旧行不改。 */
    @Transactional
    public EvaluationResult evaluate(Subject subject, RunMode mode, OffsetDateTime asOf, String runId, String supersedesEvaluationId) {
        return evaluateInCurrentTransaction(subject, mode, asOf, runId, supersedesEvaluationId);
    }

    /**
     * 供 {@link RuleRunService} 在自己的每主体事务里调用：不再套一层事务代理，失败时只回滚到本主体的保存点，
     * 不会把整个批次连接标成 rollback-only。调用方必须已开启事务。
     */
    public EvaluationResult evaluateInCurrentTransaction(Subject subject, RunMode mode, OffsetDateTime asOf, String runId, String supersedesEvaluationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("研判必须在事务内执行");
        if (subject == null || subject.kind() == null || subject.subjectId() == null || subject.subjectId().isBlank()) throw new IllegalArgumentException("研判主体无效");
        RunRow run = repository.findRun(runId);
        if (run == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "规则运行不存在");
        if (run.mode() != mode) throw new IllegalArgumentException("运行模式与请求不一致: " + run.mode() + " vs " + mode);
        if (!"RUNNING".equals(run.status())) throw new IllegalStateException("运行已收尾，不能再追加研判: " + runId);
        EvaluationLink superseded = supersedesEvaluationId == null ? null : repository.findEvaluationLink(supersedesEvaluationId);
        if (supersedesEvaluationId != null && superseded == null) throw new ApiException(HttpStatus.NOT_FOUND, "LEGALITY_EVALUATION_NOT_FOUND", "被重算的研判不存在");
        // 参数先于任何写入加载：缺参数是部署错误，必须在落库前失败，不能留下半条研判。
        RuleParams ruleParams = params.load(run.ruleSetVersionId());
        Map<String, MemberRow> members = new LinkedHashMap<>();
        for (MemberRow member : repository.members(run.ruleSetVersionId())) if (member.enabled()) members.put(member.ruleCode(), member);

        Resolved resolved = resolve(subject);
        StateRow stateRow = resolved.targetId() == null ? null : repository.latestState(resolved.targetId());
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime effectiveAsOf = asOf == null ? now : asOf;
        Freshness freshness = freshness(run.triggerKind(), stateRow, effectiveAsOf, ruleParams);
        // 手动/重算/回放以观测时刻为评估时点，保证同一状态重跑得到同一结论；定时以 tick 时刻为准并受新鲜度约束。
        if (stateRow != null && !TRIGGER_SCHEDULED.equals(run.triggerKind())) effectiveAsOf = stateRow.observedAt();
        String trackId = resolved.targetId() == null ? null : repository.latestTrackId(resolved.targetId());
        TrackQuality track = trackQuality(trackId, ruleParams);
        TargetState state = stateRow == null ? null : state(resolved, trackId, stateRow);

        PlanMatch planMatch = PlanMatch.notApplicable();
        List<AirspaceHit> airspaces = List.of();
        List<HitDetail> hits = new ArrayList<>();
        List<String> candidateIds = List.of();
        boolean usable = freshness == Freshness.FRESH || freshness == Freshness.REPLAY;
        String objectType = resolved.targetId() == null ? null : repository.objectType(resolved.targetId());
        boolean uav = "UAV".equals(objectType);
        if (usable && uav) {
            List<PlanFact> candidates = candidates(resolved, ruleParams, effectiveAsOf, members);
            candidateIds = candidates.stream().map(PlanFact::planId).toList();
            EvaluationContext collecting = new EvaluationContext(resolved.subject(), state, track, null, List.of(), effectiveAsOf, freshness, mode, resolved.sourceMode());
            if (members.containsKey(RuleCodes.C01)) planMatch = planMatcher.match(collecting, candidates, ruleParams);
            airspaces = airspaces(state, effectiveAsOf);
            EvaluationContext context = new EvaluationContext(resolved.subject(), state, track, planMatch, airspaces, effectiveAsOf, freshness, mode, resolved.sourceMode());
            for (RuleCheck check : ordered(members)) {
                HitDetail hit = check.evaluate(context, ruleParams);
                MemberRow member = members.get(check.ruleCode());
                hits.add(new HitDetail(hit.ruleCode(), member.ruleVersionId(), hit.resultCode(), hit.reasonCode(), hit.severity(), hit.facts(), hit.params(), hit.evidence(), hit.message()));
            }
        }
        EvaluationContext context = new EvaluationContext(resolved.subject(), state, track, planMatch, airspaces, effectiveAsOf, freshness, mode, resolved.sourceMode());
        Decision verdict = decision.decide(context, hits, ruleParams);
        // 无人机飞行规则不能把鸟类/人员当作无计划飞行；识别中也不能先认定为无人机。
        if (usable && !uav) {
            verdict = objectType == null || "UNKNOWN".equals(objectType)
                    ? Decision.undetermined(java.util.Set.of("OBJECT_TYPE_UNKNOWN"), List.of())
                    : Decision.notApplicable("NON_UAV_OBJECT");
        }

        String evaluationId = UUID.randomUUID().toString();
        PlanFact plan = planMatch.plan();
        String planId = plan != null ? plan.planId() : resolved.planId();
        String routeVersionId = plan != null ? plan.routeVersionId() : resolved.routeVersionId();
        List<EvidenceRef> evidence = evidence(hits, resolved.targetId(), trackId);
        String shadowOutcome = mode == RunMode.SHADOW ? write(Map.of("kind", ALARM_SUPPRESSED_SHADOW)) : null;
        repository.insertEvaluation(new EvaluationInsert(evaluationId, runId, run.ruleSetVersionId(), mode, subject.kind(), resolved.targetId(), trackId,
                planId, routeVersionId, stateRow == null ? null : stateRow.observedAt(), effectiveAsOf, now, freshness.name(), planMatch.code().name(),
                verdict.status().name(), verdict.score(), verdict.grade(), write(verdict.violationReasons()), write(hits), write(verdict.unknownReasons()),
                write(evidence), write(snapshot(stateRow, track, candidateIds, airspaces, freshness)), supersedesEvaluationId, shadowOutcome,
                resolved.ownerOrgId(), resolved.districtId(), resolved.sourceMode()));

        String assessmentId = null;
        boolean projectable = mode == RunMode.ACTIVE && plan != null && (planMatch.code() == PlanMatchCode.FULL || planMatch.code() == PlanMatchCode.PARTIAL);
        if (projectable) {
            MemberRow c03 = members.get(RuleCodes.C03);
            if (c03 == null) throw new IllegalStateException("规则集版本 " + run.ruleSetVersionId() + " 缺少 C03 成员，无法投影 assessment_result");
            assessmentId = UUID.randomUUID().toString();
            repository.insertAssessment(new AssessmentInsert(assessmentId, plan.planId(), resolved.targetId(), trackId, plan.routeVersionId(), c03.ruleVersionId(), now,
                    verdict.status().name(), write(compress(hits)), write(verdict.unknownReasons()), write(evidence), resolved.sourceMode(), evaluationId,
                    run.ruleSetVersionId(), superseded == null ? null : superseded.assessmentId()));
        }
        boolean alarmEligible = mode == RunMode.ACTIVE && (verdict.status() == LegalStatus.ILLEGAL || verdict.status() == LegalStatus.ABNORMAL);
        EvaluationOutcome outcome = new EvaluationOutcome(evaluationId, runId, run.ruleSetId(), run.ruleSetVersionId(), mode, run.triggerKind(), resolved.subject(),
                resolved.targetId(), trackId, planId, routeVersionId, resolved.ownerOrgId(), resolved.districtId(), resolved.sourceMode(), verdict.status(),
                planMatch.code(), verdict.score(), verdict.grade(), verdict.violationReasons(), verdict.unknownReasons(), effectiveAsOf,
                stateRow == null ? null : stateRow.observedAt(), stateRow == null ? null : stateRow.receivedAt(), now, assessmentId, supersedesEvaluationId,
                alarmEligible, ruleParams);
        HookResult hook = hooks.afterEvaluation(outcome);
        if (hook == null) hook = HookResult.none();
        String alarmOutcome = hook.alarmOutcome() == null || hook.alarmOutcome().isEmpty() ? null : write(hook.alarmOutcome());
        if (assessmentId != null || hook.alarmId() != null || alarmOutcome != null) {
            if (repository.backfillEvaluation(evaluationId, assessmentId, hook.alarmId(), alarmOutcome) != 1) throw new IllegalStateException("研判行回填失败: " + evaluationId);
        }
        return new EvaluationResult(evaluationId, runId, run.ruleSetVersionId(), verdict.status(), planMatch.code(), freshness, verdict.score(), verdict.grade(),
                assessmentId, hook.alarmId(), hook.alarmCreated(), hook.alarmMerged(), verdict.violationReasons(), verdict.unknownReasons());
    }

    /** 主体解析：目标直接取归属元组；计划主体取计划元组，并以同 sn 且有最新状态的目标作为观测来源（没有则 NO_STATE）。 */
    private Resolved resolve(Subject subject) {
        if (subject.kind() == SubjectKind.TARGET) {
            TargetRow target = repository.findTarget(subject.subjectId().trim());
            if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "目标不存在: " + subject.subjectId());
            if (target.ownerOrgId() == null || target.districtId() == null) throw new IllegalStateException("目标缺少组织/区域归属，不能研判: " + target.targetId());
            Subject full = new Subject(SubjectKind.TARGET, target.targetId(), target.ownerOrgId(), target.districtId(), target.sourceMode());
            return new Resolved(full, target.targetId(), target.uavSn(), null, null, null, target.ownerOrgId(), target.districtId(), target.sourceMode());
        }
        PlanFact plan = repository.planSubject(subject.subjectId().trim());
        if (plan == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "飞行计划不存在: " + subject.subjectId());
        if (plan.ownerOrgId() == null || plan.districtId() == null) throw new IllegalStateException("计划缺少组织/区域归属，不能研判: " + plan.planId());
        TargetRow target = plan.uavSn() == null ? null : repository.latestTargetBySn(plan.uavSn(), plan.ownerOrgId(), plan.districtId());
        String sourceMode = target == null ? repository.planSourceMode(plan.planId()) : target.sourceMode();
        Subject full = new Subject(SubjectKind.PLAN, plan.planId(), plan.ownerOrgId(), plan.districtId(), sourceMode);
        return new Resolved(full, target == null ? null : target.targetId(), target == null ? null : target.uavSn(), plan, plan.planId(), plan.routeVersionId(),
                plan.ownerOrgId(), plan.districtId(), sourceMode);
    }

    /**
     * 为什么 STALE 不判合法性：过期的位置只说明"曾经在那里"，据此判 ILLEGAL 会对已飞离的目标制造告警，判 LEGAL 又会掩盖之后的违规；
     * 只有 NOT_APPLICABLE 如实表达"没有可用于判定的当前事实"。
     */
    private static Freshness freshness(String trigger, StateRow state, OffsetDateTime asOf, RuleParams ruleParams) {
        if (state == null || state.observedAt() == null) return Freshness.NO_STATE;
        if (TRIGGER_REPLAY.equals(trigger)) return Freshness.REPLAY;
        if (!TRIGGER_SCHEDULED.equals(trigger)) return Freshness.FRESH;
        int freshSeconds = ruleParams.integer(RuleCodes.C03, PARAM_FRESH_SECONDS);
        return state.observedAt().isBefore(asOf.minusSeconds(freshSeconds)) ? Freshness.STALE : Freshness.FRESH;
    }

    private TrackQuality trackQuality(String trackId, RuleParams ruleParams) {
        int limit = ruleParams.integer(RuleCodes.C03, PARAM_TRACK_POINTS);
        long gapSeconds = ruleParams.integer(RuleCodes.C03, PARAM_GAP_SECONDS);
        if (trackId == null) return new TrackQuality(0, null, false);
        List<OffsetDateTime> times = repository.recentPointTimes(trackId, limit);
        Long maxGap = null;
        for (int i = 1; i < times.size(); i++) {
            OffsetDateTime newer = times.get(i - 1), older = times.get(i);
            if (newer == null || older == null) continue;
            long gap = Math.abs(Duration.between(older, newer).getSeconds());
            if (maxGap == null || gap > maxGap) maxGap = gap;
        }
        return new TrackQuality(times.size(), maxGap, maxGap != null && maxGap > gapSeconds);
    }

    private static TargetState state(Resolved resolved, String trackId, StateRow row) {
        BigDecimal confidence = row.fusionConfidence() != null ? row.fusionConfidence() : row.classificationConfidence();
        // 阶段 8.5：飞手位置随最新状态一起进规则，C02-6 才判得出超视距（共享改动，见 task-8.5.2 报告）。
        return new TargetState(resolved.targetId(), trackId, resolved.uavSn(), row.longitude(), row.latitude(), row.altitudeAmslM(), row.heightAglM(),
                row.speedMps(), row.headingDeg(), confidence, row.observedAt(), row.receivedAt(),
                row.pilotLongitude(), row.pilotLatitude(), row.pilotObservedAt());
    }

    private List<PlanFact> candidates(Resolved resolved, RuleParams ruleParams, OffsetDateTime asOf, Map<String, MemberRow> members) {
        if (!members.containsKey(RuleCodes.C01)) return List.of();
        if (resolved.plan() != null) return List.of(resolved.plan());
        int window = ruleParams.integer(RuleCodes.C01, PARAM_TIME_WINDOW_MIN);
        return repository.candidatePlans(resolved.ownerOrgId(), resolved.districtId(), resolved.uavSn(), asOf, window);
    }

    /** 版本歧义是时间版本事实，先于几何：以 UNKNOWN/VERSION_AMBIGUOUS 行进入上下文，空域类检查据此直接给未知。 */
    private List<AirspaceHit> airspaces(TargetState state, OffsetDateTime asOf) {
        List<AirspaceHit> hits = new ArrayList<>();
        if (spatial.ambiguousEffectiveAirspaceVersion(asOf)) {
            hits.add(new AirspaceHit(null, null, null, RuleCodes.RELATION_UNKNOWN, null, null, null, null, null, RuleCodes.VERSION_AMBIGUOUS));
        }
        if (state != null && state.longitude() != null && state.latitude() != null) {
            List<AirspaceHit> found = spatial.airspaceHits(state, asOf);
            if (found != null) hits.addAll(found);
        }
        return List.copyOf(hits);
    }

    /** 只运行规则集成员里启用的检查，顺序取成员 priority（缺省用检查自带优先级）。C03/C06 不是 RuleCheck，不在此列。 */
    private List<RuleCheck> ordered(Map<String, MemberRow> members) {
        return checks.stream().filter(check -> members.containsKey(check.ruleCode()))
                .sorted(Comparator.comparingInt((RuleCheck check) -> members.get(check.ruleCode()).priority()).thenComparing(RuleCheck::ruleCode)).toList();
    }

    private static List<EvidenceRef> evidence(List<HitDetail> hits, String targetId, String trackId) {
        LinkedHashSet<EvidenceRef> refs = new LinkedHashSet<>();
        if (targetId != null) refs.add(new EvidenceRef("target", targetId));
        if (trackId != null) refs.add(new EvidenceRef("track", trackId));
        for (HitDetail hit : hits) if (hit.evidence() != null) refs.addAll(hit.evidence());
        return List.copyOf(refs);
    }

    /** assessment_result.checks 是阶段 3 的压缩形状 {rule_code,result_code,reason_code}，与 hit_details 一一对应、顺序一致。 */
    private static List<Map<String, Object>> compress(List<HitDetail> hits) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (HitDetail hit : hits) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("rule_code", hit.ruleCode());
            check.put("result_code", hit.resultCode().name());
            if (hit.reasonCode() != null) check.put("reason_code", hit.reasonCode());
            checks.add(check);
        }
        return checks;
    }

    /** input_snapshot 不出 API，但仍只放判定用到的字段，不放原始载荷。 */
    private static Map<String, Object> snapshot(StateRow state, TrackQuality track, List<String> candidateIds, List<AirspaceHit> airspaces, Freshness freshness) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("freshness", freshness.name());
        if (state != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("longitude", state.longitude()); s.put("latitude", state.latitude()); s.put("altitude_amsl_m", state.altitudeAmslM());
            s.put("height_agl_m", state.heightAglM()); s.put("speed_mps", state.speedMps()); s.put("heading_deg", state.headingDeg());
            s.put("classification_confidence", state.classificationConfidence()); s.put("fusion_confidence", state.fusionConfidence());
            s.put("observed_at", state.observedAt() == null ? null : state.observedAt().toInstant().toEpochMilli());
            s.put("received_at", state.receivedAt() == null ? null : state.receivedAt().toInstant().toEpochMilli());
            snapshot.put("state", s);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("point_count", track.pointCount()); t.put("max_gap_seconds", track.maxGapSeconds()); t.put("bridged", track.bridged());
        snapshot.put("track", t);
        snapshot.put("candidate_plan_ids", candidateIds);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (AirspaceHit hit : airspaces) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("airspace_version_id", hit.airspaceVersionId()); h.put("kind_code", hit.kindCode()); h.put("relation", hit.relation()); h.put("unknown_reason", hit.unknownReason());
            hits.add(h);
        }
        snapshot.put("airspace_hits", hits);
        return snapshot;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("研判结果无法序列化", ex); }
    }

    private record Resolved(Subject subject, String targetId, String uavSn, PlanFact plan, String planId, String routeVersionId,
            String ownerOrgId, String districtId, String sourceMode) { }

    /** 单条研判结果；SHADOW 或未投影时 assessmentId 为 null，未触发告警时 alarmId 为 null。 */
    public record EvaluationResult(String evaluationId, String runId, String ruleSetVersionId, LegalStatus legalStatus, PlanMatchCode planMatchCode,
            Freshness freshness, BigDecimal score, String grade, String assessmentId, String alarmId, boolean alarmCreated, boolean alarmMerged,
            List<String> violationReasons, List<String> unknownReasons) { }
}
