package com.uav.lowaltitude.modules.flight.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightVerificationRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** 核实结论是独立事实，不修改计划执行状态、不生成处置告警。 */
@Service
public class FlightVerificationService {
    private final FlightReadRepository plans;
    private final FlightVerificationRepository records;
    private final FlightActualsService actuals;
    private final AccessControlService access;
    private final IdempotencyGuard idempotency;
    private final AppClock clock;
    private final AuditService audit;
    private final HandoffChannelPort channel;
    private final ObjectMapper json;
    private final FlightDeviceCheckService deviceChecks;
    public FlightVerificationService(FlightReadRepository plans,FlightVerificationRepository records,FlightActualsService actuals,
            AccessControlService access,IdempotencyGuard idempotency,AppClock clock,AuditService audit,HandoffChannelPort channel,ObjectMapper json,FlightDeviceCheckService deviceChecks) {
        this.plans=plans;this.records=records;this.actuals=actuals;this.access=access;this.idempotency=idempotency;
        this.clock=clock;this.audit=audit;this.channel=channel;this.json=json;this.deviceChecks=deviceChecks;
    }
    @Transactional(readOnly=true)
    public Workflow read(String planId) {
        PlanRow plan=plan(planId);
        var history=records.verifications(plan.planId());
        String blocker=blocker(plan);
        boolean write=allowed(PermissionCode.FLIGHT_VERIFY);
        return new Workflow(plan.planId(),history.isEmpty()?0:history.get(0).revisionNo(),plan.sourceId(),plan.sourceName(),
            write && blocker==null,!write?"没有计划核实权限":blocker,allowed(PermissionCode.HANDOFF_CREATE),history,records.feedback(plan.planId()));
    }
    @Transactional
    public Verification verify(String planId,VerifyRequest request,String key) {
        access.require(PermissionCode.FLIGHT_VERIFY);
        plan(planId);
        throw conflict("MANUAL_VERIFICATION_DISABLED","检查结果由系统生成，是否起飞请由报送单位确认");
    }
    public FlightDeviceCheckService.Check deviceCheck(String planId) {
        PlanRow plan=plan(planId);
        boolean preflight=Set.of("PENDING","APPROVED").contains(plan.statusCode()) && plan.startAt()!=null
            && plan.startAt().toInstant().isAfter(clock.now());
        String blocked=preflight?null:blocker(plan);
        if(blocked!=null)throw conflict("VERIFICATION_NOT_AVAILABLE",blocked);
        return deviceChecks.read(planId);
    }
    @Transactional
    public Verification automatic(String planId,AutomaticRequest request,String key) {
        access.require(PermissionCode.FLIGHT_VERIFY);
        PlanRow plan=plan(planId);records.lockPlan(plan.planId());plan=plan(planId);
        String blocked=blocker(plan);
        if(blocked!=null)throw conflict("VERIFICATION_NOT_AVAILABLE",blocked);
        if(request==null || request.expectedRevision()==null)throw invalid("请刷新计划后再提交");
        idempotency.claim(key,"plan-auto-check:"+plan.planId()+":"+writeJson(request));
        var history=records.verifications(plan.planId());long previous=history.isEmpty()?0:history.get(0).revisionNo();
        if(request.expectedRevision()!=previous)throw conflict("VERSION_CONFLICT","检查记录已更新，请刷新后重试");
        var check=deviceChecks.read(planId);
        var actor=AuthContext.require();
        StringBuilder evidence=new StringBuilder("系统检查时间：").append(displayTime(check.checkedAt()))
            .append("；航线周边 ").append(check.nearbyMeters()).append(" 米；检查 ").append(check.rows().size()).append(" 台设备。");
        if(check.mqttSimulation())evidence.append("\n数据来源：本地 MQTT 模拟设备，不是现场监测结果。");
        for(var row:check.rows()) {
            evidence.append("\n").append(row.name()).append(row.simulated()?"（演示设备）":"")
                .append("，距航线 ").append(row.distanceM().setScale(0,java.math.RoundingMode.HALF_UP)).append(" 米，")
                .append(deviceLabel(row.connectivity())).append("，运行情况：").append(deviceLabel(row.healthCode()))
                .append("，最近上报：").append(displayTime(row.lastHeartbeatAt()));
            for(var incident:row.incidents())evidence.append("\n告警：").append(incident.reason()).append("；发生时间：")
                .append(displayTime(incident.detectedAt())).append("；结束时间：").append(incident.closedAt()==null?"尚未关闭":displayTime(incident.closedAt()));
        }
        if(!check.complete())evidence.append("\n检查信息不完整，不能据此排除设备异常。");
        if(check.uncheckedLocations()>0)evidence.append("\n另有 ").append(check.uncheckedLocations()).append(" 台设备的位置无法判断。");
        Verification record=new Verification(UUID.randomUUID().toString(),plan.planId(),previous+1,check.conclusion(),
            "UNKNOWN",evidence.toString(),check.message(),actor.userId(),actor.name(),clock.nowMillis());
        records.insert(record);audit("plan_device_checked",record.verificationId(),"plan_id="+plan.planId()+"; conclusion="+record.conclusion());
        return record;
    }
    @Transactional
    public Feedback feedback(String planId,FeedbackRequest request,String key) {
        access.require(PermissionCode.HANDOFF_CREATE);
        PlanRow plan=plan(planId);records.lockPlan(plan.planId());plan=plan(planId);
        var history=records.verifications(plan.planId());
        if(request==null || history.isEmpty() || !history.get(0).verificationId().equals(request.verificationId()))
            throw conflict("VERIFICATION_REQUIRED","请先完成并选择最新核实记录");
        if(plan.sourceId()==null || !plan.sourceId().equals(request.recipientId()) || plan.sourceName()==null || !records.sourceEnabled(plan.sourceId()))
            throw conflict("PLAN_RECIPIENT_UNAVAILABLE","接收方必须是该计划已登记且启用的来源，不能使用风险通知默认接收方");
        idempotency.claim(key,"plan-feedback:"+plan.planId()+":"+writeJson(request));
        if(records.feedback(plan.planId()).stream().anyMatch(f->f.verificationId().equals(request.verificationId())))
            throw conflict("FEEDBACK_ALREADY_EXISTS","这条核实结论已提交通知，请查看回告记录");
        Verification verification=history.get(0);String id=UUID.randomUUID().toString();
        String snapshot=writeJson(Map.of("plan_id",plan.planId(),"plan_no",plan.planNo(),"verification",verification,
            "recipient_id",plan.sourceId(),"recipient_name",plan.sourceName()));
        OffsetDateTime at=clock.now().atOffset(ZoneOffset.UTC);
        // 未有真实来源回告适配器时不借用风险通知通道冒充送达；模拟渠道也不得处理 live 计划。
        DeliveryOutcome outcome="live".equals(plan.sourceMode()) && channel.simulated()?DeliveryOutcome.notConnected():
            channel.deliver(new HandoffDispatch(id,"PLAN_VERIFICATION",verification.verificationId(),"PLAN_FEEDBACK",plan.sourceId(),plan.sourceName(),snapshot,at));
        if(outcome==null)outcome=DeliveryOutcome.notConnected();
        Feedback feedback=new Feedback(id,verification.verificationId(),plan.planId(),plan.sourceId(),plan.sourceName(),
            outcome.deliveryStatus(),outcome.receiptStatus(),outcome.receiptResult(),outcome.blockedReason(),clock.nowMillis(),
            millis(outcome.submittedAt()),millis(outcome.deliveredAt()),millis(outcome.acknowledgedAt()));
        records.insert(feedback,snapshot,AuthContext.require().userId());audit("plan_feedback_submitted",id,"plan_id="+plan.planId()+"; verification_id="+verification.verificationId());
        return feedback;
    }
    private PlanRow plan(String id) {
        var decision=access.require(PermissionCode.FLIGHT_READ);
        PlanRow plan=plans.findPlan(FlightActualsService.identifier(id),decision);
        if(plan==null)throw new ApiException(HttpStatus.NOT_FOUND,"FLIGHT_PLAN_NOT_FOUND","飞行计划不存在或不可见");
        return plan;
    }
    private String blocker(PlanRow plan) {
        if("CANCELLED".equals(plan.statusCode()))return "已取消计划不进入未起飞核实";
        if(plan.startAt()==null || plan.startAt().toInstant().isAfter(clock.now()))return "尚未到计划开始时间，不能核实未按计划起飞";
        var match=actuals.actuals(plan.planId()).match();
        if("FORBIDDEN".equals(match.availability()))return "没有实际对照查看权限，不能确认是否需要核实";
        if(!Set.of("AVAILABLE","NO_EVALUATION").contains(match.availability()))return "实际对照暂不可用";
        if(match.targetId()!=null)return "已经关联感知目标，请查看实际轨迹与研判";
        return null;
    }
    private boolean allowed(PermissionCode permission) {try{access.require(permission);return true;}catch(ApiException ex){if(ex.getStatus()!=HttpStatus.FORBIDDEN)throw ex;return false;}}
    private void audit(String action,String id,String detail) {var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),actor.roleCode(),"flight",action,"flight_verification",id,detail,"SUCCESS","","");}
    private String writeJson(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("核实材料序列化失败",ex);}}
    private static Long millis(OffsetDateTime value){return value==null?null:value.toInstant().toEpochMilli();}
    private static String displayTime(Long value) {
        return value==null?"未记录":java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
            .withZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.Instant.ofEpochMilli(value));
    }
    private static String deviceLabel(String code) {
        return code==null?"未知":switch(code) {
            case "ONLINE" -> "在线";case "OFFLINE" -> "离线";case "ABNORMAL","BAD" -> "异常";
            case "DEGRADED" -> "运行不稳定";case "GOOD" -> "正常";case "DISABLED" -> "已停用";default -> "未知";
        };
    }
    private static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
