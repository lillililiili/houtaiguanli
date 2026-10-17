package com.uav.lowaltitude.modules.handoff.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.NotificationDecisionDto;

/** Decision and deployment boundary are separate from immutable delivery and material facts. */
@Repository
public class HandoffFollowupRepository {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public HandoffFollowupRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public long cutoff(){return jdbc.queryForObject("SELECT eligible_from FROM handoff_followup_policy WHERE policy_id=1",java.sql.Timestamp.class).getTime();}
 public boolean eventCreatedAfterCutoff(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM uav_event e CROSS JOIN handoff_followup_policy p WHERE e.event_id=? AND e.created_at>=p.eligible_from",Integer.class,id)>0;}
 private static final String NEW_FACT="(EXISTS (SELECT 1 FROM uav_no_counter_decision n WHERE n.event_id=e.event_id AND n.decided_at>=?) OR EXISTS (SELECT 1 FROM uav_auto_voice_task v WHERE v.event_id=e.event_id AND v.departure_evaluated_at>=?) OR EXISTS (SELECT 1 FROM disposal_authorization a JOIN device_command c ON c.command_id=a.execution_command_id WHERE a.subject_kind='UAV_EVENT' AND a.subject_id=e.event_id AND a.status='COMPLETED' AND c.status='SUCCEEDED' AND c.completed_at>=?) OR EXISTS (SELECT 1 FROM disposal_authorization a JOIN disposal_authorization_event ae ON ae.authorization_id=a.authorization_id WHERE a.subject_kind='UAV_EVENT' AND a.subject_id=e.event_id AND a.status='COMPLETED' AND ae.event_kind='COMPLETE' AND ae.occurred_at>=?))";
 public boolean eligible(String eventId){long at=cutoff();return jdbc.queryForObject("SELECT COUNT(*) FROM uav_event e CROSS JOIN handoff_followup_policy p WHERE e.event_id=? AND (e.created_at>=p.eligible_from OR "+NEW_FACT+")",Integer.class,eventId,at,at,at,new java.sql.Timestamp(at))>0;}
 public List<String> candidates(){long at=cutoff();return jdbc.queryForList("SELECT e.event_id FROM uav_event e CROSS JOIN handoff_followup_policy p WHERE (e.created_at>=p.eligible_from OR "+NEW_FACT+") AND e.state_code='CONFIRMED' AND NOT EXISTS (SELECT 1 FROM handoff h WHERE h.source_kind='UAV_EVENT' AND h.source_id=e.event_id AND h.handoff_type='UAV_PUNISHMENT') AND (EXISTS (SELECT 1 FROM uav_no_counter_decision n WHERE n.event_id=e.event_id) OR EXISTS (SELECT 1 FROM uav_auto_voice_task v WHERE v.event_id=e.event_id AND v.departure_evaluated_at IS NOT NULL) OR EXISTS (SELECT 1 FROM disposal_authorization a WHERE a.subject_kind='UAV_EVENT' AND a.subject_id=e.event_id AND a.status='COMPLETED')) ORDER BY e.created_at,e.event_id",String.class,at,at,at,new java.sql.Timestamp(at));}
 public void insert(String handoff,String event,String fact){jdbc.update("INSERT INTO handoff_notification_decision(handoff_id,event_id,state,version,field_handling) VALUES(?,?,'PENDING_DECISION',0,?)",handoff,event,fact);}
 public NotificationDecisionDto decision(String id){var rows=jdbc.query("SELECT * FROM handoff_notification_decision WHERE handoff_id=?",(r,n)->new NotificationDecisionDto(r.getString("state"),r.getLong("version"),r.getString("reason"),r.getString("decided_by"),r.getString("decided_by_name"),(Long)r.getObject("decided_at"),tree(r.getString("field_handling"))),id);return rows.isEmpty()?null:rows.get(0);}
 public boolean decide(String id,String state,String reason,String actor,String name,long at,long version){return jdbc.update("UPDATE handoff_notification_decision SET state=?,version=version+1,reason=?,decided_by=?,decided_by_name=?,decided_at=? WHERE handoff_id=? AND state='PENDING_DECISION' AND version=?",state,reason,actor,name,at,id,version)==1;}
 public String actorName(String id){return jdbc.queryForObject("SELECT name FROM app_user WHERE user_id=?",String.class,id);}
 private com.fasterxml.jackson.databind.JsonNode tree(String value){try{var stored=json.readTree(value);var summary=json.createObjectNode();for(String key:List.of("kind","completed_at","material_blocked_reason","material_blocked_code"))if(stored.has(key))summary.set(key,stored.get(key));return summary;}catch(Exception ex){throw new IllegalStateException("现场结束依据无法读取",ex);}}
}
