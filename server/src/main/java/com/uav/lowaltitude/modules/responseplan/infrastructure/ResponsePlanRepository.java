package com.uav.lowaltitude.modules.responseplan.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.responseplan.api.ResponsePlanDtos.*;
import com.uav.lowaltitude.platform.security.AuthContext;

@Repository
public class ResponsePlanRepository {
    private final JdbcTemplate jdbc;
    public ResponsePlanRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SELECT="SELECT v.*,p.anchor_airspace_id,a.name AS airspace_name FROM response_plan_version v JOIN response_plan p ON p.plan_id=v.plan_id JOIN airspace a ON a.airspace_id=p.anchor_airspace_id ";
    private String scope(List<Object> args) {
        var actor=AuthContext.require();
        if ("ALL".equals(actor.scopeMode())) return "1=1";
        if (!"ASSIGNED".equals(actor.scopeMode())) return "1=0";
        args.add(actor.userId());
        return "EXISTS(SELECT 1 FROM app_user_data_scope s JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE WHERE s.user_id=? AND s.org_id=a.owner_org_id AND s.district_id=a.district_id)";
    }
    public Page<Version> list(int page,int size) {
        var args=new ArrayList<Object>();String where=" WHERE "+scope(args)+" AND v.revision=(SELECT MAX(v2.revision) FROM response_plan_version v2 WHERE v2.plan_id=p.plan_id)";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM response_plan_version v JOIN response_plan p ON p.plan_id=v.plan_id JOIN airspace a ON a.airspace_id=p.anchor_airspace_id"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        return new Page<>(jdbc.query(SELECT+where+" ORDER BY v.updated_at DESC,v.version_id LIMIT ? OFFSET ?",this::version,args.toArray()),page,size,total);
    }
    public Version get(String id) {
        var args=new ArrayList<Object>();args.add(id);String where=" WHERE v.version_id=? AND "+scope(args);
        return jdbc.query(SELECT+where,this::version,args.toArray()).stream().findFirst().orElse(null);
    }
    public List<Version> versions(String planId) {
        var args=new ArrayList<Object>();args.add(planId);String where=" WHERE p.plan_id=? AND "+scope(args);
        return jdbc.query(SELECT+where+" ORDER BY v.revision DESC",this::version,args.toArray());
    }
    public Page<AirspaceOption> airspaces(int page,int size,String keyword) {
        var args=new ArrayList<Object>();String where=" WHERE "+scope(args);
        if(keyword!=null&&!keyword.isBlank()){where+=" AND LOWER(a.name) LIKE ?";args.add("%"+keyword.toLowerCase(Locale.ROOT)+"%");}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM airspace a"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        return new Page<>(jdbc.query("SELECT a.airspace_id,a.name FROM airspace a"+where+" ORDER BY a.name,a.airspace_id LIMIT ? OFFSET ?",(r,i)->new AirspaceOption(r.getString(1),r.getString(2)),args.toArray()),page,size,total);
    }
    public boolean visibleAirspace(String id) {
        var args=new ArrayList<Object>();args.add(id);
        return jdbc.queryForObject("SELECT COUNT(*) FROM airspace a WHERE a.airspace_id=? AND "+scope(args),Long.class,args.toArray())==1;
    }
    public boolean sameScope(String first,String second) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM airspace a JOIN airspace b ON (a.owner_org_id=b.owner_org_id OR (a.owner_org_id IS NULL AND b.owner_org_id IS NULL)) AND (a.district_id=b.district_id OR (a.district_id IS NULL AND b.district_id IS NULL)) WHERE a.airspace_id=? AND b.airspace_id=?",Long.class,first,second)==1;
    }
    public void lockAirspace(String id){jdbc.queryForObject("SELECT airspace_id FROM airspace WHERE airspace_id=? FOR UPDATE",String.class,id);}
    public int activeAirspaceVersions(String id,long now){var at=new java.sql.Timestamp(now);return jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version WHERE airspace_id=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)",Integer.class,id,at,at);}
    public void lockPlan(String id){jdbc.queryForObject("SELECT plan_id FROM response_plan WHERE plan_id=? FOR UPDATE",String.class,id);}
    public void lockVersion(String id){jdbc.queryForObject("SELECT version_id FROM response_plan_version WHERE version_id=? FOR UPDATE",String.class,id);}
    public void createPlan(String id,String airspace,long now){jdbc.update("INSERT INTO response_plan(plan_id,anchor_airspace_id,created_at) VALUES(?,?,?)",id,airspace,now);}
    public int nextRevision(String id){return jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM response_plan_version WHERE plan_id=?",Integer.class,id);}
    public void insert(String id,String plan,int revision,Input b,long now){jdbc.update("INSERT INTO response_plan_version(version_id,plan_id,revision,name,trigger_basis,action_steps,manual_conditions,failure_handling,source_mode,valid_from,valid_to,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",id,plan,revision,b.name().trim(),b.triggerBasis().trim(),b.actionSteps().trim(),b.manualConditions().trim(),b.failureHandling().trim(),b.sourceMode(),b.validFrom(),b.validTo(),now,now);}
    public int update(String id,Input b,long now){return jdbc.update("UPDATE response_plan_version SET name=?,trigger_basis=?,action_steps=?,manual_conditions=?,failure_handling=?,source_mode=?,valid_from=?,valid_to=?,version=version+1,updated_at=? WHERE version_id=? AND version=? AND status='DRAFT'",b.name().trim(),b.triggerBasis().trim(),b.actionSteps().trim(),b.manualConditions().trim(),b.failureHandling().trim(),b.sourceMode(),b.validFrom(),b.validTo(),now,id,b.expectedVersion());}
    public int publish(String id,long version,long now,String actor){return jdbc.update("UPDATE response_plan_version SET status='PUBLISHED',published_at=?,published_by=?,updated_at=?,version=version+1 WHERE version_id=? AND version=? AND status='DRAFT'",now,actor,now,id,version);}
    public int withdraw(String id,long version,long now,String reason){return jdbc.update("UPDATE response_plan_version SET status='WITHDRAWN',withdrawn_at=?,withdrawn_reason=?,updated_at=?,version=version+1 WHERE version_id=? AND version=? AND status='PUBLISHED'",now,reason,now,id,version);}
    public record BindingRow(String id,String airspaceId,String versionId,long at,String by,String reason,Long endedAt,String endedBy,String endReason) { }
    public BindingRow current(String airspace){return jdbc.query("SELECT * FROM airspace_response_plan_binding WHERE airspace_id=? AND ended_at IS NULL",this::binding,airspace).stream().findFirst().orElse(null);}
    public Page<BindingRow> history(String airspace,int page,int size){long total=jdbc.queryForObject("SELECT COUNT(*) FROM airspace_response_plan_binding WHERE airspace_id=? AND ended_at IS NOT NULL",Long.class,airspace);return new Page<>(jdbc.query("SELECT * FROM airspace_response_plan_binding WHERE airspace_id=? AND ended_at IS NOT NULL ORDER BY ended_at DESC,binding_id LIMIT ? OFFSET ?",this::binding,airspace,size,(page-1)*size),page,size,total);}
    public void end(String id,long now,String actor,String reason){jdbc.update("UPDATE airspace_response_plan_binding SET ended_at=?,ended_by=?,end_reason=? WHERE binding_id=? AND ended_at IS NULL",now,actor,reason,id);}
    public void bind(String id,String airspace,String version,long now,String actor,String reason){jdbc.update("INSERT INTO airspace_response_plan_binding(binding_id,airspace_id,version_id,bound_at,bound_by,reason) VALUES(?,?,?,?,?,?)",id,airspace,version,now,actor,reason);}
    private BindingRow binding(ResultSet r,int index)throws SQLException{return new BindingRow(r.getString("binding_id"),r.getString("airspace_id"),r.getString("version_id"),r.getLong("bound_at"),r.getString("bound_by"),r.getString("reason"),nullable(r,"ended_at"),r.getString("ended_by"),r.getString("end_reason"));}
    private Version version(ResultSet r,int index)throws SQLException{return new Version(r.getString("plan_id"),r.getString("version_id"),r.getString("anchor_airspace_id"),r.getString("airspace_name"),r.getInt("revision"),r.getString("name"),r.getString("trigger_basis"),r.getString("action_steps"),r.getString("manual_conditions"),r.getString("failure_handling"),r.getString("source_mode"),r.getLong("valid_from"),nullable(r,"valid_to"),r.getString("status"),r.getLong("version"),r.getLong("created_at"),r.getLong("updated_at"),nullable(r,"published_at"),r.getString("published_by"),nullable(r,"withdrawn_at"),r.getString("withdrawn_reason"));}
    private static Long nullable(ResultSet r,String key)throws SQLException{long n=r.getLong(key);return r.wasNull()?null:n;}
}
