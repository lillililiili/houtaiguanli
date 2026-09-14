package com.uav.lowaltitude.modules.disposal.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.ActionResultDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.CreateRequest;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.CreatedDto;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 处置授权写侧。
 *
 * 事务顺序（全仓统一）：鉴权 → 严格解析请求体 → 锁行 → claim 幂等键 → 核对 expected_version →
 * 业务规则 → 写入 → 成功审计（同事务）。失败审计由全局异常处理在事务外落库，不能放进来——
 * 它会跟着业务写入一起回滚，等于失败没留痕。
 */
@Service
public class DisposalAuthorizationService {
    private static final Set<String> CREATE_FIELDS = Set.of("action_type", "subject_kind", "subject_id",
            "device_id", "channel", "reason");
    private static final Set<String> DECISION_FIELDS = Set.of("expected_version", "note");
    private static final Set<String> EXECUTE_FIELDS = Set.of("expected_version", "operation_params");
    private static final Set<String> MANUAL_FIELDS = Set.of("expected_version", "result", "detail");

    private final AccessControlService access;
    private final DisposalRepository repository;
    private final DisposalPolicyRepository policies;
    private final UavEventRepository events;
    private final DisposalExecutionGateway gateway;
    private final DisposalJammingChain jammingChain;
    private final IdempotencyGuard idempotency;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;

    public DisposalAuthorizationService(AccessControlService access, DisposalRepository repository,
            DisposalPolicyRepository policies, UavEventRepository events, DisposalExecutionGateway gateway,
            DisposalJammingChain jammingChain, IdempotencyGuard idempotency, AppClock clock, AuditService audit,
            ObjectMapper json) {
        this.access = access; this.repository = repository; this.policies = policies; this.events = events;
        this.gateway = gateway; this.jammingChain = jammingChain; this.idempotency = idempotency; this.clock = clock;
        this.audit = audit; this.json = json;
    }

    /* ---- 申请 ---- */

    @Transactional
    public CreatedDto create(String rawRequest, String key) {
        // 申请权与主体读权都先于请求体解析：缺任一权限统一 403，不给人靠 400/404 的差异探测系统里有什么。
        access.require(PermissionCode.DISPOSAL_REQUEST);
        CreateRequest request = parseCreate(rawRequest);
        DisposalRules.requireKnown(request.actionType(), DisposalRules.ACTION_TYPES, "处置动作类型无效");
        // 风险单独给码（决策 18-14）：它不在主体名单里，但落进泛泛的"类型无效"就把一句能读懂的话
        // ——"风险请走通知上级"——换成了值班员看不懂的校验错误。答复保持与之前一致。
        if ("RISK".equals(request.subjectKind())) throw riskSubjectNotSupported();
        DisposalRules.requireKnown(request.subjectKind(), DisposalRules.SUBJECT_KINDS, "处置主体类型无效");
        DisposalRules.requireKnown(request.channel(), DisposalRules.CHANNELS, "执行通道无效");
        String subjectId = text(request.subjectId(), "subject_id");
        String reason = reason(request.reason());
        if (!DisposalRules.MANUAL.equals(request.channel()) && blank(request.deviceId()))
            throw bad("经设备执行的处置必须指定设备");

        DisposalPolicy policy = policies.active();
        Subject subject = resolveSubject(request.subjectKind(), subjectId, request.actionType(), policy);
        // 一律用**解析后**的主体 ID：目标经 target_current_alias 并入当前航迹后，旧 ID 与新 ID 指的是同一件事。
        // 若按调用方递进来的旧 ID 落库，"这个目标有没有未了结的授权"就会查不到自己，
        // 并发上限形同虚设，态势页也看不到已经批出去的处置。
        String effectiveSubjectId = subject.subjectId();
        idempotency.claim(key, "disposal:create:" + request.subjectKind() + ":" + effectiveSubjectId + ":" + request.actionType());
        // 并发上限按主体+动作计：同一架无人机不该同时挂着两份还没了结的反制授权。
        if (repository.activeCount(request.subjectKind(), effectiveSubjectId, request.actionType()) >= policy.maxActivePerSubject())
            throw conflict("ACTIVE_AUTHORIZATION_EXISTS", "该主体已有未了结的同类处置授权");

        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String id = UUID.randomUUID().toString();
        String no = DisposalRules.authorizationNo(dayKey(at), repository.nextSequence(dayKey(at)));
        // approval_required=false 的策略可以直接成 APPROVED，但那必须是策略的明示决定，不是代码里的默认。
        String status = policy.approvalRequired() ? DisposalRules.REQUESTED : DisposalRules.APPROVED;
        repository.insert(new AuthorizationInsert(id, no, request.actionType(), request.subjectKind(), effectiveSubjectId,
                subject.targetId(), blank(request.deviceId()) ? null : request.deviceId().trim(), request.channel(),
                reason, actor.userId(), at, status, policy.policyCode(), subject.ownerOrgId(), subject.districtId(),
                subject.sourceMode()));
        event(id, "REQUEST", actor.userId(), reason, Map.of("status", status, "action_type", request.actionType(),
                "channel", request.channel(), "policy_version", policy.policyCode()), at);
        audit(actor, "disposal_requested", id, "authorization_no=" + no + "; action_type=" + request.actionType()
                + "; subject=" + request.subjectKind() + "/" + effectiveSubjectId + "; channel=" + request.channel());
        return new CreatedDto(id, no, status, 0L);
    }

    /* ---- 审批 ---- */

    @Transactional
    public ActionResultDto approve(String id, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_APPROVE);
        Decision body = parseDecision(rawRequest, false);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:approve:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.APPROVE, row.status());
        DisposalPolicy policy = policies.active();
        AuthUser actor = AuthContext.require();
        DisposalRules.requireTwoPerson(policy, row.requestedBy(), actor.userId());
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime until = at.plusMinutes(policy.timeLimitMinutes(row.actionType()));
        if (repository.approve(id, row.version(), actor.userId(), at, at, until, body.note()) != 1) throw versionConflict();
        event(id, "APPROVE", actor.userId(), body.note(), Map.of("status", DisposalRules.APPROVED,
                "valid_from", at.toInstant().toEpochMilli(), "valid_until", until.toInstant().toEpochMilli()), at);
        audit(actor, "disposal_approved", id, "authorization_no=" + row.authorizationNo()
                + "; valid_until=" + until.toInstant().toEpochMilli());
        return result(id, DisposalRules.APPROVED, row.version() + 1, null, null);
    }

    @Transactional
    public ActionResultDto reject(String id, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_APPROVE);
        Decision body = parseDecision(rawRequest, true);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:reject:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.REJECT, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.reject(id, row.version(), actor.userId(), at, body.note()) != 1) throw versionConflict();
        event(id, "REJECT", actor.userId(), body.note(), Map.of("status", DisposalRules.REJECTED), at);
        audit(actor, "disposal_rejected", id, "authorization_no=" + row.authorizationNo());
        return result(id, DisposalRules.REJECTED, row.version() + 1, null, null);
    }

    /* ---- 执行 ---- */

    @Transactional
    public ExecuteOutcome execute(String id, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_EXECUTE);
        Execute body = parseExecute(rawRequest);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:execute:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.EXECUTE, row.status());
        long now = clock.nowMillis();
        DisposalRules.requireWithinWindow(now, millis(row.validFrom()), millis(row.validUntil()));
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);

        if (DisposalRules.MANUAL.equals(row.channel())) {
            if (repository.transition(id, row.version(), DisposalRules.EXECUTING, at, null, null, null) != 1)
                throw versionConflict();
            event(id, "EXECUTE", actor.userId(), "人工执行", Map.of("status", DisposalRules.EXECUTING,
                    "channel", DisposalRules.MANUAL), at);
            audit(actor, "disposal_executed", id, "authorization_no=" + row.authorizationNo() + "; channel=MANUAL");
            return ExecuteOutcome.accepted(new ActionResultDto(id, DisposalRules.EXECUTING, row.version() + 1,
                    null, null, null));
        }

        DisposalPolicy policy = policies.active();
        // 四通道走 A 的原生 TCP 设置；凌云 B 仍经 enqueue，自校验 devices.op（决策 13-9）。
        DisposalExecutionGateway.Result dispatched = DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())
                ? gateway.dispatch4ch(row.deviceId(), "disposal-" + id + "-" + row.version(),
                        id, row.actionType(), row.reason())
                : gateway.dispatch(row.deviceId(), "disposal-" + id + "-" + row.version(),
                        id, policy, row.actionType(), body.operationParams(), row.reason());
        if (dispatched instanceof DisposalExecutionGateway.Rejected rejected) {
            // 设备侧执行不了：授权保持 APPROVED、只记事件，不伪造回执、不推进状态（决策 13-3）。
            // 关键：**不能在这里抛**。@Transactional 下抛异常会把刚写的事件一起回滚，
            // 页面上就只剩一个错误码，而没有"何时、因何不可执行"的记录——那恰恰是这条路径唯一的产出。
            // 因此正常返回，由控制器在事务提交之后再抛 409。
            event(id, rejected.eventKind(), actor.userId(), rejected.detail(),
                    Map.of("status", row.status(), "channel", row.channel(), "device_id", nullSafe(row.deviceId())), at);
            audit(actor, "disposal_executed", id, "authorization_no=" + row.authorizationNo()
                    + "; channel=" + row.channel() + "; blocked=" + rejected.eventKind());
            return new ExecuteOutcome(new ActionResultDto(id, row.status(), row.version(), null, null,
                    rejected.eventKind()), rejected.errorCode(), rejected.detail());
        }
        String commandId = ((DisposalExecutionGateway.Accepted) dispatched).commandId();
        if (repository.transition(id, row.version(), DisposalRules.EXECUTING, at, commandId, null, null) != 1)
            throw versionConflict();
        event(id, "EXECUTE", actor.userId(), null, Map.of("status", DisposalRules.EXECUTING,
                "channel", row.channel(), "command_id", commandId), at);
        audit(actor, "disposal_executed", id, "authorization_no=" + row.authorizationNo()
                + "; channel=" + row.channel() + "; command_id=" + commandId);
        return ExecuteOutcome.accepted(new ActionResultDto(id, DisposalRules.EXECUTING, row.version() + 1,
                null, commandId, null));
    }

    @Transactional
    public ActionResultDto manualResult(String id, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_EXECUTE);
        Manual body = parseManual(rawRequest);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:manual-result:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.MANUAL_RESULT, row.status());
        // 协议 B 的结果只能来自设备回执：人不能替设备说"我成功了"，否则处罚案件的依据就成了自述。
        if (!DisposalRules.MANUAL.equals(row.channel()))
            throw conflict("INVALID_TRANSITION", "只有人工执行的授权才能登记人工结果");
        String status = "SUCCEEDED".equals(body.result()) ? DisposalRules.COMPLETED : DisposalRules.FAILED;
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.transition(id, row.version(), status, at, null, "MANUAL_" + body.result(), body.detail()) != 1)
            throw versionConflict();
        event(id, "MANUAL_RESULT", actor.userId(), body.detail(), Map.of("status", status, "result", body.result()), at);
        audit(actor, "disposal_manual_result", id, "authorization_no=" + row.authorizationNo()
                + "; result=" + body.result());
        if (DisposalRules.COMPLETED.equals(status) && DisposalRules.COUNTERMEASURE.equals(row.actionType())) {
            jammingChain.scheduleAfterComplete(id);
        }
        return result(id, status, row.version() + 1, null, "MANUAL_" + body.result());
    }

    /* ---- 停止与撤回 ---- */

    @Transactional
    public ActionResultDto stop(String id, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_STOP);
        Decision body = parseDecision(rawRequest, true);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:stop:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.STOP, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.transition(id, row.version(), DisposalRules.STOPPED, at, null, "STOPPED_BY_OPERATOR", body.note()) != 1)
            throw versionConflict();
        event(id, "STOP", actor.userId(), body.note(), Map.of("status", DisposalRules.STOPPED), at);
        // 撤销授权与停住设备是两件事。设备协议本期没有急停（A 的 emergency-stop 一律 CONTROL_NOT_ENABLED），
        // 所以这里如实记一条事件而不是假装停住了；撤销本身不因此受阻（决策 13-4）。
        String stopResult = DisposalRules.STOP_NOT_ATTEMPTED;
        if (DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())) {
            DisposalExecutionGateway.Result stopped = gateway.stop4ch(actor, row.deviceId(),
                    "disposal-stop-" + id + "-" + row.version(), id, row.reason());
            if (stopped instanceof DisposalExecutionGateway.Accepted) {
                event(id, "DEVICE_ALL_OFF_ISSUED", actor.userId(), "已向四通道网络控制器下发全关，回执以设备为准",
                        Map.of("device_id", nullSafe(row.deviceId()),
                                "command_id", ((DisposalExecutionGateway.Accepted) stopped).commandId()), at);
                stopResult = DisposalRules.STOP_ALL_OFF_ISSUED;
            } else {
                DisposalExecutionGateway.Rejected rejected = (DisposalExecutionGateway.Rejected) stopped;
                event(id, rejected.eventKind(), actor.userId(), rejected.detail(),
                        Map.of("device_id", nullSafe(row.deviceId())), at);
                stopResult = DisposalRules.STOP_UNAVAILABLE;
            }
        } else if (DisposalRules.LINGYUN_B.equals(row.channel())) {
            if (!gateway.bound(row.deviceId())) {
                event(id, DisposalExecutionGateway.EVENT_NOT_BOUND, actor.userId(),
                        "该设备未登记凌云 MQTT，无法尝试停止", Map.of("device_id", nullSafe(row.deviceId())), at);
                stopResult = DisposalRules.STOP_NOT_BOUND;
            } else {
                event(id, "DEVICE_STOP_UNAVAILABLE", actor.userId(), "设备协议未提供急停，授权已撤销但设备可能仍在动作",
                        Map.of("device_id", nullSafe(row.deviceId())), at);
                stopResult = DisposalRules.STOP_UNAVAILABLE;
            }
        } else {
            stopResult = DisposalRules.STOP_NOT_ATTEMPTED;
        }
        audit(actor, "disposal_stopped", id, "authorization_no=" + row.authorizationNo()
                + "; device_stop_result=" + stopResult);
        return new ActionResultDto(id, DisposalRules.STOPPED, row.version() + 1, stopResult, null,
                "STOPPED_BY_OPERATOR");
    }

    @Transactional
    public ActionResultDto cancel(String id, String rawRequest, String key) {
        // 撤回自己的申请只需申请权；替别人撤回才需要审批权。两者都不满足时按 403 拒绝。
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_REQUEST);
        Decision body = parseDecision(rawRequest, false);
        AuthorizationRow row = locked(id, decision);
        idempotency.claim(key, "disposal:cancel:" + id + ":" + body.expectedVersion());
        requireVersion(row, body.expectedVersion());
        DisposalRules.requireTransition(DisposalRules.CANCEL, row.status());
        AuthUser actor = AuthContext.require();
        if (!actor.userId().equals(row.requestedBy())) access.require(PermissionCode.DISPOSAL_APPROVE);
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.transition(id, row.version(), DisposalRules.CANCELLED, at, null, "CANCELLED_BY_REQUESTER", body.note()) != 1)
            throw versionConflict();
        event(id, "CANCEL", actor.userId(), body.note(), Map.of("status", DisposalRules.CANCELLED), at);
        audit(actor, "disposal_cancelled", id, "authorization_no=" + row.authorizationNo());
        return result(id, DisposalRules.CANCELLED, row.version() + 1, null, null);
    }

    /* ---- 主体解析 ---- */

    /** 授权的归属元组复制自主体：授权能被谁看见，与它针对的那个事项一致。 */
    private Subject resolveSubject(String kind, String subjectId, String actionType, DisposalPolicy policy) {
        if ("UAV_EVENT".equals(kind)) {
            AccessDecision alarmDecision = access.require(PermissionCode.ALARM_READ);
            // 锁主体但不改它：锁只用来串行化同一事件的并发申请，让下面的并发上限预检可靠。
            UavEventRepository.EventRow row = events.lock(subjectId, alarmDecision);
            if (row == null) throw notFound();
            // 未核实的事件不该被反制：先确认"确实是它"，再谈能不能动手（策略可关，但要明示）。
            if (policy.requiresConfirmedEvent(actionType) && !"CONFIRMED".equals(row.state()))
                throw conflict("POLICY_REQUIRES_CONFIRMED_EVENT", "该动作要求事件已核实为属实");
            return new Subject(subjectId, row.targetId(), row.ownerOrgId(), row.districtId(), row.sourceMode());
        }
        if ("TARGET".equals(kind)) {
            // 态势页的"派发驱离"直接对着目标发（决策 13-24）。驱离在 demo-v1 里不要求已核实事件，
            // 但目标必须存在、在范围内、且还"活着"——对一条早已消失的航迹派驱离没有意义，也无法交代。
            AccessDecision targetDecision = access.require(PermissionCode.TARGET_READ);
            DisposalRepository.TargetScope target = repository.targetScope(subjectId, targetDecision);
            if (target == null) throw notFound();
            if (policy.requiresConfirmedEvent(actionType)) {
                // 该动作要求"事件已核实"，而目标主体身上没有事件可查——不能因为换个主体类型就绕过这条。
                throw conflict("POLICY_REQUIRES_CONFIRMED_EVENT", "该动作要求先核实事件，请对事件发起而不是对目标");
            }
            requireActive(target);
            return new Subject(target.targetId(), target.targetId(), target.ownerOrgId(), target.districtId(),
                    target.sourceMode());
        }
        // 兜底：主体名单已在入口挡过一遍，走到这里说明名单里新添了一种主体却没在上面实现。
        // 与其抛内部错误，不如仍旧答那句能读懂的话。
        throw riskSubjectNotSupported();
    }

    private static ApiException riskSubjectNotSupported() {
        return new ApiException(HttpStatus.BAD_REQUEST, "SUBJECT_KIND_NOT_SUPPORTED",
                "本期只支持对已核实的无人机事件或目标发起处置授权");
    }

    /**
     * 目标要"还活着"才允许对它派处置：新鲜度阈值取生效规则集的 C03.fresh_seconds，与合法性引擎同一口径。
     * 取不到阈值时拒绝而不是放行——没有阈值就无法判断它是否还在，那种情况下批准动手是没人能交代的。
     */
    private void requireActive(DisposalRepository.TargetScope target) {
        Integer freshSeconds = repository.freshSeconds();
        if (freshSeconds == null)
            throw conflict("TARGET_NOT_ACTIVE", "尚未配置目标新鲜度阈值，无法确认该目标仍在活动");
        if (target.observedAt() == null)
            throw conflict("TARGET_NOT_ACTIVE", "该目标没有观测记录，无法确认它仍在活动");
        long ageSeconds = (clock.nowMillis() - target.observedAt().toInstant().toEpochMilli()) / 1000L;
        if (ageSeconds > freshSeconds)
            throw conflict("TARGET_NOT_ACTIVE", "该目标最近一次观测已超过 " + freshSeconds + " 秒，不能对它派发处置");
    }

    /** subjectId 是解析后的主体（目标可能经别名并入当前航迹）；targetId 是这次处置针对的目标。 */
    private record Subject(String subjectId, String targetId, String ownerOrgId, String districtId, String sourceMode) { }

    /* ---- 公共写入辅助 ---- */

    private AuthorizationRow locked(String id, AccessDecision decision) {
        AuthorizationRow row = repository.lock(id, decision);
        if (row == null) throw notFound();
        return row;
    }

    private void requireVersion(AuthorizationRow row, long expected) {
        if (row.version() != expected) throw versionConflict();
    }

    private void event(String authorizationId, String kind, String actorId, String note,
                       Map<String, Object> snapshot, OffsetDateTime at) {
        repository.insertEvent(UUID.randomUUID().toString(), authorizationId, kind, actorId, note, write(snapshot), at);
    }

    private void audit(AuthUser actor, String action, String objectId, String detail) {
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", action,
                "disposal_authorization", objectId, detail, "SUCCESS", "", "");
    }

    private static ActionResultDto result(String id, String status, long version, String commandId, String resultCode) {
        return new ActionResultDto(id, status, version, null, commandId, resultCode);
    }

    /** 执行结果：被设备侧拒绝时事务照常提交（事件要留痕），由控制器在提交后抛 409。 */
    public record ExecuteOutcome(ActionResultDto dto, String rejectedCode, String rejectedDetail) {
        static ExecuteOutcome accepted(ActionResultDto dto) { return new ExecuteOutcome(dto, null, null); }
        public boolean rejected() { return rejectedCode != null; }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize disposal event snapshot", ex); }
    }

    /* ---- 严格请求体解析：未知字段一律拒绝，避免"写了却没生效"的字段 ---- */

    private JsonNode strict(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) throw bad("请求体不能为空");
        final JsonNode node;
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            node = json.readTree(parser);
            if (parser.nextToken() != null) throw bad("请求体不是单个 JSON 对象");
        } catch (ApiException ex) { throw ex;
        } catch (Exception ex) { throw bad("请求体不是合法 JSON"); }
        if (node == null || !node.isObject()) throw bad("请求体不是 JSON 对象");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw bad("请求体含未知字段：" + name);
        });
        return node;
    }

    private CreateRequest parseCreate(String raw) {
        JsonNode node = strict(raw, CREATE_FIELDS);
        return new CreateRequest(str(node, "action_type"), str(node, "subject_kind"), str(node, "subject_id"),
                str(node, "device_id"), str(node, "channel"), str(node, "reason"));
    }

    private Decision parseDecision(String raw, boolean noteRequired) {
        JsonNode node = strict(raw, DECISION_FIELDS);
        String note = str(node, "note");
        if (noteRequired && blank(note)) throw bad("必须填写说明");
        if (note != null && note.length() > 500) throw bad("说明最长 500 个字符");
        return new Decision(version(node), note == null ? null : note.trim());
    }

    private Execute parseExecute(String raw) {
        JsonNode node = strict(raw, EXECUTE_FIELDS);
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode raw_params = node.get("operation_params");
        if (raw_params != null && !raw_params.isNull()) {
            if (!raw_params.isObject()) throw bad("operation_params 必须是对象");
            params = json.convertValue(raw_params, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        }
        return new Execute(version(node), params);
    }

    private Manual parseManual(String raw) {
        JsonNode node = strict(raw, MANUAL_FIELDS);
        String result = str(node, "result");
        if (!"SUCCEEDED".equals(result) && !"FAILED".equals(result)) throw bad("人工结果只能是成功或失败");
        String detail = str(node, "detail");
        if (blank(detail)) throw bad("必须填写人工执行结果说明");
        if (detail.length() > 500) throw bad("说明最长 500 个字符");
        return new Manual(version(node), result, detail.trim());
    }

    private long version(JsonNode node) {
        JsonNode value = node.get("expected_version");
        if (value == null || !value.canConvertToLong() || value.asLong() < 0) throw bad("expected_version 必填且不能为负");
        return value.asLong();
    }

    private static String str(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String text(String value, String field) {
        if (blank(value)) throw bad(field + " 必填");
        return value.trim();
    }

    private static String reason(String value) {
        String reason = text(value, "reason");
        if (reason.length() < 2 || reason.length() > 500) throw bad("处置理由长度必须为 2–500 个字符");
        return reason;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullSafe(String value) { return value == null ? "" : value; }
    private static Long millis(OffsetDateTime at) { return at == null ? null : at.toInstant().toEpochMilli(); }
    private static String dayKey(OffsetDateTime at) {
        return String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "该授权已被其他操作更新");
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "授权不存在或不可见");
    }

    private record Decision(long expectedVersion, String note) { }
    private record Execute(long expectedVersion, Map<String, Object> operationParams) { }
    private record Manual(long expectedVersion, String result, String detail) { }
}
