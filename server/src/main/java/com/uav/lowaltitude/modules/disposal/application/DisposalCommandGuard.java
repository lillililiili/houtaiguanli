package com.uav.lowaltitude.modules.disposal.application;

import java.util.Set;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** 设备启动前检查当前系统依据，并与急停事务串行；外部协议授权保持既有兼容。 */
@Component
public class DisposalCommandGuard {
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity cases){this.riskCases=cases;}
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    private final EmergencyStopRepository stops;
    private final DisposalRepository authorizations;
    private final UavAdvisoryRepository advisory;
    private final DirectDisposalAccess directAccess;
    private final AppClock clock;

    public DisposalCommandGuard(EmergencyStopRepository stops, DisposalRepository authorizations,
                                UavAdvisoryRepository advisory, DirectDisposalAccess directAccess, AppClock clock) {
        this.stops = stops;
        this.authorizations = authorizations;
        this.advisory = advisory;
        this.directAccess = directAccess;
        this.clock = clock;
    }

    public boolean mayStart(String authorizationId) {
        var current = authorizations.findUnlocked(authorizationId);
        String executor = current == null ? null : "DIRECT".equals(current.authorizationMode())
                ? current.requestedBy() : authorizations.latestExecuteActor(authorizationId);
        return mayStart(authorizationId, executor, current == null ? null : current.deviceId());
    }

    /** The actor comes from the enqueue caller or the persisted command, never from the applicant. */
    public boolean mayStart(String authorizationId, String executorUserId, String deviceId) {
        return startBlockReason(authorizationId, executorUserId, deviceId).isEmpty();
    }
    /** Return the reason from the same locked check; do not re-read later to guess why a send was cancelled. */
    public String startBlockReason(String authorizationId, String executorUserId, String deviceId) {
        return riskCases == null ? startBlockReasonScoped(authorizationId, executorUserId, deviceId)
                : riskCases.withAuthorization(authorizationId, () -> startBlockReasonScoped(authorizationId, executorUserId, deviceId));
    }
    private String startBlockReasonScoped(String authorizationId,String executorUserId,String deviceId){
        var initial = authorizations.findUnlocked(authorizationId);
        // 旧自动接续任务也不能从队列再次启动；停止指令不经过 mayStart。
        if (initial != null && "JAMMING".equals(initial.actionType()) && stops.parent(authorizationId) != null)
            return "旧自动接续授权不能再次启动";
        if (initial != null && "UAV_EVENT".equals(initial.subjectKind())) {
            if(riskCases!=null)riskCases.lockCases(initial.subjectId());
            if (predictions != null) {
                predictions.lockEpisode(initial.subjectId());
                if (com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal.USER_ID.equals(initial.requestedBy())
                        && predictions.blocksAutomatic("counter",initial.subjectId())) return "预测衍生风险必须由有权限人员人工发起反制";
            }
            stops.lockEvent(initial.subjectId());
            if (Set.of("COUNTERMEASURE", "JAMMING").contains(initial.actionType())) {
                String reason = advisory.counterBlockReason(initial.subjectId());
                if (!reason.isEmpty()) return reason;
            }
        }
        String status = stops.lockAuthorizationStatus(authorizationId);
        if ("STOPPED".equals(status)) return "本次授权已停止，不能重新启动";
        if (status != null && stops.covered(authorizationId)) return "本次授权已被急停覆盖，不能重新启动";
        // 取得授权锁后重新读，排队期间发生的过期、停用、撤权和范围变化必须在实际发送前生效。
        var current = authorizations.findUnlocked(authorizationId);
        if (current != null) {
            if (!java.util.Objects.equals(current.deviceId(), deviceId)) return "启动设备与本次授权不一致";
            var now = clock.now().atOffset(ZoneOffset.UTC);
            if (!Set.of("APPROVED", "EXECUTING").contains(current.status())) return "本次授权当前状态不允许启动";
            if (current.validFrom() == null || current.validUntil() == null
                    || now.isBefore(current.validFrom()) || !now.isBefore(current.validUntil())) return "当前时间不在本次授权有效期内";
            // 设备锁仍按 事件→授权→设备→指令 的顺序取，与手动执行、续链和急停互斥。
            // 2026-10-14 起急停后不再登记现场停机核查：同一设备上别的授权排队中的启动不再因急停被拦，
            // 被急停覆盖或已停止的授权仍按上面的授权状态挡下。
            if (current.deviceId() != null) stops.lockDevice(current.deviceId());
        }
        if (current != null && "DIRECT".equals(current.authorizationMode())) {
            if (status == null || !Set.of("APPROVED", "EXECUTING").contains(status)) return "本次授权当前状态不允许启动";
            if (!current.requestedBy().equals(executorUserId)) return "直接反制必须由本次授权的发起人执行";
            return directAccess.eligibleRequester(current, true) != null ? "" : "发起人的当前权限、数据范围或设备操作资格不满足本次授权";
        }
        return directAccess.eligibleExecutor(current, executorUserId, deviceId) != null ? "" : "执行人的当前权限、数据范围或设备操作资格不满足本次授权";
    }
}
