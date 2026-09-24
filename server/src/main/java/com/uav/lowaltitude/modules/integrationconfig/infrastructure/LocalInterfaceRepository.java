package com.uav.lowaltitude.modules.integrationconfig.infrastructure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.Binding;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.SourceOption;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class LocalInterfaceRepository {
 private final JdbcTemplate jdbc;
 public LocalInterfaceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Row(String id,String externalId,String kind,String direction,String subjectId,String actor,String state,String payload,String result,long createdAt,long version){}
 private static final org.springframework.jdbc.core.RowMapper<Row> ROW=(r,n)->new Row(r.getString("message_id"),r.getString("external_id"),r.getString("kind"),r.getString("direction"),r.getString("subject_id"),r.getString("created_by"),r.getString("state"),r.getString("payload"),r.getString("result"),r.getLong("created_at"),r.getLong("version"));
 public void actorLock(String actor){jdbc.queryForObject("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",String.class,actor);}
 public Row existing(String actor,String kind,String external){return first(jdbc.query("SELECT * FROM local_interface_message WHERE created_by=? AND kind=? AND external_id=?",ROW,actor,kind,external));}
 public Row lock(String id){return first(jdbc.query("SELECT * FROM local_interface_message WHERE message_id=? FOR UPDATE",ROW,id));}
 public Row find(String id){return first(jdbc.query("SELECT * FROM local_interface_message WHERE message_id=?",ROW,id));}
 public List<Row> messages(String actor){return jdbc.query("SELECT * FROM local_interface_message WHERE created_by=? ORDER BY created_at DESC,message_id DESC FETCH FIRST 100 ROWS ONLY",ROW,actor);}
 public Row weather(String plan){return first(jdbc.query("SELECT * FROM local_interface_message WHERE kind='WEATHER_FORECAST' AND subject_id=? AND direction='IN' ORDER BY created_at DESC,message_id DESC FETCH FIRST 1 ROWS ONLY",ROW,plan));}
 public void insert(Row r){jdbc.update("INSERT INTO local_interface_message(message_id,external_id,kind,direction,subject_id,created_by,state,payload,result,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",r.id(),r.externalId(),r.kind(),r.direction(),r.subjectId(),r.actor(),r.state(),r.payload(),r.result(),r.createdAt(),r.createdAt());}
 public int receipt(String id,long expected,String state,String result,long now){return jdbc.update("UPDATE local_interface_message SET state=?,result=?,updated_at=?,version=version+1 WHERE message_id=? AND version=?",state,result,now,id,expected);}
 public record BindingRow(String sourceKind,String sourceId,String actor,boolean enabled,long expiresAt){}
 public BindingRow binding(String kind,String id){var rows=jdbc.query("SELECT * FROM local_interface_binding WHERE source_kind=? AND source_id=?",(r,n)->new BindingRow(r.getString("source_kind"),r.getString("source_id"),r.getString("created_by"),r.getBoolean("enabled"),r.getLong("expires_at")),kind,id);return rows.isEmpty()?null:rows.get(0);}
 public void bind(String kind,String id,String actor,boolean enabled,long expiry){int changed=jdbc.update("UPDATE local_interface_binding SET enabled=?,expires_at=?,created_by=? WHERE source_kind=? AND source_id=?",enabled,expiry,actor,kind,id);if(changed==0)jdbc.update("INSERT INTO local_interface_binding(source_kind,source_id,created_by,enabled,expires_at) VALUES(?,?,?,?,?)",kind,id,actor,enabled,expiry);}
 public List<Binding> bindings(String actor,long now){return jdbc.query("SELECT * FROM local_interface_binding WHERE created_by=? ORDER BY expires_at DESC FETCH FIRST 100 ROWS ONLY",(r,n)->new Binding(r.getString("source_kind"),r.getString("source_id"),r.getBoolean("enabled")&&r.getLong("expires_at")>now,r.getLong("expires_at")),actor);}
 public List<SourceOption> sources(boolean risk,AccessDecision access){
  String table=risk?"flight_risk s":"uav_event s JOIN alarm a ON a.alarm_id=s.alarm_id",id=risk?"risk_id":"event_id",label=risk?"s.risk_no":"COALESCE(a.alarm_no,a.source_alarm_id,s.event_id)",mode=risk?"s.source_mode":"a.source_mode";
  String scope=" AND EXISTS(SELECT 1 FROM app_org o JOIN app_district d ON d.district_id=s.district_id WHERE o.org_id=s.owner_org_id AND o.enabled=TRUE AND d.enabled=TRUE)";
  var args=new ArrayList<Object>();if(access.scopeMode()==ScopeMode.ASSIGNED){scope+=" AND EXISTS(SELECT 1 FROM app_user_data_scope a WHERE a.user_id=? AND a.org_id=s.owner_org_id AND a.district_id=s.district_id)";args.add(access.userId());}
  return jdbc.query("SELECT s."+id+","+label+" AS label FROM "+table+" WHERE "+mode+" IN ('mock','replay')"+scope+" ORDER BY s.created_at DESC FETCH FIRST 100 ROWS ONLY",(r,n)->new SourceOption(risk?"RISK":"UAV_EVENT",r.getString(1),r.getString(2)==null?r.getString(1):r.getString(2)),args.toArray());
 }
 private static Row first(List<Row> rows){return rows.isEmpty()?null:rows.get(0);}
}
