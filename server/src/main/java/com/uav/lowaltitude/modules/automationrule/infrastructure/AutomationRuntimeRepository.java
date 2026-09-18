package com.uav.lowaltitude.modules.automationrule.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AutomationRuntimeRepository {
    private final JdbcTemplate jdbc;
    public AutomationRuntimeRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record State(String category,String eventId,long version,String runId,String status,Long unknownSince,String holds,String signature) { }
    public record RunRow(String id,String category,String eventId,String targetId,long version,String status,String reason,long at,Long observed,String mode,String conditions) { }
    public record ActionRow(String id,String eventId,String category,String code,String runId,String status,String reason,String reference,String receipt,Long lease,int attempts) { }
    public String cursor(){return jdbc.queryForObject("SELECT cursor_event_id FROM automation_runtime_worker WHERE worker_key='automation-rules'",String.class);}
    public void heartbeat(long now,String cursor,String error){jdbc.update("UPDATE automation_runtime_worker SET heartbeat_at=?,cursor_event_id=?,last_error=? WHERE worker_key='automation-rules'",now,cursor,error);}
    public long heartbeat(){return jdbc.queryForObject("SELECT heartbeat_at FROM automation_runtime_worker WHERE worker_key='automation-rules'",Long.class);}
    public String lastError(){return jdbc.queryForObject("SELECT last_error FROM automation_runtime_worker WHERE worker_key='automation-rules'",String.class);}
    // Caller holds the corresponding group row; all initialization and changes are serialized by that lock.
    public State state(String category,String event){return jdbc.query("SELECT * FROM automation_runtime_state WHERE category=? AND event_id=?",(r,n)->new State(category,event,r.getLong("group_version"),r.getString("run_id"),r.getString("status"),nullable(r,"unknown_since"),r.getString("holds_json"),r.getString("signature")),category,event).stream().findFirst().orElse(null);}
    public void state(String category,String event,long version,String run,String status,Long unknown,String holds,String signature) {
        if(state(category,event)==null)jdbc.update("INSERT INTO automation_runtime_state(category,event_id,group_version,run_id,status,unknown_since,holds_json,signature) VALUES(?,?,?,?,?,?,?,?)",category,event,version,run,status,unknown,holds,signature);
        else jdbc.update("UPDATE automation_runtime_state SET group_version=?,run_id=?,status=?,unknown_since=?,holds_json=?,signature=? WHERE category=? AND event_id=?",version,run,status,unknown,holds,signature,category,event);
    }
    public void insertRun(RunRow run,String facts){jdbc.update("INSERT INTO automation_runtime_run(run_id,category,event_id,target_id,group_version,status,reason,evaluated_at,observed_at,source_mode,conditions_json,facts_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",run.id(),run.category(),run.eventId(),run.targetId(),run.version(),run.status(),run.reason(),run.at(),run.observed(),run.mode(),run.conditions(),facts);}
    public RunRow run(String id){return jdbc.query("SELECT * FROM automation_runtime_run WHERE run_id=?",AutomationRuntimeRepository::run,id).stream().findFirst().orElse(null);}
    public List<RunRow> runs(String category,int page,int size){return jdbc.query("SELECT * FROM automation_runtime_run WHERE category=? ORDER BY evaluated_at DESC,run_id DESC LIMIT ? OFFSET ?",AutomationRuntimeRepository::run,category,size,(page-1)*size);}
    public long runCount(String category){return jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run WHERE category=?",Long.class,category);}
    public List<ActionRow> actionsForRun(String run){return jdbc.query("SELECT a.* FROM automation_runtime_action a JOIN automation_runtime_run_action r ON r.action_id=a.action_id WHERE r.run_id=? ORDER BY a.code",AutomationRuntimeRepository::action,run);}
    public ActionRow action(String event,String code){return jdbc.query("SELECT * FROM automation_runtime_action WHERE event_id=? AND code=?",AutomationRuntimeRepository::action,event,code).stream().findFirst().orElse(null);}
    public ActionRow lockAction(String id){return jdbc.query("SELECT * FROM automation_runtime_action WHERE action_id=? FOR UPDATE",AutomationRuntimeRepository::action,id).stream().findFirst().orElse(null);}
    public ActionRow ensureAction(String category,String event,String code,String run,long now){
        var current=action(event,code);
        if(current==null){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO automation_runtime_action(action_id,event_id,category,code,run_id,status,reason,updated_at) VALUES(?,?,?,?,?,'WAITING','等待后台执行',?)",id,event,category,code,run,now);current=action(event,code);}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run_action WHERE run_id=? AND action_id=?",Long.class,run,current.id())==0)jdbc.update("INSERT INTO automation_runtime_run_action(run_id,action_id) VALUES(?,?)",run,current.id());
        return current;
    }
    public void claim(ActionRow action,String run,long now){jdbc.update("UPDATE automation_runtime_action SET run_id=?,status='RUNNING',reason='后台正在执行',updated_at=?,lease_until=?,attempt_count=attempt_count+1 WHERE action_id=?",run,now,now+60000,action.id());}
    public void finish(String id,String status,String reason,String reference,String receipt,long now){jdbc.update("UPDATE automation_runtime_action SET status=?,reason=?,reference_id=?,receipt_json=?,updated_at=?,lease_until=NULL WHERE action_id=?",status,reason,reference,receipt,now,id);}
    private static RunRow run(ResultSet r,int n)throws SQLException{return new RunRow(r.getString("run_id"),r.getString("category"),r.getString("event_id"),r.getString("target_id"),r.getLong("group_version"),r.getString("status"),r.getString("reason"),r.getLong("evaluated_at"),nullable(r,"observed_at"),r.getString("source_mode"),r.getString("conditions_json"));}
    private static ActionRow action(ResultSet r,int n)throws SQLException{return new ActionRow(r.getString("action_id"),r.getString("event_id"),r.getString("category"),r.getString("code"),r.getString("run_id"),r.getString("status"),r.getString("reason"),r.getString("reference_id"),r.getString("receipt_json"),nullable(r,"lease_until"),r.getInt("attempt_count"));}
    private static Long nullable(ResultSet r,String key)throws SQLException{long value=r.getLong(key);return r.wasNull()?null:value;}
}
