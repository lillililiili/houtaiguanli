package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Action;

@Repository
public class UavAdvisoryRepository {
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity cases){this.riskCases=cases;}
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    private final JdbcTemplate jdbc;
    private final NoCounterRepository noCounter;
    private final AutoSmsRepository facts;
    private final com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository disposal;
    private final com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository policies;
    private final com.uav.lowaltitude.platform.time.AppClock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory;
    public UavAdvisoryRepository(JdbcTemplate jdbc,com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory,
            AutoSmsRepository facts,com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository disposal,
            com.uav.lowaltitude.platform.time.AppClock clock,com.fasterxml.jackson.databind.ObjectMapper json,NoCounterRepository noCounter,
            com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository policies) {
        this.policies=policies;
        this.noCounter=noCounter;
        this.jdbc=jdbc;this.directory=directory;this.facts=facts;this.disposal=disposal;this.clock=clock;this.json=json;
    }
    /** 内部受控读取：调用方先完成事件范围校验或已授权材料组装。 */
    public List<Record> records(String eventId) {
        String selected=riskCases==null?null:riskCases.caseId(eventId);
        List<String> ids=selected==null?List.of(eventId):riskCases.eventIds(selected);
        if(ids.isEmpty())return List.of();
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        var params=new java.util.ArrayList<Object>(ids);
        String smsWhere="a.event_id IN ("+marks+")",voiceWhere="v.event_id IN ("+marks+")";
        if(selected!=null){smsWhere+=" AND "+recordScope("a","SMS");params.add(selected);params.add(selected);}
        params.addAll(ids);
        if(selected!=null){voiceWhere+=" AND "+recordScope("v","VOICE");params.add(selected);params.add(selected);}
        String sql="SELECT a.record_id,a.kind,a.created_at,COALESCE(a.actor_label,u.name) AS actor_name,a.recipient_name,a.contact_basis,a.content,a.outcome,a.danger,a.note,a.urgent,a.simulated,a.delivery_status,a.trigger_mode,a.policy_code,a.recipient_snapshot,a.event_version FROM uav_event_advisory a LEFT JOIN app_user u ON u.user_id=a.actor_id WHERE "+smsWhere
                +" UNION ALL SELECT v.record_id,'VOICE_SIMULATED',v.created_at,'自动电话录音服务',COALESCE(v.recipient_name,'演示飞手'),CASE WHEN v.recipient_snapshot IS NULL THEN '本地演示接收端，不对应真实手机号' ELSE '关联任务执行飞手；模拟通道' END,v.recording_name || '：' || v.transcript,NULL,NULL,NULL,FALSE,TRUE,v.delivery_status,'AUTO',v.policy_code,v.recipient_snapshot,v.event_version FROM uav_event_voice_advisory v WHERE "+voiceWhere+" ORDER BY created_at,record_id";
        return jdbc.query(sql,(r,n)->new Record(r.getString("record_id"),r.getString("kind"),r.getLong("created_at"),r.getString("actor_name"),r.getString("recipient_name"),r.getString("contact_basis"),r.getString("content"),r.getString("outcome"),r.getString("danger"),r.getString("note"),r.getBoolean("urgent"),r.getBoolean("simulated"),r.getString("delivery_status"),r.getString("trigger_mode"),r.getString("policy_code"),directory.decodeSnapshot(r.getString("recipient_snapshot"))),params.toArray());
    }
    private static String recordScope(String alias,String channel){
        return "("+alias+".risk_case_id=? OR ("+alias+".risk_case_id IS NULL AND (EXISTS(SELECT 1 FROM uav_risk_channel c WHERE c.risk_case_id=? AND c.channel='"+channel+"' AND c.event_id="+alias+".event_id AND c.task_scope='') OR (SELECT COUNT(*) FROM uav_risk_case_member m JOIN uav_risk_case c ON c.risk_case_id=m.risk_case_id WHERE m.event_id="+alias+".event_id AND c.superseded_by IS NULL)=1)))";
    }
    /** 调用方须先完成数据范围校验；动作执行链还须先锁定关联事件。 */
    public String counterBlockReason(String eventId) {
        if(noCounter.active(eventId))return NoCounterRepository.ACTIVE_REASON;
        if(predictions!=null) {
            String block=predictions.counterBlock(eventId,true);
            if(block!=null)return block;
        }
        var event=jdbc.queryForMap("SELECT state_code,alarm_id FROM uav_event WHERE event_id=?",eventId);
        AutoSmsRepository.Facts current;
        try { current=facts.facts(eventId); }
        catch (org.springframework.dao.EmptyResultDataAccessException missing) { return "事件所属机构或区域不可用"; }
        var evaluation=facts.latestEvaluation(eventId);
        boolean sufficient=false,noUnknowns=false;
        if(evaluation!=null) {
            sufficient=Boolean.TRUE.equals(jdbc.queryForObject("SELECT CASE WHEN decision_assurance_code='SUFFICIENT' AND decision_algorithm_version IS NOT NULL AND decision_algorithm_version<>'' THEN TRUE ELSE FALSE END FROM rule_evaluation WHERE evaluation_id=?",Boolean.class,evaluation.id()));
            try {
                var reasons=json.readTree(evaluation.unknowns());
                if(reasons.isTextual()) reasons=json.readTree(reasons.asText());
                noUnknowns=com.uav.lowaltitude.modules.alarm.domain.UavAdvisoryRules.noBlockingUnknowns(reasons);
            } catch(Exception invalid) { noUnknowns=false; }
        }
        return com.uav.lowaltitude.modules.alarm.domain.UavAdvisoryRules.counterBlockReason(
                riskCases!=null&&riskCases.hasCases(eventId)?(riskCases.confirmedForDisposal(eventId)?"CONFIRMED":"PENDING_VERIFICATION"):(String)event.get("state_code"),(String)event.get("alarm_id"),current,evaluation,
                sufficient,noUnknowns,disposal.freshSeconds(),clock.nowMillis());
    }
    /** 仅用于新建反制入口；执行已有授权仍单独检查 counterBlockReason，不能被自身授权阻断。 */
    public String counterLaunchBlockReason(String eventId) {
        if(predictions!=null) {
            String block=predictions.counterBlock(eventId,false);
            if(block!=null&&!block.isEmpty())return block;
            if(predictions.hasOtherActiveCounter(eventId,null))return "同次风险已有未了结的反制授权，请查看关联处置进度";
        }
        long active=disposal.activeCount("UAV_EVENT",eventId,"COUNTERMEASURE");
        if(active>0 && active>=policies.active().maxActivePerSubject())
            return "该告警已有未了结的反制授权，请查看当前处置进度";
        return "";
    }
    public boolean noCounterActive(String eventId) { return noCounter.active(eventId); }
    public void append(String id, String eventId, long version, String actor, long at, Action a, boolean simulated, String delivery) {
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,outcome,danger,note,urgent,simulated,delivery_status,risk_case_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,eventId,version,a.kind(),at,actor,a.recipientName(),a.contactBasis(),a.content(),a.outcome(),a.danger(),a.note(),Boolean.TRUE.equals(a.urgent()),simulated,delivery,riskCases==null?null:riskCases.caseId(eventId));
    }
    public void appendAutomatic(String id,String eventId,long version,long at,String content,String policy) {
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status,trigger_mode,actor_label,policy_code,risk_case_id) VALUES(?,?,?,'SMS_SIMULATED',?,NULL,'演示飞手','本地演示接收端，不对应真实手机号',?,FALSE,TRUE,'SIMULATED_DELIVERED','AUTO','自动短信服务',?,?)",id,eventId,version,at,content,policy,riskCases==null?null:riskCases.caseId(eventId));
    }
    public Replay replay(String actor, String key) {
        var rows=jdbc.query("SELECT request_hash,event_id,response_text FROM uav_event_advisory_request WHERE actor_id=? AND request_key=?",(r,n)->new Replay(r.getString(1),r.getString(2),r.getString(3)),actor,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void saveReplay(String actor,String key,String hash,String event,String response) {
        jdbc.update("INSERT INTO uav_event_advisory_request(actor_id,request_key,request_hash,event_id,response_text) VALUES(?,?,?,?,?)",actor,key,hash,event,response);
    }
    public record Replay(String hash,String eventId,String response) { }
}
