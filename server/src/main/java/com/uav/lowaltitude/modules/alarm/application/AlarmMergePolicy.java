package com.uav.lowaltitude.modules.alarm.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmIngestionService.IngestResult;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.AlarmState;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.EscalationInsert;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.GroupRow;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.MemberRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * C06 告警生成与合并。同目标同类型只维护一个 OPEN 合并组，组里的当前告警（latest_alarm_id）就是这架无人机正在处理的那一条：
 * 去重窗内再次命中，等级更高或带来告警里还没有的违规原因 → ESCALATED，升级当前告警（追加 alarm_escalation，
 * 不建告警、不另起核实）；同级且原因都已在告警里 → MERGED；更低 → DOWNGRADED（都只计数）；窗口已过 → 关旧组、开新组。
 * 当前告警已被核实为误报时不往里并：更高等级且在升级窗内 → 新告警 UPGRADED，否则只计数。
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
            KIND_MANUAL = "MANUAL_ESCALATION", KIND_BLOCKED = "BLOCKED", KIND_ESCALATED = "ESCALATED";
    /** 告警升级记录的来源：系统研判命中 / 人工转告警。 */
    public static final String TRIGGER_ENGINE = "ENGINE", TRIGGER_MANUAL = "MANUAL";
    private static final String CLOSED_EXPIRED_NEW_HIT = "EXPIRED_BEFORE_NEW_HIT", CLOSED_AUTO = "WINDOW_EXPIRED";
    private static final String SEVERITY_UNKNOWN = "UNKNOWN";
    private static final String EVENT_FALSE_POSITIVE = "FALSE_POSITIVE";
    private static final List<String> SEVERITY_ORDER = List.of(SEVERITY_UNKNOWN, "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private final AlarmMergeRepository repository;
    private final AlarmIngestionService ingestion;
    private final ObjectMapper json;

    public AlarmMergePolicy(AlarmMergeRepository repository, AlarmIngestionService ingestion, ObjectMapper json) {
        this.repository = repository; this.ingestion = ingestion; this.json = json;
    }

    /** 引擎 ACTIVE 模式下 C03 得出 ABNORMAL/ILLEGAL 后调用；与研判同一事务。 */
    @Transactional
    public MergeOutcome apply(MergeInput in, RuleParams params) {
        MemberRow existing = repository.memberOfEvaluation(in.evaluationId());
        // 同一研判重复进入（例如事务重试）只回放既有成员，不再建第二条告警、成员或升级记录。
        if (existing != null) return replay(existing);
        String severity = severityFor(in.grade(), params);
        int dedupMinutes = params.integer(RULE_CODE, PARAM_DEDUP_WINDOW_MIN);
        int upgradeMinutes = params.integer(RULE_CODE, PARAM_UPGRADE_WINDOW_MIN);
        if (!repository.lockTarget(in.targetId())) return blocked(in, severity, "目标不存在");
        // 窗口算术统一以合并时刻（评估时钟 now）为基准：手动/重算/回放研判的 as_of 可能是几小时或几天前的观测时刻，
        // 不能拿它与实时组的窗口比较；as_of 只进告警 occurred_at。
        OffsetDateTime at = in.mergedAt();
        GroupRow group = repository.lockOpenGroup(in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY);
        if (group != null) {
            // The confirmed window method includes exactly five minutes; old published methods retain [from,to).
            boolean withinDedup = confirmedWindows(params) ? !at.isAfter(group.windowExpiresAt()) : at.isBefore(group.windowExpiresAt());
            if (!withinDedup) {
                // 去重窗已过：旧组只改组状态（告警行不动），随后按“新组”处理。
                if (repository.closeGroup(group.groupId(), group.version(), CLOSED_EXPIRED_NEW_HIT, at) != 1) throw conflict();
                group = null;
            } else {
                int delta = rank(severity) - rank(group.currentSeverity());
                boolean withinUpgrade = at.isBefore(group.windowOpenedAt().plusMinutes(upgradeMinutes));
                AlarmState current = repository.alarmState(group.latestAlarmId());
                if (params.has(RULE_CODE, "upgrade_window_basis")
                        && "FALSE_POSITIVE_AT".equals(params.string(RULE_CODE, "upgrade_window_basis")) && !live(current)) {
                    OffsetDateTime verifiedAt = current == null ? null : repository.falsePositiveAt(current.eventId());
                    withinUpgrade = verifiedAt != null && !at.isBefore(verifiedAt) && !at.isAfter(verifiedAt.plusMinutes(upgradeMinutes));
                }
                if (live(current)) {
                    // 同一架无人机只留一条告警（BUG-16）：等级更高或出现新的违规原因（如偏航、进入禁飞区，BUG-11）就升级原告警，
                    // 不受升级窗限制——升级不新建告警，也就没有刷屏的问题；原告警已核实属实的照样升级，不要求重新核实。
                    Change change = change(current, severity, in.violationReasons(), false);
                    if (change != null) {
                        record(in, group.groupId(), current, change, TRIGGER_ENGINE, null, null, at);
                        if (repository.recordHit(group.groupId(), group.version(), higher(group.currentSeverity(), change.after()), group.latestAlarmId(),
                                at.plusMinutes(dedupMinutes), at) != 1) throw conflict();
                        repository.insertMember(UUID.randomUUID().toString(), group.groupId(), in.evaluationId(), current.alarmId(), KIND_ESCALATED, change.before(), change.after(), at);
                        return new MergeOutcome(KIND_ESCALATED, current.alarmId(), current.eventId(), group.groupId(), change.after(), null);
                    }
                } else if (delta > 0 && withinUpgrade) {
                    // 当前告警已被核实为误报：误报结论不覆盖更重的新违规，仍按升级窗另建告警重新核实。
                    IngestResult alarm = ingest(in, severity, KIND_UPGRADED, "engine");
                    if (alarm == null) return blocked(in, severity, "告警入库被拒绝");
                    if (repository.recordHit(group.groupId(), group.version(), severity, alarm.alarmId(), at.plusMinutes(dedupMinutes), at) != 1) throw conflict();
                    repository.insertMember(UUID.randomUUID().toString(), group.groupId(), in.evaluationId(), alarm.alarmId(), KIND_UPGRADED, group.currentSeverity(), severity, at);
                    return new MergeOutcome(KIND_UPGRADED, alarm.alarmId(), alarm.eventId(), group.groupId(), severity, null);
                }
                // 没有更高等级、也没有新原因（或误报告警后更高但已过升级窗）：只计数并延长去重窗，不建告警。
                // 升级窗从组打开时刻计，避免被不断延长的去重窗架空。
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
     * 人工转告警：没有规则参数也能执行（人工动作不依赖 C06 参数）。等级取研判 grade 的默认映射，缺失时 UNKNOWN。
     * 同一目标的 OPEN 组还在去重窗内、当前告警未被判误报时，并入并升级这条告警，升级记录写明操作人与说明，
     * 不再另起一条待核实告警（BUG-16）；否则新建告警：有 OPEN 组则挂入，没有就开一个零长度窗口的组以保留链路。
     */
    @Transactional
    public MergeOutcome escalateManually(MergeInput in, String actorId, String note) {
        MemberRow existing = repository.memberOfEvaluation(in.evaluationId());
        if (existing != null && existing.alarmId() != null) return replay(existing);
        String severity = defaultSeverity(in.grade());
        if (!repository.lockTarget(in.targetId())) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ALARM_FACT", "目标不存在");
        OffsetDateTime at = in.mergedAt();
        GroupRow group = repository.lockOpenGroup(in.targetId(), AlarmIngestionService.ALARM_TYPE_RULE_LEGALITY);
        AlarmState current = group == null || !at.isBefore(group.windowExpiresAt()) ? null : repository.alarmState(group.latestAlarmId());
        if (live(current)) {
            // 人工动作总要留下记录：即使等级与原因都没变，也追加一行写明谁、为什么转告警。
            Change change = change(current, severity, in.violationReasons(), true);
            record(in, group.groupId(), current, change, TRIGGER_MANUAL, actorId, note == null || note.isBlank() ? null : note.trim(), at);
            if (repository.recordHit(group.groupId(), group.version(), higher(group.currentSeverity(), change.after()), group.latestAlarmId(),
                    group.windowExpiresAt(), at) != 1) throw conflict();
            if (existing == null) repository.insertMember(UUID.randomUUID().toString(), group.groupId(), in.evaluationId(), current.alarmId(), KIND_MANUAL, change.before(), change.after(), at);
            return new MergeOutcome(KIND_MANUAL, current.alarmId(), current.eventId(), group.groupId(), change.after(), null);
        }
        IngestResult alarm = ingestion.ingest(fact(in, severity, KIND_MANUAL, "manual", "manual:" + in.evaluationId()));
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
        boolean lastHit = confirmedWindows(params);
        List<GroupRow> expired = lastHit ? repository.lockOpenGroupsLastHitBefore(threshold) : repository.lockOpenGroupsExpiredBefore(threshold);
        for (GroupRow group : expired) {
            String latest = repository.latestActiveLegalStatus(group.targetId());
            if ("ABNORMAL".equals(latest) || "ILLEGAL".equals(latest)) continue;
            // Unknown, stale and missing observations do not establish that a violation stopped.
            if (lastHit && (!"LEGAL".equals(latest)
                    || !repository.latestLegalEvidenceIsCurrent(group.targetId(), now, params.integer("C03", "fresh_seconds")))) continue;
            if (repository.closeGroup(group.groupId(), group.version(), CLOSED_AUTO, now) == 1) closed++;
        }
        return closed;
    }

    private static boolean confirmedWindows(RuleParams params) {
        return params.has(RULE_CODE, "auto_close_basis") && "LAST_HIT".equals(params.string(RULE_CODE, "auto_close_basis"));
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

    public String associatedAlarmId(String evaluationId) {
        return repository.associatedAlarmId(evaluationId);
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

    /** 当前告警还在处理链上：没有事件的旧数据也算；已核实为误报的不再往里并。 */
    private static boolean live(AlarmState current) {
        return current != null && !EVENT_FALSE_POSITIVE.equals(current.eventState());
    }

    /**
     * 与告警当前状态比：等级更高或带来告警里还没有的违规原因才算升级，原因按出现顺序累计、不重复。
     * 都没有时引擎路径返回 null（只计数）；人工路径（always）仍返回一条“等级与原因不变”的记录。
     */
    private Change change(AlarmState current, String severity, List<String> reasons, boolean always) {
        String before = current.currentSeverity();
        String after = higher(before, severity);
        List<String> known = currentReasons(current);
        List<String> added = new ArrayList<>();
        if (reasons != null) {
            for (String reason : reasons) {
                if (reason != null && !reason.isBlank() && !known.contains(reason.trim()) && !added.contains(reason.trim())) added.add(reason.trim());
            }
        }
        if (!always && rank(after) <= rank(before) && added.isEmpty()) return null;
        List<String> all = new ArrayList<>(known);
        all.addAll(added);
        return new Change(before, after, added, all);
    }

    private void record(MergeInput in, String groupId, AlarmState current, Change change, String trigger, String actorId, String note, OffsetDateTime at) {
        repository.insertEscalation(new EscalationInsert(UUID.randomUUID().toString(), current.alarmId(), current.escalationSeq() + 1, groupId,
                in.evaluationId(), trigger, change.before(), change.after(), write(change.added()), write(change.reasons()), note, actorId,
                current.ownerOrgId() != null ? current.ownerOrgId() : in.ownerOrgId(),
                current.districtId() != null ? current.districtId() : in.districtId(), at));
    }

    /** 告警当前的违规原因：升级过取最近一次的累计结果，否则取告警明细里的 violation_reasons。读不出来按空处理，不阻断合并。 */
    private List<String> currentReasons(AlarmState current) {
        JsonNode node = read(current.escalationSeq() > 0 ? current.escalatedReasonsJson() : current.detailJson());
        if (node != null && node.isObject()) node = node.get("violation_reasons");
        List<String> output = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode item : node) if (item.isTextual() && !output.contains(item.textValue())) output.add(item.textValue());
        return output;
    }

    /** JSON 列在 H2 上可能回读成带引号的 JSON 字符串：是文本就再解析一层（同 AlarmEscalationService）。 */
    private JsonNode read(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode node = json.readTree(text);
            return node != null && node.isTextual() ? json.readTree(node.textValue()) : node;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String write(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("违规原因无法序列化", ex); }
    }

    private MergeOutcome replay(MemberRow existing) {
        return new MergeOutcome(existing.memberKind(), existing.alarmId(), existing.alarmId() == null ? null : repository.eventIdOfAlarm(existing.alarmId()),
                existing.groupId(), existing.severityAfter(), null);
    }

    private static MergeOutcome blocked(MergeInput in, String severity, String reason) { return new MergeOutcome(KIND_BLOCKED, null, null, null, severity, reason); }
    private static int rank(String severity) { int index = SEVERITY_ORDER.indexOf(severity == null ? SEVERITY_UNKNOWN : severity.toUpperCase(Locale.ROOT)); return Math.max(index, 0); }
    private static String higher(String current, String candidate) { return rank(candidate) > rank(current) ? candidate : current; }
    private static ApiException conflict() { return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "VERSION_CONFLICT", "告警合并组已被其他操作更新"); }

    /** 一次升级：升级前后的等级、本次新增的违规原因与升级后的全部原因。 */
    private record Change(String before, String after, List<String> added, List<String> reasons) { }

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

    /**
     * kind ∈ CREATED|MERGED|UPGRADED|DOWNGRADED|ESCALATED|MANUAL_ESCALATION|BLOCKED；
     * ESCALATED 与并入原告警的 MANUAL_ESCALATION 返回被升级的原告警与事件；MERGED/DOWNGRADED/BLOCKED 的 alarmId/eventId 为 null。
     */
    public record MergeOutcome(String kind, String alarmId, String eventId, String groupId, String severity, String reason) { }
}
