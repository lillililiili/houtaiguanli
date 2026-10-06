package com.uav.lowaltitude.modules.handoff.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceLedgerRepository;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DisposalMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventVerificationDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EvidenceChainItemDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EvidenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.JudgmentMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialV2Dto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.PartyMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.PilotLocationMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.domain.HandoffParty;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 处罚交接材料包 v2 的组装（决策 14-2 / 14-28）。
 *
 * 独立成组件是因为**服务层和种子必须用同一套组装**：种子手写一份 JSON 壳子看起来能跑，
 * 但库里落下的是空 `disposals`、空 `verifications` 的假材料——页面照样渲染，
 * 而"证据充分"这件事根本没被证明过。更糟的是服务路径是对的，看代码只会看到对的那条。
 *
 * 快照冻结的是"提交那一刻的事实"，之后源怎么变都不影响它。
 */
@Component
public class HandoffMaterialAssembler {
    /** 材料形状版本（决策 14-1）；v1 的风险形状原样保留。 */
    public static final int SCHEMA_V2 = 2;

    /** 证据链四类，与事件页“证据链”及证据台账一致。 */
    private static final List<String> EVIDENCE_CATEGORIES = List.of("VIDEO", "TRACK", "IMAGE", "COMMAND");
    /** 每类最多冻结的条数，与事件页证据链每类读取的上限一致。 */
    private static final int EVIDENCE_PER_CATEGORY = 100;

    private final HandoffRepository repository;
    private final com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository advisory;
    private final com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targets;
    private final EvidenceLedgerRepository ledger;
    private final ObjectMapper json;
    private final AppClock clock;

    public HandoffMaterialAssembler(HandoffRepository repository, com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository advisory,
            com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targets, EvidenceLedgerRepository ledger,
            ObjectMapper json, AppClock clock) {
        this.repository = repository; this.advisory = advisory; this.targets = targets;
        this.ledger = ledger; this.json = json; this.clock = clock;
    }

    /**
     * 证据链按谁的可见范围冻结。后台自动移送和种子用 {@link #SYSTEM}（全部范围）；
     * 人工提交时用提交人的证据读取范围，轨迹和指令再看提交人能否查看目标和设备指令，与提交人在事件页看到的证据链一致。
     */
    public record EvidenceScope(AccessDecision decision, boolean tracks, boolean commands) {
        public static final EvidenceScope SYSTEM = new EvidenceScope(new AccessDecision("system:handoff-material", ScopeMode.ALL), true, true);
    }

    /**
     * @param includeEvidence 证据段是否纳入。由调用方按**提交人当时**的 evidence:read 决定；
     *        为 false 时整段省略并置 `evidence_omitted=true`，**不留空数组**——
     *        "没有权限看"和"本案没有证据"是两件完全不同的事，混同会让读卷宗的人得出相反结论（决策 14-4）。
     */
    public MaterialV2Dto assemble(String eventId, boolean includeEvidence) {
        return assemble(eventId, includeEvidence ? EvidenceScope.SYSTEM : null);
    }

    /** @param evidenceScope 为 null 时证据段和证据链整段省略，并置 evidence_omitted=true。 */
    public MaterialV2Dto assemble(String eventId, EvidenceScope evidenceScope) {
        boolean includeEvidence = evidenceScope != null;
        HandoffRepository.EventMaterialRow event = repository.eventMaterial(eventId);
        EventMaterialDto eventDto = event == null ? null : new EventMaterialDto(event.eventId(), event.alarmId(),
                event.sourceAlarmId(), event.alarmType(), event.severity(), millis(event.occurredAt()),
                millis(event.receivedAt()), event.state(), event.targetId(), event.ownerOrgId(), event.districtId(),
                event.sourceMode(), event.version());
        List<EventVerificationDto> verifications = repository.eventVerifications(eventId).stream()
                .map(v -> new EventVerificationDto(v.conclusion(), v.note(), v.resultingState(), v.version(),
                        v.createdAt().toInstant().toEpochMilli(), v.actorId(), v.actorName()))
                .toList();
        List<DisposalMaterialDto> disposals = repository.eventDisposals(eventId).stream()
                .map(d -> new DisposalMaterialDto(d.authorizationId(), d.authorizationNo(), d.actionType(), d.channel(),
                        d.deviceId(), d.status(), d.requestedByName(), d.approvedByName(), millis(d.validFrom()),
                        millis(d.validUntil()), d.resultCode(), d.resultDetail(), millis(d.completedAt()),
                        d.authorizationMode()))
                .toList();
        List<EvidenceMaterialDto> evidence = includeEvidence
                ? repository.eventEvidence(eventId).stream()
                        .map(e -> new EvidenceMaterialDto(e.evidenceId(), e.evidenceNo(), e.kindCode(), e.sha256(),
                                millis(e.capturedAt()), e.status()))
                        .toList()
                : null;
        ReferenceMaterialDto references = event == null || event.targetId() == null ? null
                : new ReferenceMaterialDto(null, null, null, event.targetId(), null);
        PilotLocationMaterialDto pilotLocation = pilotLocation(event);
        // 兼容旧材料对空历史段的省略；劝离记录按提交时事实独立冻结。
        return new MaterialV2Dto(SCHEMA_V2, eventDto, emptyToNull(verifications), emptyToNull(disposals), evidence,
                includeEvidence ? null : Boolean.TRUE, references, advisory.records(eventId), pilotLocation,
                judgments(eventId), includeEvidence ? evidenceChain(eventId, evidenceScope) : null,
                event == null ? null : party(eventId, pilotLocation != null));
    }

    /** 当事人认定：只看关联本事件的报备计划和目标上报的序列号，不取电话等联系方式。 */
    public PartyMaterialDto party(String eventId, Boolean pilotLocation) {
        HandoffRepository.PartyRow row = repository.eventParty(eventId);
        HandoffParty.Assessment assessment = HandoffParty.assess(row.planId(), row.pilotName(), row.operatorName(),
                row.planSerial(), row.targetSerial(), pilotLocation);
        return new PartyMaterialDto(assessment.status(), assessment.label(), assessment.reasons().isEmpty() ? null : assessment.reasons(),
                row.planId(), row.planNo(), assessment.identified() ? row.pilotName() : null,
                assessment.identified() ? row.operatorName() : null, assessment.uavSn());
    }

    /** 告警依据的研判在前；提交时同一目标若已有更新的研判，另列一条。一条都没有时保留空数组：那是“移送时没有找到研判”这个事实。 */
    private List<JudgmentMaterialDto> judgments(String eventId) {
        List<JudgmentMaterialDto> result = new ArrayList<>();
        HandoffRepository.JudgmentRow basis = repository.eventJudgment(eventId, true);
        HandoffRepository.JudgmentRow latest = repository.eventJudgment(eventId, false);
        if (basis != null) result.add(judgment("EVENT_ALARM", basis));
        if (latest != null && (basis == null || !latest.evaluationId().equals(basis.evaluationId()))) result.add(judgment("LATEST", latest));
        return result;
    }

    private JudgmentMaterialDto judgment(String basis, HandoffRepository.JudgmentRow row) {
        return new JudgmentMaterialDto(basis, row.evaluationId(), row.legalStatus(), row.manualStatus(), row.reviewState(),
                row.planMatchCode(), row.planId(), row.planNo(), row.grade(), row.score(), row.freshnessCode(),
                codes(row.violationReasons()), codes(row.unknownReasons()), row.decisionAssuranceCode(),
                millis(row.observedAt()), millis(row.evaluatedAt()), row.ruleSetVersionId());
    }

    /** 研判原因码是 JSON 字符串数组；读不出来就省略，不把半截内容冻进卷宗。 */
    private List<String> codes(String raw) {
        if (raw == null) return null;
        try {
            JsonNode node = json.readTree(raw);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isArray() || node.isEmpty()) return null;
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) { if (!item.isTextual()) return null; values.add(item.textValue()); }
            return List.copyOf(values);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * 证据链清单，与事件页“证据链”同一查询（证据台账按事件主体取）。文件补上 sha256，处罚部门可以与证据台账逐项比对。
     * 查到零条时保留空数组：那是“查过了，本案没有关联证据”这个事实。
     */
    private List<EvidenceChainItemDto> evidenceChain(String eventId, EvidenceScope scope) {
        long now = clock.nowMillis();
        List<EvidenceLedgerRepository.LedgerRow> rows = new ArrayList<>();
        for (String category : EVIDENCE_CATEGORIES) {
            var query = new EvidenceLedgerRepository.Query(category, null, null, "EVENT", eventId, null, null, null);
            rows.addAll(ledger.list(ledger.relation(query, scope.decision(), false, scope.tracks(), scope.commands(), now), 0, EVIDENCE_PER_CATEGORY));
        }
        Map<String, String> digests = repository.evidenceDigests(rows.stream()
                .filter(row -> "FILE".equals(row.sourceKind())).map(EvidenceLedgerRepository.LedgerRow::sourceId).toList());
        return rows.stream()
                .sorted(Comparator.comparing((EvidenceLedgerRepository.LedgerRow row) -> occurredAt(row), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(EvidenceLedgerRepository.LedgerRow::sourceKind).thenComparing(EvidenceLedgerRepository.LedgerRow::sourceId))
                .map(row -> new EvidenceChainItemDto(row.category(), row.sourceKind(), row.sourceId(), row.evidenceNo(), row.originalName(),
                        row.kindCode(), row.status(), row.capturedAt(), row.startedAt(), row.endedAt(), row.pointCount(), row.sizeBytes(),
                        "FILE".equals(row.sourceKind()) ? digests.get(row.sourceId()) : null))
                .toList();
    }

    private static Long occurredAt(EvidenceLedgerRepository.LedgerRow row) {
        return row.capturedAt() != null ? row.capturedAt() : row.storedAt();
    }

    /** 设备测算的遥控器位置，按提交时目标的最新状态冻结；没有就省略，由页面写明"没有遥控器位置"。 */
    private PilotLocationMaterialDto pilotLocation(HandoffRepository.EventMaterialRow event) {
        if (event == null || event.targetId() == null) return null;
        var fix = targets.pilotFix(event.targetId());
        return fix == null ? null : new PilotLocationMaterialDto(fix.location().longitude(), fix.location().latitude(),
                millis(fix.observedAt()), "DEVICE_ESTIMATE");
    }

    /** 事件所属告警的 source_mode（决策 14-22）；取不到时按 mock 处理，绝不冒充 live。 */
    public String sourceMode(String eventId) {
        HandoffRepository.EventMaterialRow event = repository.eventMaterial(eventId);
        return event == null || event.sourceMode() == null ? "mock" : event.sourceMode();
    }

    /**
     * 空列表一律转成 null（键被 non_null 序列化省略）。
     *
     * 只对 `verifications` / `disposals` 这样"按前提必然非空"的段这么做——它们为空只可能是取数出了问题。
     * `evidence` **不走这条**：调用者有权限时查出零条，是"查过了，本案没有关联证据"这个真实结论，
     * 与任何前提都不矛盾；把它也省略掉反而抹掉了一个有用的事实。缺权限那种情况由 `evidence_omitted` 表达。
     */
    private static <T> List<T> emptyToNull(List<T> items) {
        return items == null || items.isEmpty() ? null : items;
    }

    private static Long millis(OffsetDateTime at) { return at == null ? null : at.toInstant().toEpochMilli(); }
}
