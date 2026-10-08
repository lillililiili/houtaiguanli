package com.uav.lowaltitude.modules.integrationconfig.application;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.directory.application.SimulatorNotificationConfiguration;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Transactional outbox. Only an authenticated independent receiver can acknowledge a request. */
@Service
public class RealtimeNotificationTransport {
 public static final String ENDPOINT="local-data-simulator", MARKER="SIMULATOR_WAITING:";
 private static final Set<String> KINDS=Set.of("ADVISORY_SMS","ADVISORY_VOICE","RISK_NOTICE","UAV_PUNISHMENT","PLAN_FEEDBACK","DEVICE_MAINTENANCE");
 private final JdbcTemplate jdbc; private final ObjectMapper json; private final AppClock clock;
 private final Environment env; private final AccessService access; private final AuditService audit;
 private final SimulatorNotificationConfiguration settings;
 private final ObjectProvider<RealtimeSimulatorReceiptProjector> projector;
 public RealtimeNotificationTransport(JdbcTemplate jdbc,ObjectMapper json,AppClock clock,Environment env,AccessService access,
  AuditService audit,SimulatorNotificationConfiguration settings,ObjectProvider<RealtimeSimulatorReceiptProjector> projector){
  this.jdbc=jdbc;this.json=json;this.clock=clock;this.env=env;this.access=access;this.audit=audit;this.settings=settings;this.projector=projector;
 }
 public boolean enabled(){return "simulator".equals(env.getProperty("app.notifications.transport"))&&env.acceptsProfiles(Profiles.of("((local & qa) | test) & !prod & !production"));}
 public boolean online(){return enabled()&&jdbc.queryForObject("SELECT COUNT(*) FROM simulator_notification_receiver WHERE receiver_id='receiver' AND enabled=TRUE AND expires_at>?",Long.class,clock.nowMillis())==1;}
 private void authorize(){
  if(!enabled())throw conflict("数据模拟器通知通道尚未启用");
  access.require("interfaces.op");access.require("notificationSettings.auth");
  if(!"ALL".equals(AuthContext.require().scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"SIMULATOR_SCOPE_REQUIRED","数据模拟器接收端需要全局管理范围");
 }
 @Transactional public Binding connect(BindingInput input){
  authorize();if(!"receiver".equals(input.sourceId()))throw conflict("接收端标识无效");
  var actor=AuthContext.require();var existing=jdbc.queryForMap("SELECT * FROM simulator_notification_receiver WHERE receiver_id='receiver' FOR UPDATE");
  long now=clock.nowMillis();
  if(Boolean.TRUE.equals(existing.get("enabled"))&&((Number)existing.get("expires_at")).longValue()>now&&!actor.userId().equals(existing.get("actor_id")))throw conflict("已有其他操作人的实时接收端");
  if(input.enabled())settings.activate();
  long expiry=input.enabled()?now+30000:now;
  jdbc.update("UPDATE simulator_notification_receiver SET actor_id=?,enabled=?,expires_at=? WHERE receiver_id='receiver'",actor.userId(),input.enabled(),expiry);
  return new Binding("NOTIFICATION_CHANNEL","receiver",input.enabled(),expiry);
 }
 /** 模拟器"实时收发设置"里的处罚接收单位：读当前选择，或按所选单位接到数据模拟器（D-1）。 */
 public SimulatorNotificationConfiguration.PunishmentRecipients punishmentRecipients(){authorize();return settings.punishmentRecipients();}
 @Transactional public SimulatorNotificationConfiguration.PunishmentRecipients selectPunishmentRecipients(List<String> orgIds){authorize();return settings.selectPunishmentRecipients(orgIds);}
 @Transactional public Message submit(String kind,String subject,String attempt,JsonNode payload){
  if(!KINDS.contains(kind)||attempt==null||attempt.isBlank()||attempt.length()>256)throw conflict("通知请求类型或标识无效");
  if(!online())throw conflict("数据模拟器接收端未连接或心跳已失效");
  // The singleton lock serializes concurrent retries without swallowing a failed PostgreSQL transaction.
  var receiver=jdbc.queryForMap("SELECT * FROM simulator_notification_receiver WHERE receiver_id='receiver' FOR UPDATE");
  if(!Boolean.TRUE.equals(receiver.get("enabled"))||((Number)receiver.get("expires_at")).longValue()<=clock.nowMillis())throw conflict("数据模拟器接收端已断开");
  var rows=jdbc.query("SELECT * FROM simulator_notification_message WHERE attempt_key=?",this::row,attempt);
  if(!rows.isEmpty()){
   var old=rows.get(0);if(!old.kind().equals(kind)||!old.subjectId().equals(subject)||!old.payload().equals(payload))throw conflict("同一通知尝试不能更换内容或接收对象");return view(old,false);
  }
  String id="simn-"+UUID.randomUUID();long now=clock.nowMillis();
  jdbc.update("INSERT INTO simulator_notification_message(message_id,attempt_key,actor_id,kind,subject_id,payload,state,created_at,version) VALUES(?,?,?,?,?,?,'SUBMITTED',?,0)",id,attempt,receiver.get("actor_id"),kind,subject,payload.toString(),now);
  return view(require(id,false),false);
 }
 public List<Message> pending(){
  if(!enabled())return List.of();
  try{authorize();}catch(ApiException denied){if(denied.getStatus()==HttpStatus.FORBIDDEN)return List.of();throw denied;}
  String actor=AuthContext.require().userId();
  if(!online()||!actor.equals(jdbc.queryForObject("SELECT actor_id FROM simulator_notification_receiver WHERE receiver_id='receiver'",String.class)))return List.of();
  return jdbc.query("SELECT * FROM simulator_notification_message WHERE actor_id=? AND (state IN('SUBMITTED','ANSWERED') OR (state='DELIVERED' AND kind<>'ADVISORY_SMS')) ORDER BY created_at,message_id",this::row,actor).stream().map(r->view(r,true)).toList();
 }
 @Transactional public Message receipt(String id,ReceiptInput input){
  authorize();var actor=AuthContext.require();Row old=require(id,true);
  if(!actor.userId().equals(old.actor()))throw new ApiException(HttpStatus.NOT_FOUND,"SIMULATOR_MESSAGE_NOT_FOUND","通知请求不存在");
  if(input.receiptResult()!=null&&(!"RISK_NOTICE".equals(old.kind())||!"ACKNOWLEDGED".equals(input.outcome())
    ||!Set.of("DISPERSED","NOT_DISPERSED").contains(input.receiptResult())))throw conflict("处理结果只适用于风险通知的签收回执");
  if(old.state().equals(input.outcome())){
   if("ACKNOWLEDGED".equals(old.state())&&"APPLIED".equals(old.projection())
     &&!Objects.equals(input.receiptResult(),projector.getObject().processingResult(old.kind(),old.subjectId())))
    throw conflict("重复回执不能更改已保存的处理结果");
   return view(old,true);
  }
  if(old.version()!=input.expectedVersion())throw conflict("请求版本已变化，请重新读取");
  if(!allowed(old.kind(),old.state(),input.outcome()))throw conflict("通知回执顺序无效");
  long now=clock.nowMillis();Long delivered=old.delivered(),answered=old.answered(),completed=old.completed(),ack=old.ack();
  switch(input.outcome()){case "DELIVERED"->delivered=now;case "ANSWERED"->answered=now;case "PLAYED"->completed=now;case "ACKNOWLEDGED"->ack=now;default->{}}
  boolean applied=projector.getObject().apply(id,old.kind(),old.subjectId(),old.payload(),input.outcome(),delivered,answered,completed,ack,input.receiptResult());
  if(jdbc.update("UPDATE simulator_notification_message SET state=?,delivered_at=?,answered_at=?,completed_at=?,acknowledged_at=?,projection_status=?,version=version+1 WHERE message_id=? AND version=?",input.outcome(),delivered,answered,completed,ack,applied?"APPLIED":"IGNORED_STALE_ATTEMPT",id,old.version())!=1)throw conflict("请求已被处理");
  audit.record(actor.userId(),actor.account(),"simulator_notification_receipt","local_interface",id,"独立模拟器回执："+input.outcome()+"; kind="+old.kind(),null);
  return view(require(id,false),true);
 }
 static boolean allowed(String kind,String state,String next){
  if("SUBMITTED".equals(state))return Set.of("FAILED","TIMEOUT").contains(next)||("ADVISORY_VOICE".equals(kind)?"ANSWERED".equals(next):"DELIVERED".equals(next));
  if("ANSWERED".equals(state))return "ADVISORY_VOICE".equals(kind)&&Set.of("PLAYED","TIMEOUT").contains(next);
  return "DELIVERED".equals(state)&&!"ADVISORY_SMS".equals(kind)&&Set.of("ACKNOWLEDGED","TIMEOUT").contains(next);
 }
 private Row require(String id,boolean lock){var rows=jdbc.query("SELECT * FROM simulator_notification_message WHERE message_id=?"+(lock?" FOR UPDATE":""),this::row,id);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"SIMULATOR_MESSAGE_NOT_FOUND","通知请求不存在");return rows.get(0);}
 private Row row(java.sql.ResultSet r,int n)throws java.sql.SQLException {
  try{return new Row(r.getString("message_id"),r.getString("actor_id"),r.getString("kind"),r.getString("subject_id"),json.readTree(r.getString("payload")),r.getString("state"),r.getLong("created_at"),r.getLong("version"),(Long)r.getObject("delivered_at"),(Long)r.getObject("answered_at"),(Long)r.getObject("completed_at"),(Long)r.getObject("acknowledged_at"),r.getString("projection_status"));}catch(java.io.IOException e){throw new IllegalStateException("通知请求无法读取",e);}
 }
 private Message view(Row r,boolean external){
  JsonNode payload=r.payload().deepCopy();if(external&&payload instanceof ObjectNode object)object.remove("claim_token");
  var result=json.createObjectNode();if(r.delivered()!=null)result.put("delivered_at",r.delivered());if(r.answered()!=null)result.put("answered_at",r.answered());if(r.completed()!=null)result.put("playback_completed_at",r.completed());if(r.ack()!=null)result.put("acknowledged_at",r.ack());result.put("transport","DATA_SIMULATOR");
  result.put("projection_status",r.projection());
  if("ACKNOWLEDGED".equals(r.state())&&"APPLIED".equals(r.projection())){
   String processing=projector.getObject().processingResult(r.kind(),r.subjectId());
   if(processing!=null)result.put("receipt_result",processing);
  }
  return new Message(r.id(),r.kind(),"OUT",r.subjectId(),r.state(),r.version(),r.createdAt(),payload,result);
 }
 private record Row(String id,String actor,String kind,String subjectId,JsonNode payload,String state,long createdAt,long version,Long delivered,Long answered,Long completed,Long ack,String projection){}
 private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"SIMULATOR_NOTIFICATION_CONFLICT",message);}
}
