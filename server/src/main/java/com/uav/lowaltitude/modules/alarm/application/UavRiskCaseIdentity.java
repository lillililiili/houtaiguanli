package com.uav.lowaltitude.modules.alarm.application;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Persisted risk identities. A risk confirmation is disposal eligibility, never a legal verdict. */
@Service
public class UavRiskCaseIdentity {
    public static final String REQUEST_CASE="uav.risk.case";
    public static final String REQUEST_VERSION="uav.risk.version";
    private final JdbcTemplate jdbc;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    private final AppClock clock;
    private final boolean postgis;
    private boolean measuredQaAllowed,measuredSimulationAllowed;
    @org.springframework.beans.factory.annotation.Autowired
    public void simulation(com.uav.lowaltitude.platform.config.SimulationPolicy policy,org.springframework.core.env.Environment environment){
        measuredQaAllowed=policy.allowed();measuredSimulationAllowed=UavMeasuredRiskSql.realtimeSimulation(policy,environment);
    }
    private static final ThreadLocal<String> BACKGROUND_CASE=new ThreadLocal<>();
    public UavRiskCaseIdentity(JdbcTemplate jdbc,AppClock clock){this.jdbc=jdbc;this.namedJdbc=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);this.clock=clock;this.postgis=UavMeasuredRiskSql.postgis(jdbc);}
    public record CaseRef(String riskCaseId,String episodeId,String airspaceId,String stage,String actionEventId,
                          List<String> eventIds,boolean confirmed,boolean manualCounterOnly,long version) { }
    public static final String ACTION_EVENT="COALESCE((SELECT m.event_id FROM uav_risk_case_member m WHERE m.risk_case_id=c.risk_case_id ORDER BY CASE WHEN m.role='OBSERVED' THEN 0 ELSE 1 END,m.created_at DESC,m.event_id FETCH FIRST 1 ROW ONLY),c.root_event_id)";
    public static final String VERIFICATION="COALESCE((SELECT v.conclusion FROM uav_risk_case_verification v WHERE v.risk_case_id=c.risk_case_id ORDER BY v.case_version DESC FETCH FIRST 1 ROW ONLY),CASE WHEN (SELECT COUNT(*) FROM uav_risk_case_member vm JOIN uav_risk_case vc ON vc.risk_case_id=vm.risk_case_id WHERE vm.event_id=c.root_event_id AND vc.superseded_by IS NULL)=1 THEN root_event.state_code ELSE 'PENDING_VERIFICATION' END)";
    /** A legacy authorization is attributed only when its event has one proven current risk. */
    public static final String CASE_AUTHORIZATION = "SELECT 1 FROM disposal_authorization da WHERE da.subject_kind='UAV_EVENT'"
            + " AND da.owner_org_id=c.owner_org_id AND da.district_id=c.district_id AND da.source_mode=c.source_mode"
            + " AND da.action_type IN ('COUNTERMEASURE','JAMMING') AND da.channel<>'MANUAL'"
            + " AND (da.risk_case_id=c.risk_case_id OR (da.risk_case_id IS NULL"
            + " AND EXISTS(SELECT 1 FROM uav_risk_case_member am WHERE am.risk_case_id=c.risk_case_id AND am.event_id=da.subject_id)"
            + " AND (SELECT COUNT(*) FROM uav_risk_case_member am JOIN uav_risk_case ac ON ac.risk_case_id=am.risk_case_id"
            + " AND ac.superseded_by IS NULL WHERE am.event_id=da.subject_id)=1))";
    public static final String CASE_DEVICE_WORK_REMAINS = "(EXISTS(" + CASE_AUTHORIZATION + " AND da.status IN ('APPROVED','EXECUTING'))"
            + " OR EXISTS(" + CASE_AUTHORIZATION + " AND EXISTS(SELECT 1 FROM disposal_emergency_stop ps"
            + " JOIN disposal_emergency_stop_device pd ON pd.stop_id=ps.stop_id LEFT JOIN device_command pc ON pc.command_id=pd.command_id"
            + " WHERE pd.authorization_id=da.authorization_id AND ps.request_origin='SYSTEM_PREDICTION_STOP'"
            + " AND pd.stop_status<>'NOT_REQUIRED' AND (pc.command_id IS NULL OR pc.status<>'SUCCEEDED'))))";
    public static final String CASE_LATEST_ENTRY = "COALESCE((SELECT MAX(al.observed_at) FROM prediction_actual_entry_link al"
            + " JOIN uav_risk_case_member em ON em.event_id=al.event_id AND em.risk_case_id=c.risk_case_id"
            + " WHERE al.target_id=c.target_id AND al.airspace_id=c.airspace_id),c.created_at)";
    public static final String CASE_COMPLETED = "EXISTS(" + CASE_AUTHORIZATION + " AND da.status='COMPLETED'"
            + " AND CAST(EXTRACT(EPOCH FROM da.updated_at)*1000 AS BIGINT)>=" + CASE_LATEST_ENTRY
            + " AND NOT EXISTS(" + CASE_AUTHORIZATION.replace("da.","newer.").replace("authorization da", "authorization newer")
            + " AND (newer.requested_at>da.requested_at OR (newer.requested_at=da.requested_at AND newer.authorization_id>da.authorization_id))))";
    public static final String CASE_DEPARTED = "EXISTS(SELECT 1 FROM uav_auto_voice_task vt WHERE vt.departure_evaluated_at IS NOT NULL"
            + " AND vt.departure_evaluated_at<=:prediction_now AND vt.departure_observed_at>=" + CASE_LATEST_ENTRY
            + " AND (vt.risk_scope=c.risk_case_id OR (vt.risk_scope='' AND (SELECT COUNT(*) FROM uav_risk_case_member vcm JOIN uav_risk_case vcc USING(risk_case_id) WHERE vcm.event_id=vt.event_id AND vcc.superseded_by IS NULL)=1 AND EXISTS(SELECT 1 FROM uav_risk_channel vc WHERE vc.risk_case_id=c.risk_case_id"
            + " AND vc.event_id=vt.event_id AND vc.task_scope=vt.risk_scope))))";
    public static final String STAGE="CASE WHEN "+VERIFICATION+"='FALSE_POSITIVE' THEN 'ENDED'"
            + " WHEN p.status='CLEARED' THEN 'CLEARED'"
            + " WHEN "+CASE_DEPARTED+" THEN 'CLEARED' WHEN "+CASE_COMPLETED+" THEN 'ENDED'"
            + " WHEN c.airspace_id IS NOT NULL AND "+UavMeasuredRiskSql.TOKEN+" THEN 'ENTERED'"
            + " WHEN p.status='ACTIVE' AND p.current_evidence_valid=TRUE AND p.valid_until>:prediction_now THEN 'PREDICTED'"
            + " WHEN p.episode_id IS NOT NULL THEN 'UNKNOWN'"
            + " WHEN c.airspace_id IS NOT NULL THEN 'UNKNOWN' ELSE 'OBSERVED' END";
    public static final String JOINS=" LEFT JOIN uav_risk_case c ON c.superseded_by IS NULL AND "+ACTION_EVENT+"=e.event_id LEFT JOIN prediction_episode p ON p.episode_id=c.episode_id LEFT JOIN uav_event root_event ON root_event.event_id=c.root_event_id";

    public String selectedCase(){if(BACKGROUND_CASE.get()!=null)return BACKGROUND_CASE.get();var attrs=RequestContextHolder.getRequestAttributes();if(!(attrs instanceof ServletRequestAttributes r))return null;Object id=r.getRequest().getAttribute(REQUEST_CASE);if(id==null)id=r.getRequest().getParameter("risk_case_id");return id==null?null:id.toString();}
    public <T>T withCase(String id,java.util.function.Supplier<T> work){String previous=BACKGROUND_CASE.get();try{BACKGROUND_CASE.set(id);return work.get();}finally{if(previous==null)BACKGROUND_CASE.remove();else BACKGROUND_CASE.set(previous);}}
    public void runWithCase(String id,Runnable work){withCase(id,()->{work.run();return null;});}
    public <T>T withAuthorization(String authorizationId,java.util.function.Supplier<T> work){
        var rows=jdbc.queryForList("SELECT risk_case_id,subject_kind,subject_id FROM disposal_authorization WHERE authorization_id=?",authorizationId);
        if(rows.isEmpty()||!"UAV_EVENT".equals(rows.get(0).get("subject_kind")))return work.get();
        var row=rows.get(0);String caseId=(String)row.get("risk_case_id");
        if(caseId==null){var cases=allCases((String)row.get("subject_id"));if(cases.size()>1)throw conflict("RISK_CASE_REQUIRED","旧授权不能推断所属区域，请重新办理");if(cases.size()==1)caseId=cases.get(0).riskCaseId();}
        if(caseId!=null&&selectedCase()!=null&&!caseId.equals(selectedCase()))throw conflict("RISK_CASE_AUTHORIZATION_MISMATCH","授权不属于所选风险");
        return caseId==null?work.get():withCase(caseId,work);
    }
    public List<String> eventIds(String caseId){return jdbc.queryForList("SELECT event_id FROM uav_risk_case_member WHERE risk_case_id=? ORDER BY event_id",String.class,caseId);}
    public List<CaseRef> cases(String eventId){
        String selected=selectedCase();
        return allCases(eventId).stream().filter(c->selected==null||selected.equals(c.riskCaseId())).toList();
    }
    public List<CaseRef> allCases(String eventId){
        if(eventId==null)return List.of();
        var ids=jdbc.queryForList("SELECT c.risk_case_id FROM uav_risk_case c JOIN uav_risk_case_member m USING(risk_case_id) WHERE m.event_id=? AND c.superseded_by IS NULL ORDER BY c.risk_case_id",String.class,eventId);
        return ids.stream().map(this::find).filter(Objects::nonNull).toList();
    }
    public CaseRef find(String id){var row=details(id);if(row==null)return null;return new CaseRef(id,(String)row.get("episode_id"),(String)row.get("airspace_id"),(String)row.get("stage"),(String)row.get("action_event_id"),eventIds(id),"CONFIRMED".equals(row.get("verification_status")),row.get("episode_id")!=null,((Number)row.get("version")).longValue());}
    /** Identity/verification lookups must not evaluate current geometry or device work. */
    private List<Map<String,Object>> identityRows(String eventId,boolean verification) {
        if(eventId==null)return List.of();
        var parameters=new ArrayList<Object>();parameters.add(eventId);
        String selected=selectedCase(),scope="";
        if(selected!=null){scope=" AND c.risk_case_id=?";parameters.add(selected);}
        return jdbc.queryForList("SELECT c.risk_case_id,c.root_event_id"
                +(verification?","+VERIFICATION+" AS verification_status":"")
                +" FROM uav_risk_case c JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id"
                +" JOIN uav_event root_event ON root_event.event_id=c.root_event_id"
                +" WHERE m.event_id=? AND c.superseded_by IS NULL"+scope+" ORDER BY c.risk_case_id",parameters.toArray());
    }
    public String caseId(String eventId){var rows=identityRows(eventId,false);return rows.size()==1?(String)rows.get(0).get("risk_case_id"):null;}
    public boolean confirmedForDisposal(String eventId){var rows=identityRows(eventId,true);return !rows.isEmpty()&&rows.stream().allMatch(row->"CONFIRMED".equals(row.get("verification_status")));}
    public boolean pendingVerification(String eventId){var rows=identityRows(eventId,true);return rows.size()==1&&"PENDING_VERIFICATION".equals(rows.get(0).get("verification_status"));}
    public boolean hasCases(String eventId){return !identityRows(eventId,false).isEmpty();}
    public long caseCount(String eventId){return jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case c JOIN uav_risk_case_member m USING(risk_case_id) WHERE m.event_id=? AND c.superseded_by IS NULL",Long.class,eventId);}
    public Map<String,Object> details(String id){
        if(id==null)return null;
        var rows=detailRows(List.of(id));
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> detailRows(Collection<String> ids){
        for(int attempt=0;;attempt++) {
        var parameters=new HashMap<String,Object>();parameters.put("case_ids",ids);
        long now=UavMeasuredRiskSql.addTimes(parameters,jdbc,clock,measuredQaAllowed,measuredSimulationAllowed);
        parameters.put("prediction_now",now);
        var rows=namedJdbc.queryForList(
                UavMeasuredRiskSql.expand("SELECT c.*,"+ACTION_EVENT+" AS action_event_id,"+VERIFICATION+" AS verification_status,"+STAGE
                +" AS stage,p.latest_prediction_id,p.first_prediction_id,p.warning_json,p.valid_until,p.reason AS prediction_reason,"
                +"("+CASE_DEVICE_WORK_REMAINS+") AS device_work_remains FROM uav_risk_case c"
                +" LEFT JOIN prediction_episode p ON p.episode_id=c.episode_id JOIN uav_event root_event ON root_event.event_id=c.root_event_id"
                +" WHERE c.risk_case_id IN (:case_ids) AND c.superseded_by IS NULL",postgis),parameters);
        if(attempt<2&&rows.stream().anyMatch(row->"UNKNOWN".equals(row.get("stage")))&&frameCommittedAfterSample(ids,now))continue;
        return rows;
        }
    }

    /** A READ COMMITTED spatial SELECT may see a frame committed after its Java clock sample.
     * Only that proven race is re-read, with the full predicates and a new clock; future data,
     * expired evidence and exhausted retries still fail closed. No old eligibility is reused. */
    private boolean frameCommittedAfterSample(Collection<String> ids,long sampledAt){
        var parameters=new HashMap<String,Object>();parameters.put("case_ids",ids);
        var watermarks=namedJdbc.queryForList("SELECT s.observed_at,s.received_at,"
                +"(SELECT MAX(r.evaluated_at) FROM rule_evaluation r WHERE r.target_id=c.target_id"
                +" AND r.owner_org_id=c.owner_org_id AND r.district_id=c.district_id AND r.source_mode=c.source_mode AND r.mode='ACTIVE') AS evaluated_at"
                +" FROM uav_risk_case c LEFT JOIN target_latest_state s ON s.target_id=c.target_id"
                +" WHERE c.risk_case_id IN (:case_ids) AND c.superseded_by IS NULL",parameters);
        long latest=sampledAt;
        for(var row:watermarks)for(Object value:row.values())if(value instanceof java.sql.Timestamp stamp)latest=Math.max(latest,stamp.getTime());
        // Sample after the watermark query as well: reading the watermark can itself wait.
        return latest>sampledAt&&latest<=clock.nowMillis();
    }
    /** Lock episode first, then case, before event / authorization / device locks. */
    public void lockCases(String eventId){
        jdbc.queryForList("SELECT episode_id FROM prediction_episode WHERE episode_id IN(SELECT episode_id FROM prediction_event_link WHERE event_id=?) ORDER BY episode_id FOR UPDATE",eventId);
        var locked=jdbc.queryForList("SELECT c.risk_case_id,c.version FROM uav_risk_case c WHERE c.superseded_by IS NULL AND EXISTS(SELECT 1 FROM uav_risk_case_member m WHERE m.risk_case_id=c.risk_case_id AND m.event_id=?) ORDER BY c.risk_case_id FOR UPDATE",eventId);
        String selected=selectedCase();
        if(selected!=null){var c=locked.stream().filter(row->selected.equals(row.get("risk_case_id"))).findFirst().orElse(null);if(c==null)throw conflict("RISK_CASE_EVENT_MISMATCH","当前事件不属于所选风险，请刷新");
            // The locked identity/version rows already prove membership. Lock acquisition
            // must not run another live spatial assessment before its caller's action guard.
            if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs){Object expected=attrs.getRequest().getAttribute(REQUEST_VERSION);if(expected!=null&&((Number)c.get("version")).longValue()!=((Number)expected).longValue())throw conflict("RISK_CASE_VERSION_CONFLICT","风险阶段或办理状态已更新，请刷新");}}
    }
    public void bindAuthorization(String authorizationId,String eventId){
        lockCases(eventId);var rows=cases(eventId);if(rows.isEmpty())return;
        if(rows.size()!=1)throw conflict("RISK_CASE_REQUIRED","此事件涉及多个区域，请从对应风险办理反制");
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=? WHERE authorization_id=? AND risk_case_id IS NULL",rows.get(0).riskCaseId(),authorizationId);
    }
    /** Channel ownership is durable and independent of send success, so UNKNOWN is never retried on another event. */
    public boolean claimChannel(String eventId,String channel){
        lockCases(eventId);if(legacyNotificationAmbiguous(eventId,channel))return false;
        var cases=cases(eventId);for(var c:cases){
            if(!c.confirmed()||Set.of("CLEARED","UNKNOWN","ENDED").contains(c.stage()))return false;
            var owner=channelOwner(c.riskCaseId(),channel);if(owner!=null&&!owner.equals(eventId))return false;
        }
        for(var c:cases)if(channelOwner(c.riskCaseId(),channel)==null)jdbc.update("INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at) VALUES (?,?,?,?,?)",c.riskCaseId(),channel,eventId,taskScope(eventId,channel),clock.nowMillis());
        return true;
    }
    /** An attempted event-level legacy send cannot be allocated to multiple regions by guessing. */
    public boolean legacyNotificationAmbiguous(String eventId,String channel){
        if(!Set.of("SMS","VOICE").contains(channel))throw new IllegalArgumentException("Unknown notification channel");
        String table="SMS".equals(channel)?"uav_auto_sms_task":"uav_auto_voice_task";
        for(var selected:identityRows(eventId,false)){
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" t JOIN uav_risk_case_member mine ON mine.event_id=t.event_id"
                    +" WHERE mine.risk_case_id=? AND t.risk_scope='' AND t.attempt_count>0"
                    +" AND (SELECT COUNT(*) FROM uav_risk_case_member m JOIN uav_risk_case c ON c.risk_case_id=m.risk_case_id"
                    +" WHERE m.event_id=t.event_id AND c.superseded_by IS NULL)>1"
                    +" AND (SELECT COUNT(*) FROM uav_risk_channel ch JOIN uav_risk_case c ON c.risk_case_id=ch.risk_case_id"
                    +" JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id AND m.event_id=ch.event_id"
                    +" WHERE ch.event_id=t.event_id AND ch.channel=? AND ch.task_scope='' AND c.superseded_by IS NULL)<>1",
                    Long.class,selected.get("risk_case_id"),channel);
            if(count!=null&&count>0)return true;
        }
        return false;
    }
    public String channelOwner(String caseId,String channel){var rows=jdbc.queryForList("SELECT event_id FROM uav_risk_channel WHERE risk_case_id=? AND channel=?",String.class,caseId,channel);return rows.isEmpty()?null:rows.get(0);}
    /** Resolve before creating even a WAITING task; stage changes never allocate a second provider key. */
    public String notificationTaskOwner(String eventId,String channel){
        var rows=identityRows(eventId,false);if(rows.size()!=1)return eventId;
        var row=rows.get(0);String owner=channelOwner((String)row.get("risk_case_id"),channel);
        return owner==null?(String)row.get("root_event_id"):owner;
    }
    public String taskScope(String eventId,String channel){
        String caseId=caseId(eventId);if(caseId==null)return "";
        var scopes=jdbc.queryForList("SELECT task_scope FROM uav_risk_channel WHERE risk_case_id=? AND channel=?",String.class,caseId,channel);
        if(!scopes.isEmpty())return scopes.get(0);
        String table="SMS".equals(channel)?"uav_auto_sms_task":"uav_auto_voice_task";
        if(caseCount(eventId)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE event_id=? AND risk_scope=''",Long.class,eventId)>0)return "";
        return caseId;
    }
    public <T>T withReceipt(String eventId,String channel,String providerKey,java.util.function.Supplier<T> work){
        String table="SMS".equals(channel)?"uav_auto_sms_task":"uav_auto_voice_task";
        var scopes=jdbc.queryForList("SELECT risk_scope FROM "+table+" WHERE event_id=? AND provider_key=?",String.class,eventId,providerKey);
        if(scopes.size()==1&&!scopes.get(0).isBlank())return withCase(scopes.get(0),work);
        return work.get();
    }
    public String notificationOwner(String eventId){
        var rows=identityRows(eventId,false);if(rows.size()!=1)return eventId;
        var row=rows.get(0);String id=(String)row.get("risk_case_id");
        String sms=channelOwner(id,"SMS"),voice=channelOwner(id,"VOICE");
        return sms!=null?sms:voice!=null?voice:(String)row.get("root_event_id");
    }
    public boolean confirmationInherited(String eventId){return confirmedForDisposal(eventId);}
    /** Area-specific relation; ordinary freshness and legality checks remain mandatory downstream. */
    public boolean currentMeasuredArea(CaseRef c){
        if(c.airspaceId()==null)return true;
        for(int attempt=0;;attempt++) {
        var parameters=new HashMap<String,Object>();parameters.put("case_id",c.riskCaseId());
        long now=UavMeasuredRiskSql.addTimes(parameters,jdbc,clock,measuredQaAllowed,measuredSimulationAllowed);
        boolean current=Boolean.TRUE.equals(namedJdbc.queryForObject(
                UavMeasuredRiskSql.expand("SELECT "+UavMeasuredRiskSql.TOKEN+" FROM uav_risk_case c WHERE c.risk_case_id=:case_id AND c.superseded_by IS NULL",postgis),parameters,Boolean.class));
        if(!current&&attempt<2&&frameCommittedAfterSample(List.of(c.riskCaseId()),now))continue;
        return current;
        }
    }
    public Long measuredFreshnessMillis(){return UavMeasuredRiskSql.freshnessMillis(jdbc);}
    public List<Map<String,Object>> currentMeasuredEntries(CaseRef c){
        if(c.airspaceId()==null)return List.of();
        for(int attempt=0;;attempt++) {
        var parameters=new HashMap<String,Object>();parameters.put("case_id",c.riskCaseId());
        long now=UavMeasuredRiskSql.addTimes(parameters,jdbc,clock,measuredQaAllowed,measuredSimulationAllowed);
        var rows=namedJdbc.queryForList(UavMeasuredRiskSql.currentEntriesQuery(postgis),parameters);
        if(rows.isEmpty()&&attempt<2&&frameCommittedAfterSample(List.of(c.riskCaseId()),now))continue;
        return rows;
        }
    }
    public Long measuredObservedAt(CaseRef c){return jdbc.queryForObject("SELECT CAST(EXTRACT(EPOCH FROM s.observed_at)*1000 AS BIGINT) FROM target_latest_state s JOIN uav_risk_case c ON c.target_id=s.target_id WHERE c.risk_case_id=?",Long.class,c.riskCaseId());}
    public List<String> authorizationIds(String caseId){return jdbc.queryForList("SELECT authorization_id FROM disposal_authorization a WHERE a.risk_case_id=? OR (a.risk_case_id IS NULL AND a.subject_kind='UAV_EVENT' AND EXISTS(SELECT 1 FROM uav_risk_case_member m WHERE m.risk_case_id=? AND m.event_id=a.subject_id) AND (SELECT COUNT(*) FROM uav_risk_case_member m JOIN uav_risk_case c USING(risk_case_id) WHERE m.event_id=a.subject_id AND c.superseded_by IS NULL)=1) ORDER BY authorization_id",String.class,caseId,caseId);}
    public Map<String,Object> authorizationSummary(String id){
        var rows=jdbc.queryForList("SELECT risk_case_id,subject_kind,subject_id FROM disposal_authorization WHERE authorization_id=?",id);
        if(rows.isEmpty())return null;var row=rows.get(0);String caseId=(String)row.get("risk_case_id");
        if(caseId==null&&"UAV_EVENT".equals(row.get("subject_kind"))){var candidates=allCases((String)row.get("subject_id"));if(candidates.size()==1)caseId=candidates.get(0).riskCaseId();}
        return caseId==null?null:summary(caseId);
    }
    public Map<String,Object> summary(String id){return summary(id,null);}
    public Map<String,Object> summary(String id,com.uav.lowaltitude.modules.identity.domain.AccessDecision disposalScope){
        return id==null?null:summaries(List.of(id),disposalScope).get(id);
    }
    /** One bounded read batch; no value survives this invocation or becomes an action authorization. */
    public Map<String,Map<String,Object>> summaries(Collection<String> caseIds,
            com.uav.lowaltitude.modules.identity.domain.AccessDecision disposalScope){
        var ids=caseIds.stream().filter(Objects::nonNull).distinct().toList();
        if(ids.isEmpty())return Map.of();
        var sources=detailRows(ids);
        if(sources.isEmpty())return Map.of();
        var parameters=new HashMap<String,Object>();
        parameters.put("ids",sources.stream().map(row->row.get("risk_case_id")).toList());
        parameters.put("events",sources.stream().map(row->row.get("action_event_id")).distinct().toList());
        var eventVersions=new HashMap<String,Object>();
        for(var row:namedJdbc.queryForList("SELECT event_id,version FROM uav_event WHERE event_id IN (:events)",parameters))
            eventVersions.put((String)row.get("event_id"),row.get("version"));
        var basis=new HashMap<String,String>();
        for(var row:namedJdbc.queryForList("SELECT risk_case_id,basis_kind FROM (SELECT risk_case_id,basis_kind,"
                +"ROW_NUMBER() OVER(PARTITION BY risk_case_id ORDER BY case_version DESC) AS basis_rank"
                +" FROM uav_risk_case_verification WHERE risk_case_id IN (:ids)) ranked WHERE basis_rank=1",parameters))
            basis.put((String)row.get("risk_case_id"),(String)row.get("basis_kind"));
        var relatedEvents=new HashMap<String,List<String>>();
        for(var row:namedJdbc.queryForList("SELECT risk_case_id,event_id FROM uav_risk_case_member"
                +" WHERE risk_case_id IN (:ids) ORDER BY risk_case_id,event_id",parameters))
            relatedEvents.computeIfAbsent((String)row.get("risk_case_id"),key->new ArrayList<>()).add((String)row.get("event_id"));
        var relatedAlarms=new HashMap<String,List<String>>();
        for(var row:namedJdbc.queryForList("SELECT m.risk_case_id,e.alarm_id FROM uav_risk_case_member m JOIN uav_event e ON e.event_id=m.event_id"
                +" WHERE m.risk_case_id IN (:ids) ORDER BY m.risk_case_id,m.created_at,e.alarm_id",parameters))
            relatedAlarms.computeIfAbsent((String)row.get("risk_case_id"),key->new ArrayList<>()).add((String)row.get("alarm_id"));
        var channels=new HashMap<String,Map<String,String>>();
        for(var row:namedJdbc.queryForList("SELECT risk_case_id,channel,event_id FROM uav_risk_channel WHERE risk_case_id IN (:ids)",parameters))
            channels.computeIfAbsent((String)row.get("risk_case_id"),key->new HashMap<>()).put((String)row.get("channel"),(String)row.get("event_id"));
        var names=new HashMap<String,String>();
        for(var row:namedJdbc.queryForList("SELECT c.risk_case_id,ar.name FROM uav_risk_case c JOIN airspace ar ON ar.airspace_id=c.airspace_id"
                +" AND ar.owner_org_id=c.owner_org_id AND ar.district_id=c.district_id WHERE c.risk_case_id IN (:ids)",parameters))
            names.put((String)row.get("risk_case_id"),(String)row.get("name"));
        var counters=counterSummaries(parameters,disposalScope);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var result=new LinkedHashMap<String,Map<String,Object>>();
        for(var source:sources){
            String id=(String)source.get("risk_case_id"),action=(String)source.get("action_event_id");
            Map<String,Object> out=new LinkedHashMap<>();
            for(String field:List.of("risk_case_id","version","stage","source_mode","created_at","airspace_id","verification_status","action_event_id"))out.put(field,source.get(field));
            out.put("action_event_version",eventVersions.get(action));
            out.put("manual_counter_only",source.get("episode_id")!=null);
            out.put("verification_basis_kind",basis.getOrDefault(id,source.get("episode_id")==null?"OBSERVED":"PREDICTION"));
            out.put("related_event_ids",relatedEvents.getOrDefault(id,List.of()));
            out.put("related_alarm_ids",relatedAlarms.getOrDefault(id,List.of()));
            var owners=channels.getOrDefault(id,Map.of());
            out.put("notification_event_id",owners.getOrDefault("SMS",owners.getOrDefault("VOICE",action)));
            out.put("prediction_id",source.get("latest_prediction_id"));
            // Frozen display evidence never becomes a measured position or a legal fact.
            com.fasterxml.jackson.databind.JsonNode warning=mapper.createObjectNode();
            try {if(source.get("warning_json")!=null){warning=mapper.readTree(source.get("warning_json").toString());if(warning.isTextual())warning=mapper.readTree(warning.textValue());}}
            catch(com.fasterxml.jackson.core.JsonProcessingException ignored){ /* Missing fields stay unknown. */ }
            if(warning!=null&&warning.isObject()) {
                out.put("airspace_name",warning.path("airspace_name").isTextual()?warning.path("airspace_name").textValue():null);
                out.put("entry_at","PREDICTED".equals(source.get("stage"))&&warning.path("entry_at").isIntegralNumber()?warning.path("entry_at").longValue():null);
            }
            if(out.get("airspace_name")==null&&names.containsKey(id))out.put("airspace_name",names.get(id));
            if(counters.containsKey(id))out.put("counter",counters.get(id));
            out.put("block_reason",Set.of("UNKNOWN","CLEARED","ENDED").contains(Objects.toString(source.get("stage")))?"当前风险已结束或依据不足，暂停新处置":null);
            result.put(id,out);
        }
        return result;
    }
    private Map<String,Map<String,Object>> counterSummaries(Map<String,Object> parameters,
            com.uav.lowaltitude.modules.identity.domain.AccessDecision disposalScope){
        if(disposalScope==null||disposalScope.scopeMode()==com.uav.lowaltitude.modules.identity.domain.ScopeMode.NONE)return Map.of();
        String scope="";
        if(disposalScope.scopeMode()==com.uav.lowaltitude.modules.identity.domain.ScopeMode.ASSIGNED){
            scope=" AND EXISTS(SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE"
                    +" JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:reader_id AND ds.org_id=da.owner_org_id AND ds.district_id=da.district_id)";
            parameters.put("reader_id",disposalScope.userId());
        }
        String projection="SELECT c.risk_case_id,da.authorization_id,da.action_type,da.status,da.result_code,da.requested_at,da.updated_at,"
                +"ROW_NUMBER() OVER(PARTITION BY c.risk_case_id ORDER BY da.requested_at DESC,da.authorization_id DESC) AS counter_rank"
                +" FROM disposal_authorization da JOIN uav_risk_case c ON c.risk_case_id IN (:ids)";
        String sql="SELECT risk_case_id,authorization_id,action_type,status,result_code,requested_at,updated_at FROM ("
                +CASE_AUTHORIZATION.replace("SELECT 1 FROM disposal_authorization da",projection)+scope+") ranked WHERE counter_rank=1";
        var result=new HashMap<String,Map<String,Object>>();
        for(var row:namedJdbc.queryForList(sql,parameters)){
            var value=new LinkedHashMap<>(row);String id=(String)value.remove("risk_case_id");result.put(id,value);
        }
        return result;
    }
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
