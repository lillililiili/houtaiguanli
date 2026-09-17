package com.uav.lowaltitude.modules.alarm.application;

import java.util.Set;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;

/** 两种通知共享当前事实与时效校验；渠道送达历史各自防重，人工联系阻断两者。 */
@Service
public class AdvisoryEligibilityService {
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    private final AutoSmsRepository tasks;
    private final UavAdvisoryRepository records;
    private final AutoSmsPolicy policy;
    private final ObjectMapper json;
    public AdvisoryEligibilityService(AutoSmsRepository tasks,UavAdvisoryRepository records,AutoSmsPolicy policy,ObjectMapper json,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory) {
        this.directory=directory;
        this.tasks=tasks;this.records=records;this.policy=policy;this.json=json;
    }
    public Eligibility evaluate(EventRow event,long now,String channel) {
        if("FALSE_POSITIVE".equals(event.state()))return blocked("事件已排除为误报",null,null);
        if(!Set.of("CONFIRMED","PENDING_VERIFICATION").contains(event.state()))return blocked("事件仍待补充证据，暂停自动发送",null,null);
        Facts facts;
        try {facts=tasks.facts(event.eventId());} catch(org.springframework.dao.EmptyResultDataAccessException inactive){return blocked("事件所属机构或区域不可用",null,null);}
        Evaluation evaluation=tasks.latestEvaluation(event.eventId());
        Long observed=facts.observedAt();
        if(!policy.fresh(facts.receivedAt(),now,policy.eventMillis()))return blocked("事件已超过自动通知时效；"+policy.description(),evaluation,observed);
        if(!"UAV".equals(facts.objectType()))return blocked("目标类型尚未确认为无人机，不能仅凭身份识别猜测违规",evaluation,observed);
        if(!policy.fresh(observed,now,policy.freshMillis()))return blocked("缺少近期目标观测，不能确认目标仍在场；"+policy.description(),evaluation,observed);
        var history=records.records(event.eventId());
        var observation=history.stream().filter(r->"OBSERVATION".equals(r.kind())).reduce((a,b)->b).orElse(null);
        if(observation!=null&&!"STILL_INSIDE".equals(observation.outcome()))return blocked("DEPARTED".equals(observation.outcome())?"现场核查确认已飞离，不再发送":"现场核查结果不明，暂停自动发送",evaluation,observed);
        if(history.stream().anyMatch(r->blocksChannel(r.kind(),channel)))return blocked("已有本渠道通知或人工联系记录，后台不重复自动通知",evaluation,observed);
        String source;
        if(evaluation!=null) {
            if(!"ILLEGAL".equals(evaluation.legalStatus())||!"FRESH".equals(evaluation.freshness())||!emptyReasons(evaluation.unknowns())
                    ||!policy.fresh(evaluation.evaluatedAt(),now,policy.freshMillis())||!policy.fresh(evaluation.observedAt(),now,policy.freshMillis()))
                return blocked("最新规则结果不是时效内明确违规，不能自动通知；"+policy.description(),evaluation,observed);
            if(!event.alarmId().equals(evaluation.alarmId()))return blocked("最新违规研判未关联本事件，不能代替本事件依据",evaluation,observed);
            source="RULE_ILLEGAL";
        } else {
            if(!"CONFIRMED".equals(event.state())||!policy.fresh(facts.confirmedAt(),now,policy.eventMillis()))return blocked("尚无明确违规规则结果或近期人工确认依据",null,observed);
            source="MANUAL_CONFIRMATION";
        }
        var recipient=directory.forPilotEvent("VOICE_SIMULATED".equals(channel)?"ADVISORY_VOICE":"ADVISORY_SMS",event.eventId(),evaluation==null?null:evaluation.id());
        if(!recipient.configured())return blocked(recipient.blockedReason(),evaluation,observed);
        return new Eligibility(true,"WAITING","触发条件满足，等待后台按渠道通知；"+policy.description(),source,evaluation,observed);
    }
    private boolean blocksChannel(String kind,String channel) {
        if("OBSERVATION".equals(kind))return false;
        if(Set.of("SMS_SIMULATED","VOICE_SIMULATED").contains(kind))return kind.equals(channel);
        return true;
    }
    private boolean emptyReasons(String value) {try {var n=json.readTree(value);if(n.isTextual())n=json.readTree(n.asText());return n.isArray()&&n.isEmpty();}catch(Exception bad){return false;}}
    private Eligibility blocked(String reason,Evaluation evaluation,Long observed){return new Eligibility(false,"BLOCKED",reason,null,evaluation,observed);}
    public record Eligibility(boolean allowed,String status,String reason,String source,Evaluation evaluation,Long observedAt) { }
}
