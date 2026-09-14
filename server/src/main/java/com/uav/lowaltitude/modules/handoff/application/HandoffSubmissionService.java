package com.uav.lowaltitude.modules.handoff.application;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.CreateRequest;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.CreatedDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialV2Dto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EventVerificationDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DisposalMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EvidenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RiskMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.VerificationMaterialDto;
import com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.DeliveryInsert;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.HandoffInsert;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.RecipientRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.application.RiskNotificationService;
import com.uav.lowaltitude.modules.risk.application.RiskReadService;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.VerificationRow;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class HandoffSubmissionService {
    private static final Set<String> BODY_FIELDS = Set.of("source_kind", "source_id", "handoff_type", "recipient_id", "expected_version");
    private static final int HISTORY_LIMIT = 1000;
    /** 处罚交接的材料形状版本（决策 14-1）；v1 的风险形状原样保留。 */
    public static final int MATERIAL_SCHEMA_V2 = HandoffMaterialAssembler.SCHEMA_V2;
    private final AccessControlService access;
    private final HandoffRepository repository;
    private final RiskRepository risks;
    private final RiskReadService riskRead;
    private final IdempotencyGuard idempotency;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final DisposalCompletionPort disposals;
    private final UavEventRepository events;
    private final HandoffMaterialAssembler materials;
    private final RiskNotificationService notifications;

    public HandoffSubmissionService(AccessControlService access, HandoffRepository repository, RiskRepository risks, RiskReadService riskRead,
            IdempotencyGuard idempotency, AppClock clock, AuditService audit, ObjectMapper objectMapper,
            DisposalCompletionPort disposals, UavEventRepository events, HandoffMaterialAssembler materials, HandoffChannelPort channel,
            RiskNotificationService notifications) {
        this.channel = channel;
        this.access = access; this.repository = repository; this.risks = risks; this.riskRead = riskRead;
        this.idempotency = idempotency; this.clock = clock; this.audit = audit; this.objectMapper = objectMapper;
        this.disposals = disposals;
        this.events = events;
        this.materials = materials;
        this.notifications = notifications;
    }

    /**
     * 事务顺序：鉴权 → 解析 → 锁源风险 → claim 幂等键 → 版本/状态/接收方/逻辑唯一检查 → 交接头 + 快照 + 首条投递 + 成功审计。
     * 任何一步失败整体回滚；失败审计由全局异常处理在事务外单独落库，不能放在这里——它会和业务写入一起被回滚而丢失留痕。
     */
    @Transactional
    public CreatedDto create(String rawRequest, String key) {
        // 两个动作权限都先于请求体、ID、对象与幂等解析：缺任一权限统一 403，不给探测 400/404 差异的机会。
        access.require(PermissionCode.HANDOFF_CREATE);
        // 预解析**只取 source_kind**，用来决定该向调用者要哪一种来源读权；解析失败不在这里报错。
        // 阶段 5 起有一条安全性质：缺权限的人拿畸形请求体来必须先吃 403 而不是 400，
        // 否则没有读权的人能靠错误码差异试探请求体校验规则。所以严格解析排在鉴权之后。
        boolean uavEvent = HandoffRules.KIND_UAV_EVENT.equals(previewSourceKind(rawRequest));
        // 读权必须先于前提校验（决策 14-21 修订）：`completedExists` 不带范围过滤，
        // 若前提排在前面，只有 handoff:create 的人就能用任意事件 id 靠 409/403 的差异跨机构探测
        // "那边有没有处置完成的事件"。先要权限，探测面就关掉了。
        AccessDecision riskDecision = access.require(uavEvent ? PermissionCode.ALARM_READ : PermissionCode.RISK_READ);
        CreateRequest request = parse(rawRequest);
        String sourceId = HandoffReadService.id(request.sourceId());
        // 接收方可缺省（18-14），所以只在有值时才校验格式；缺省的那条路由 resolveRecipient 决定。
        String recipientId = request.recipientId() == null || request.recipientId().isBlank()
                ? null : HandoffReadService.id(request.recipientId());
        // 前提与组合校验保住既有答复：(RISK, UAV_PUNISHMENT) 恒 409、(UAV_EVENT, RISK_NOTICE) 恒 400。
        HandoffRules.requirePrerequisite(request.handoffType(), request.sourceKind(), sourceId, disposals);
        HandoffRules.requireKindSupportsType(request.sourceKind(), request.handoffType());
        if (uavEvent) return createFromEvent(request, sourceId, recipientId, riskDecision, key);
        RiskRow risk = risks.lock(sourceId, riskDecision);
        if (risk == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "源对象不存在或不可见");
        // 幂等键与逻辑唯一同时存在：幂等键只识别“同一个客户端请求的重放”，逻辑唯一才保证“不同人、不同键”对同一事项只落一份交接。
        idempotency.claim(key, operation(request.sourceKind(), sourceId, request.handoffType(), recipientId, request.expectedVersion()));
        if (risk.version() != request.expectedVersion()) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "风险已被其他操作更新");
        HandoffRules.requireNotifiable(risk.state());
        if (!repository.anyEnabledRecipient(request.handoffType())) {
            throw new ApiException(HttpStatus.CONFLICT, "RECIPIENT_NOT_CONFIGURED", "尚未配置可用的交接接收方");
        }
        RecipientRow recipient = resolveRecipient(recipientId, request.handoffType());
        recipientId = recipient.recipientId();
        // 源风险已加锁，这里的预检对同一风险是可靠的；数据库唯一约束仍是最终保障。
        if (repository.logicalExists(request.sourceKind(), sourceId, request.handoffType(), recipientId)) throw alreadyExists();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        AuthUser actor = AuthContext.require();
        String handoffId = UUID.randomUUID().toString();
        MaterialDto material = material(risk, riskDecision);
        try {
            repository.insertHandoff(new HandoffInsert(handoffId, request.sourceKind(), sourceId, sourceId, null, request.handoffType(),
                    recipientId, risk.version(), risk.ownerOrgId(), risk.districtId(), risk.sourceMode(), actor.userId(), at));
        } catch (DuplicateKeyException ex) {
            throw alreadyExists();
        }
        String snapshot = json(material);
        repository.insertSnapshot(handoffId, HandoffRules.SNAPSHOT_SCHEMA_VERSION, snapshot, at);
        // 提交不等于送达：首条投递记录写渠道返回的事实（未接通=待投递+阻断原因；模拟/真实上级接口=送达与回执时刻）。
        DeliveryOutcome outcome = dispatch(handoffId, request.sourceKind(), sourceId, request.handoffType(), recipient, snapshot, at);
        repository.insertDelivery(delivery(handoffId, outcome, at));
        if (outcome.receiptResult() != null) repository.updateReceiptResult(handoffId, outcome.receiptResult());
        String riskState = "";
        if (HandoffRules.TYPE_RISK_NOTICE.equals(request.handoffType())) {
            notifications.submitted(sourceId, risk.version(), handoffId, at);
            riskState = "; risk_state=NOTIFIED";
            boolean trustAck = "ACKNOWLEDGED".equals(outcome.receiptStatus())
                    && !("live".equals(risk.sourceMode()) && channel.simulated());
            if (trustAck) {
                notifications.acknowledged(sourceId, risks.currentVersion(sourceId), handoffId, at);
                riskState = "; risk_state=ACKNOWLEDGED";
            }
        }
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "handoff", "handoff_created", "handoff", handoffId,
                "source_kind=" + request.sourceKind() + "; source_id=" + sourceId + "; handoff_type=" + request.handoffType()
                        + "; recipient_id=" + recipientId + "; source_version=" + risk.version()
                        + riskState, "SUCCESS", "", "");
        return new CreatedDto(handoffId, request.sourceKind(), sourceId, request.handoffType(), recipientId, risk.version(),
                outcome.deliveryStatus(), outcome.receiptStatus(), outcome.receiptResult(), outcome.blockedReason(),
                at.toInstant().toEpochMilli());
    }

    /**
     * 处罚交接（UAV_EVENT 来源）。顺序与风险来源一致：锁事件 → 幂等 → 版本 → 状态 → 接收方 → 逻辑唯一 → 写入。
     *
     * 快照冻结的是"提交那一刻的事实"（决策 14-2）：事件与告警、全部核实历史、该事件的全部终态授权、
     * 提交人当时可见的证据引用。之后源怎么变都不影响这份材料——处罚要凭它说明当时依据了什么。
     */
    private CreatedDto createFromEvent(CreateRequest request, String sourceId, String recipientId,
                                       AccessDecision alarmDecision, String key) {
        // 锁事件但不改它：锁只用来串行化同一事件的并发提交并冻结版本核对；提交交接不是事件状态迁移。
        UavEventRepository.EventRow event = events.lock(sourceId, alarmDecision);
        if (event == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "源对象不存在或不可见");
        idempotency.claim(key, operation(request.sourceKind(), sourceId, request.handoffType(), recipientId, request.expectedVersion()));
        if (event.version() != request.expectedVersion())
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已被其他操作更新");
        // 未核实的事件不能移送处罚：处罚要"事实清楚"，而"待核实"恰恰是事实还没清楚（决策 14-3）。
        if (!"CONFIRMED".equals(event.state()))
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "只有已核实为属实的事件可以提交处罚交接");
        if (!repository.anyEnabledRecipient(request.handoffType()))
            throw new ApiException(HttpStatus.CONFLICT, "RECIPIENT_NOT_CONFIGURED", "尚未配置可用的交接接收方");
        RecipientRow recipient = resolveRecipient(recipientId, request.handoffType());
        recipientId = recipient.recipientId();
        if (repository.logicalExists(request.sourceKind(), sourceId, request.handoffType(), recipientId)) throw alreadyExists();

        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        AuthUser actor = AuthContext.require();
        String handoffId = UUID.randomUUID().toString();
        MaterialV2Dto material = eventMaterial(sourceId);
        try {
            repository.insertHandoff(new HandoffInsert(handoffId, request.sourceKind(), sourceId, null, sourceId,
                    request.handoffType(), recipientId, event.version(), event.ownerOrgId(), event.districtId(),
                    // source_mode 取告警的（决策 14-22），与快照 event.source_mode 同源：
                    // 写死 live 会让一条 mock 数据造出来的交接在库里冒充真实来源。
                    materials.sourceMode(sourceId), actor.userId(), at));
        } catch (DuplicateKeyException ex) {
            throw alreadyExists();
        }
        String snapshot = json(material);
        repository.insertSnapshot(handoffId, MATERIAL_SCHEMA_V2, snapshot, at);
        DeliveryOutcome outcome = dispatch(handoffId, request.sourceKind(), sourceId, request.handoffType(), recipient, snapshot, at);
        repository.insertDelivery(delivery(handoffId, outcome, at));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "handoff", "handoff_created", "handoff", handoffId,
                "source_kind=" + request.sourceKind() + "; source_id=" + sourceId + "; handoff_type=" + request.handoffType()
                        + "; recipient_id=" + recipientId + "; source_version=" + event.version(), "SUCCESS", "", "");
        if (outcome.receiptResult() != null) repository.updateReceiptResult(handoffId, outcome.receiptResult());
        return new CreatedDto(handoffId, request.sourceKind(), sourceId, request.handoffType(), recipientId, event.version(),
                outcome.deliveryStatus(), outcome.receiptStatus(), outcome.receiptResult(), outcome.blockedReason(),
                at.toInstant().toEpochMilli());
    }

    /**
     * 快照 v2 组装委托给共用组件（决策 14-28），服务与种子用同一套——
     * 种子手写 JSON 壳子会在库里落下空 disposals/verifications 的假材料，而页面照样渲染。
     * 证据段按**提交人当时**的 evidence:read 裁剪。
     */
    private MaterialV2Dto eventMaterial(String eventId) {
        boolean mayReadEvidence = true;
        try { access.require(PermissionCode.EVIDENCE_READ); }
        catch (ApiException denied) { mayReadEvidence = false; }
        return materials.assemble(eventId, mayReadEvidence);
    }


    /**
     * 只取 source_kind 的宽松预解析：此时还没要来源读权，不能因为请求体有问题就先报 400。
     * 取不到（畸形、缺字段、类型不对）时返回 null，调用方按风险来源要权限——那是历史默认，
     * 也保证"看不出请求体有没有问题"。
     */
    private String previewSourceKind(String rawRequest) {
        if (rawRequest == null || rawRequest.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(rawRequest);
            if (node == null || !node.isObject()) return null;
            com.fasterxml.jackson.databind.JsonNode kind = node.get("source_kind");
            return kind == null || !kind.isTextual() ? null : kind.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 白名单快照：风险安全字段、核实历史、提交者当时可见的关联引用。没有文件就没有任何文件字段。 */
    private MaterialDto material(RiskRow risk, AccessDecision riskDecision) {
        RiskDto visible = riskRead.dto(risk);
        List<VerificationMaterialDto> history = risks.verifications(risk.riskId(), riskDecision, 0, HISTORY_LIMIT).stream()
                .map(HandoffSubmissionService::verification).toList();
        ReferenceMaterialDto references = new ReferenceMaterialDto(visible.planId(), visible.routeVersionId(), visible.assessmentId(),
                visible.targetId(), visible.trackId());
        RiskMaterialDto material = new RiskMaterialDto(risk.riskId(), risk.sourceRiskId(), risk.riskType(), risk.severity(), risk.state(),
                risk.reasonCode(), risk.reasonText(), risk.occurredAt() == null ? null : risk.occurredAt().toInstant().toEpochMilli(),
                risk.receivedAt().toInstant().toEpochMilli(), risk.version());
        return new MaterialDto(HandoffRules.SNAPSHOT_SCHEMA_VERSION, material, history, empty(references) ? null : references);
    }

    private static boolean empty(ReferenceMaterialDto references) {
        return references.planId() == null && references.routeVersionId() == null && references.assessmentId() == null
                && references.targetId() == null && references.trackId() == null;
    }

    private static VerificationMaterialDto verification(VerificationRow row) {
        return new VerificationMaterialDto(row.conclusion(), row.note(), row.resultingState(), row.version(),
                row.createdAt().toInstant().toEpochMilli(), row.actorId());
    }

    private String json(Object material) {
        try { return objectMapper.writeValueAsString(material); }
        catch (JsonProcessingException ex) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误"); }
    }

    private CreateRequest parse(String rawRequest) {
        if (rawRequest == null || rawRequest.isBlank()) throw invalidRequest();
        try (JsonParser parser = objectMapper.getFactory().createParser(rawRequest)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            // 夹带 delivery_status/receipt_status/delivered_at 等任何非契约字段：客户端不能自行推进投递或回执。
            node.fieldNames().forEachRemaining(name -> { if (!BODY_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name); });
            // recipient_id 可缺省（决策 18-14：风险通知取默认接收方），其余四项仍必填。
            // 白名单一步没放松：未知字段照样 UNKNOWN_FIELD，只是"字段齐不齐"改成逐个必填项检查，
            // 而不是数个数——数个数的写法在"某一项可选"之后就表达不了"少了哪个"。
            for (String field : new String[]{"source_kind", "source_id", "handoff_type"}) {
                JsonNode value = node.get(field);
                if (value == null || !value.isTextual()) throw invalidRequest();
            }
            JsonNode recipientNode = node.get("recipient_id");
            if (recipientNode != null && !recipientNode.isTextual()) throw invalidRequest();
            // 原来靠"字段个数相等"兜住它的存在，现在 recipient_id 可缺省，这里必须显式判空——
            // 否则少传 expected_version 会变成 NPE(500) 而不是 400。
            JsonNode version = node.get("expected_version");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) throw invalidRequest();
            String kind = node.get("source_kind").textValue().trim(), type = node.get("handoff_type").textValue().trim();
            if (!HandoffRules.SOURCE_KINDS.contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "source_kind 无效");
            if (!HandoffRules.HANDOFF_TYPES.contains(type)) throw invalidRequest();
            return new CreateRequest(kind, node.get("source_id").textValue(), type,
                    recipientNode == null ? null : recipientNode.textValue(), version.longValue());
        } catch (java.io.IOException ex) {
            throw invalidRequest();
        }
    }

    private static String operation(String kind, String sourceId, String type, String recipientId, long expected) {
        // 长度前缀序列化：ID 可含分隔符，直接拼接会把不同请求误判为 replay。
        // 接收方可缺省（决策 18-14）：缺省时以空串入键。ID 本身不可能是空串，所以"没传接收方"和"传了某个接收方"不会撞同一个键。
        return framed("handoff") + framed(kind) + framed(sourceId) + framed(type)
                + framed(recipientId == null ? "" : recipientId) + framed(Long.toString(expected));
    }
    private static String framed(String value) { return value.getBytes(StandardCharsets.UTF_8).length + ":" + value; }
    private static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体无效"); }
    /**
     * 定下这次交接发给谁（决策 18-14）。
     *
     * 风险通知不传接收方时取该类型的默认接收方——上级就那一个，每次让值班员选一遍既慢又容易选错。
     * **处罚移送不给默认**：移送给谁是案件的一部分，默认一个等于替办案人做主。
     * 一个默认都没有又没传，答 400 而不是随手挑一个：挑出来的未必是该收的人，
     * 而这种错要等回执回来（甚至更久）才看得出来。
     */
    private RecipientRow resolveRecipient(String recipientId, String handoffType) {
        if (recipientId != null && !recipientId.isBlank()) {
            RecipientRow chosen = repository.findEnabledRecipient(recipientId, handoffType);
            if (chosen == null) throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND", "接收方不存在或不可用");
            return chosen;
        }
        if (!HandoffRules.TYPE_RISK_NOTICE.equals(handoffType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECIPIENT_REQUIRED", "请指定接收方");
        }
        RecipientRow fallback = repository.findDefaultRecipient(handoffType);
        if (fallback != null) return fallback;
        // 没标默认，但该类型只有一个启用接收方（决策 18-16）：只有一个的时候没有可选的余地，
        // 再要求值班员显式指定就是让他把唯一的答案抄一遍。零个或多个才是真的没法替他决定。
        RecipientRow sole = repository.findSoleEnabledRecipient(handoffType);
        if (sole != null) return sole;
        throw new ApiException(HttpStatus.BAD_REQUEST, "RECIPIENT_REQUIRED", "未配置默认接收方，请指定接收方");
    }

    private static ApiException alreadyExists() { return new ApiException(HttpStatus.CONFLICT, "HANDOFF_ALREADY_EXISTS", "该事项已向此接收方提交过交接"); }

    private final HandoffChannelPort channel;

    /** 渠道异常不吞：交接与快照已入库，投递记录如实写"待投递 · 未接通"，由后续人工或重试处理。 */
    private DeliveryOutcome dispatch(String handoffId, String sourceKind, String sourceId, String handoffType, RecipientRow recipient,
            String snapshot, OffsetDateTime at) {
        try {
            DeliveryOutcome outcome = channel.deliver(new HandoffDispatch(handoffId, sourceKind, sourceId, handoffType,
                    recipient.recipientId(), recipient.displayName(), snapshot, at));
            if (outcome == null || !HandoffRules.DELIVERY_STATUSES.contains(outcome.deliveryStatus())
                    || !HandoffRules.RECEIPT_STATUSES.contains(outcome.receiptStatus())) return DeliveryOutcome.notConnected();
            return outcome;
        } catch (RuntimeException ex) {
            return DeliveryOutcome.notConnected();
        }
    }

    private static DeliveryInsert delivery(String handoffId, DeliveryOutcome outcome, OffsetDateTime at) {
        return new DeliveryInsert(UUID.randomUUID().toString(), handoffId, 1, outcome.deliveryStatus(), outcome.receiptStatus(),
                outcome.blockedReason(), at, outcome.submittedAt(), outcome.deliveredAt(), outcome.acknowledgedAt());
    }
}
