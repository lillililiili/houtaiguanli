package com.uav.lowaltitude.modules.alarm.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.application.AlarmIngestionService.IngestResult;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.GroupRow;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.MemberRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * C06 告警生成与合并。同目标同类型只维护一个 OPEN 合并组：窗口内同级 → MERGED（不建告警）；
 * 更高等级且在升级窗内 → 新告警 UPGRADED；更低 → DOWNGRADED（不建告警）；窗口已过 → 关旧组、开新组。
 * 所有窗口参数来自 rule_param（C06.*），代码里没有裸阈值；alarm 行永不 UPDATE，uav_event.state_code 永不改。
 */
@Service
public class AlarmMergePolicy {
    public static final String RULE_CODE = "C06";
    public static final String PARAM_DEDUP_WINDOW_MIN = "dedup_window_min";
    public static final String PARAM_UPGRADE_WINDOW_MIN = "upgrade_window_min";
    public static final String PARAM_AUTO_CLOSE_MIN = "auto_close_min";
    public static final String PARAM_SEVERITY_BY_GRADE = "severity_by_grade";
    public static final String KIND_CREATED = "CREATED", KIND_MERGED = "MERGED", KIND_UPGRADED = "UPGRADED", KIND_DOWNGRADED = "DOWNGRADED",
            KIND_MANUAL = "MANUAL_ESCALATION", KIND_BLOCKED = "BLOCKED";
    private static final String CLOSED_EXPIRED_NEW_HIT = "EXPIRED_BEFORE_NEW_HIT", CLOSED_AUTO = "WINDOW_EXPIRED";
    private static final String SEVERITY_UNKNOWN = "UNKNOWN";
    private static final List<String> SEVERITY_ORDER = List.of(SEVERITY_UNKNOWN, "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private final AlarmMergeRepository repository;
    private final AlarmIngestionService ingestion;

    public AlarmMergePolicy(AlarmMergeRepository repository, AlarmIngestionService ingestion) {
        this.repository = repository; this.ingestion = ingestion;
    }

    /** 引擎 ACTIVE 模式下 C03 得出 ABNORMAL/ILLEGAL 后调用；与研判同一事务。 */
    @Transactional
    public MergeOutcome apply(MergeInput in, RuleParams params) {
        MemberRow existing = repository.memberOfEvaluation(in.evaluationId());
        // 同一研判重复进入（例如事务重试）只回放既有成员，不再建第二条告警或成员。
        if (existing != null) return new MergeOutcome(existing.memberKind(), existing.alarmId(), existing.alarmId() == null ? null : repository.eventIdOfAlarm(existing.alarmId()), existing.groupId(), existing.severityAfter(), null);
        String severity = severityFor(in.grade(), params);
        int dedupMinutes = params.integer(RULE_CODE, PARAM_DEDUP_WINDOW_MIN);
        int upgradeMinutes = params.integer(RULE_CODE, PARAM_UPGRADE_WINDOW_MIN);
        if (!repository.lockTarget(in.targetId())) return blocked(in, severity, "目标不存在");
        // 窗口算术统一以合并时刻（评估时钟 now）为基准：手动/重算/回放研判的 as_of 可能是几小时或几天前的观测时刻，
        // 不能拿它与实时组的窗口比较；as_of 只进告警 occurred_at。
        OffsetDateTime at = in.mergedAt();
        GroupRow group = repository.lockOpenGroup(in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY);
        if (group != null) {
            boolean withinDedup = at.isBefore(group.windowExpiresAt());
            if (!withinDedup) {
                // 去重窗已过：旧组只改组状态（告警行不动），随后按“新组”处理。
                if (repository.closeGroup(group.groupId(), group.version(), CLOSED_EXPIRED_NEW_HIT, at) != 1) throw conflict();
                group = null;
            } else {
                int delta = rank(severity) - rank(group.currentSeverity());
                boolean withinUpgrade = at.isBefore(group.windowOpenedAt().plusMinutes(upgradeMinutes));
                if (delta > 0 && withinUpgrade) {
                    IngestResult alarm = ingest(in, severity, KIND_UPGRADED, "engine");
                    if (alarm == null) return blocked(in, severity, "告警入库被拒绝");
                    if (repository.recordHit(group.groupId(), group.version(), severity, alarm.alarmId(), at.plusMinutes(dedupMinutes), at) != 1) throw conflict();
                    repository.insertMember(UUID.randomUUID().toString(), group.groupId(), in.evaluationId(), alarm.alarmId(), KIND_UPGRADED, group.currentSeverity(), severity, at);
                    return new MergeOutcome(KIND_UPGRADED, alarm.alarmId(), alarm.eventId(), group.groupId(), severity, null);
                }
                // 同级或更低（或更高但已过升级窗）：只计数并延长去重窗，不建告警。升级窗从组打开时刻计，避免被不断延长的去重窗架空。
                String kind = delta < 0 ? KIND_DOWNGRADED : KIND_MERGED;
                if (repository.recordHit(group.groupId(), group.version(), group.currentSeverity(), group.latestAlarmId(), at.plusMinutes(dedupMinutes), at) != 1) throw conflict();
                repository.insertMember(UUID.randomUUID().toString(), group.groupId(), in.evaluationId(), null, kind, group.currentSeverity(), severity, at);
                return new MergeOutcome(kind, null, null, group.groupId(), group.currentSeverity(), null);
            }
        }
        IngestResult alarm = ingest(in, severity, KIND_CREATED, "engine");
        if (alarm == null) return blocked(in, severity, "告警入库被拒绝");
        String groupId = UUID.randomUUID().toString();
        repository.insertGroup(new GroupRow(groupId, in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY, in.ruleSetId(), "OPEN", severity,
                alarm.alarmId(), alarm.alarmId(), 1, at, at.plusMinutes(dedupMinutes), at, in.ownerOrgId(), in.districtId(), 0));
        repository.insertMember(UUID.randomUUID().toString(), groupId, in.evaluationId(), alarm.alarmId(), KIND_CREATED, null, severity, at);
        return new MergeOutcome(KIND_CREATED, alarm.alarmId(), alarm.eventId(), groupId, severity, null);
    }

    /**
     * 人工转告警：不受去重窗约束，总是新建告警；有 OPEN 组则挂入，否则开一个零长度窗口的组以保留链路。
     * 没有规则参数也能执行（人工动作不依赖 C06 窗口）。等级取研判 grade 的默认映射，缺失时 UNKNOWN。
     */
    @Transactional
    public MergeOutcome escalateManually(MergeInput in) {
        MemberRow existing = repository.memberOfEvaluation(in.evaluationId());
        if (existing != null && existing.alarmId() != null) return new MergeOutcome(existing.memberKind(), existing.alarmId(), repository.eventIdOfAlarm(existing.alarmId()), existing.groupId(), existing.severityAfter(), null);
        String severity = defaultSeverity(in.grade());
        if (!repository.lockTarget(in.targetId())) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ALARM_FACT", "目标不存在");
        IngestResult alarm = ingestion.ingest(fact(in, severity, KIND_MANUAL, "manual", "manual:" + in.evaluationId()));
        OffsetDateTime at = in.mergedAt();
        GroupRow group = repository.lockOpenGroup(in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY);
        String groupId;
        if (group != null) {
            groupId = group.groupId();
            String next = rank(severity) > rank(group.currentSeverity()) ? severity : group.currentSeverity();
            if (repository.recordHit(groupId, group.version(), next, alarm.alarmId(), group.windowExpiresAt(), at) != 1) throw conflict();
        } else {
            groupId = UUID.randomUUID().toString();
            repository.insertGroup(new GroupRow(groupId, in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY, in.ruleSetId(), "OPEN", severity,
                    alarm.alarmId(), alarm.alarmId(), 1, at, at, at, in.ownerOrgId(), in.districtId(), 0));
        }
        if (existing == null) repository.insertMember(UUID.randomUUID().toString(), groupId, in.evaluationId(), alarm.alarmId(), KIND_MANUAL, group == null ? null : group.currentSeverity(), severity, at);
        return new MergeOutcome(KIND_MANUAL, alarm.alarmId(), alarm.eventId(), groupId, severity, null);
    }

    /** 到期自动关闭：window_expires_at + auto_close_min < now 且目标最近 ACTIVE 研判非 ABNORMAL/ILLEGAL。只改组，不碰 alarm/uav_event。 */
    @Transactional
    public int autoCloseExpired(OffsetDateTime now, RuleParams params) {
        int closed = 0;
        OffsetDateTime threshold = now.minusMinutes(params.integer(RULE_CODE, PARAM_AUTO_CLOSE_MIN));
        for (GroupRow group : repository.lockOpenGroupsExpiredBefore(threshold)) {
            String latest = repository.latestActiveLegalStatus(group.targetId());
            if ("ABNORMAL".equals(latest) || "ILLEGAL".equals(latest)) continue;
            if (repository.closeGroup(group.groupId(), group.version(), CLOSED_AUTO, now) == 1) closed++;
        }
        return closed;
    }

    /** C06.severity_by_grade：形如 HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW；grade 缺失或未映射时为 UNKNOWN，不默认成 LOW。 */
    public static String severityFor(String grade, RuleParams params) {
        if (grade == null || grade.isBlank()) return SEVERITY_UNKNOWN;
        for (String entry : params.list(RULE_CODE, PARAM_SEVERITY_BY_GRADE)) {
            String[] pair = entry.trim().split(":", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(grade.trim())) {
                String severity = pair[1].trim().toUpperCase(Locale.ROOT);
                return SEVERITY_ORDER.contains(severity) ? severity : SEVERITY_UNKNOWN;
            }
        }
        return SEVERITY_UNKNOWN;
    }

    private static String defaultSeverity(String grade) {
        if (grade == null) return SEVERITY_UNKNOWN;
        String value = grade.trim().toUpperCase(Locale.ROOT);
        return SEVERITY_ORDER.contains(value) ? value : SEVERITY_UNKNOWN;
    }

    private IngestResult ingest(MergeInput in, String severity, String kind, String trigger) {
        try { return ingestion.ingest(fact(in, severity, kind, trigger, "eval:" + in.evaluationId())); }
        catch (ApiException ex) {
            // INVALID_ALARM_FACT（来源停用、目标目录停用等）不是研判失败：研判照常入库，alarm_outcome 记 BLOCKED。
            if ("INVALID_ALARM_FACT".equals(ex.getCode())) return null;
            throw ex;
        }
    }

    private static TrustedAlarmFact fact(MergeInput in, String severity, String kind, String trigger, String sourceAlarmId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("evaluation_id", in.evaluationId()); detail.put("rule_set_id", in.ruleSetId()); detail.put("rule_set_version_id", in.ruleSetVersionId());
        detail.put("legal_status", in.legalStatus()); detail.put("plan_match_code", in.planMatchCode()); detail.put("grade", in.grade());
        detail.put("score", in.score()); detail.put("violation_reasons", in.violationReasons()); detail.put("trigger", trigger); detail.put("merge_kind", kind);
        return new TrustedAlarmFact(AlarmIngestionService.sourceIdFor(in.sourceMode()), sourceAlarmId, in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY,
                severity, in.occurredAt(), in.mergedAt(), detail, in.sourceMode());
    }

    private static MergeOutcome blocked(MergeInput in, String severity, String reason) { return new MergeOutcome(KIND_BLOCKED, null, null, null, severity, reason); }
    private static int rank(String severity) { int index = SEVERITY_ORDER.indexOf(severity == null ? SEVERITY_UNKNOWN : severity.toUpperCase(Locale.ROOT)); return Math.max(index, 0); }
    private static ApiException conflict() { return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "VERSION_CONFLICT", "告警合并组已被其他操作更新"); }

    /**
     * 两个时刻各司其职，不能互相冒充：
     * @param occurredAt 业务时刻 = 研判 as_of（SCHEDULED 为 tick 时刻，MANUAL/RECOMPUTE/REPLAY 为 observed_at），只用作告警 occurred_at
     * @param mergedAt   合并时刻 = 评估/操作时刻（AppClock now，引擎路径即 rule_evaluation.evaluated_at），用于全部窗口算术、
     *                   组/成员时间戳与告警 received_at；与 {@link #autoCloseExpired} 的 now 同一时基
     */
    public record MergeInput(String evaluationId, String targetId, String ownerOrgId, String districtId, String sourceMode, String ruleSetId,
            String ruleSetVersionId, String legalStatus, String planMatchCode, String grade, BigDecimal score, List<String> violationReasons,
            OffsetDateTime occurredAt, OffsetDateTime mergedAt) {
        public MergeInput {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(mergedAt, "mergedAt");
        }
    }

    /** kind ∈ CREATED|MERGED|UPGRADED|DOWNGRADED|MANUAL_ESCALATION|BLOCKED；不建告警时 alarmId/eventId 为 null。 */
    public record MergeOutcome(String kind, String alarmId, String eventId, String groupId, String severity, String reason) { }
}
