package com.uav.lowaltitude.modules.handoff.application;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DisposalMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventVerificationDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EvidenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialV2Dto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;

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

    private final HandoffRepository repository;

    public HandoffMaterialAssembler(HandoffRepository repository) { this.repository = repository; }

    /**
     * @param includeEvidence 证据段是否纳入。由调用方按**提交人当时**的 evidence:read 决定；
     *        为 false 时整段省略并置 `evidence_omitted=true`，**不留空数组**——
     *        "没有权限看"和"本案没有证据"是两件完全不同的事，混同会让读卷宗的人得出相反结论（决策 14-4）。
     */
    public MaterialV2Dto assemble(String eventId, boolean includeEvidence) {
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
                        millis(d.validUntil()), d.resultCode(), d.resultDetail(), millis(d.completedAt())))
                .toList();
        List<EvidenceMaterialDto> evidence = includeEvidence
                ? repository.eventEvidence(eventId).stream()
                        .map(e -> new EvidenceMaterialDto(e.evidenceId(), e.evidenceNo(), e.kindCode(), e.sha256(),
                                millis(e.capturedAt()), e.status()))
                        .toList()
                : null;
        ReferenceMaterialDto references = event == null || event.targetId() == null ? null
                : new ReferenceMaterialDto(null, null, null, event.targetId(), null);
        // 取不到就省略键，不落空数组（决策 14-28 补充）。空数组是在**断言"没有"**：
        // `verifications: []` 等于说"这个事件从没被核实过"，而它的状态是 CONFIRMED；
        // `disposals: []` 等于说"从没处置过"，而交接能存在的前提正是有一条 COMPLETED 授权。
        // 两者都会让读卷宗的人得出与事实相反的结论，比缺一段更糟。
        return new MaterialV2Dto(SCHEMA_V2, eventDto, emptyToNull(verifications), emptyToNull(disposals), evidence,
                includeEvidence ? null : Boolean.TRUE, references);
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
