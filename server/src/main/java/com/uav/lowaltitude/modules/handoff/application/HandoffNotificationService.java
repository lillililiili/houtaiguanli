package com.uav.lowaltitude.modules.handoff.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DeliveryDto;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.*;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** 已入库处罚交接的发送入口。先持久化占位，事务外投递，再保存结果；中断后保持待核对，不盲目重发。 */
@Service
public class HandoffNotificationService {
 private final HandoffRepository repository;
 private final UavEventRepository events;
 private final AccessControlService access;
 private final IdempotencyGuard idempotency;
 private final NotificationDirectoryService directory;
 private final HandoffChannelPort channel;
 private final AppClock clock;
 private final AuditService audit;
 private final ObjectMapper json;
 private final Environment environment;
 private final TransactionTemplate transaction;
 public HandoffNotificationService(HandoffRepository repository,UavEventRepository events,AccessControlService access,
   IdempotencyGuard idempotency,NotificationDirectoryService directory,HandoffChannelPort channel,AppClock clock,
   AuditService audit,ObjectMapper json,Environment environment,PlatformTransactionManager manager){
  this.repository=repository;this.events=events;this.access=access;this.idempotency=idempotency;this.directory=directory;
  this.channel=channel;this.clock=clock;this.audit=audit;this.json=json;this.environment=environment;this.transaction=new TransactionTemplate(manager);
 }
 public record StatusDto(String handoffId,boolean canNotify,String blockedReason,int expectedAttemptNo,
   String deliveryStatus,String receiptStatus,DeliveryDto latestDelivery,RecipientSnapshot recipientSnapshot,boolean simulated){}
 private record Prepared(HandoffRow handoff,DeliveryInsert attempt,RecipientSnapshot target,String material){}

 public StatusDto status(String id){
  HandoffRow row=visible(id,false);
  var latest=repository.latestDelivery(row.handoffId());
  String reason=stateBlocker(row,latest);
  RecipientSnapshot target=latest==null?null:savedRecipient(latest.deliveryId());
  if(target==null)target=directory.handoffSnapshot(row.handoffId());
  // 已投递记录只查事实，不因当前配置失效覆盖原来的送达或回执。
  if(reason==null){
   try{access.require(PermissionCode.HANDOFF_CREATE);var decision=access.require(PermissionCode.ALARM_READ);
    var event=events.find(row.sourceId(),decision);
    if(event==null)reason="来源事件不存在或不在当前权限范围内";
    else if(!"CONFIRMED".equals(event.state()))reason="当前事件不再属于已核实属实的事件，请核对原交接";
    else{target=directory.forHandoff(row.handoffType(),row.recipientId());reason=channelBlocker(row,target);}
   }catch(ApiException error){reason=error.getStatus()==HttpStatus.FORBIDDEN?"当前账号没有通知处罚部门所需权限":error.getMessage();}
  }
  return new StatusDto(row.handoffId(),reason==null,reason,latest==null?0:latest.attemptNo(),row.deliveryStatus(),row.receiptStatus(),
    latest==null?null:HandoffReadService.dto(latest),target, target!=null&&"MOCK".equals(target.channelType()));
 }
 public DeliveryDto notify(String id,String raw,String key){
  // 鉴权先于解析，避免无权用户通过校验差异探测对象。
  access.require(PermissionCode.HANDOFF_CREATE);access.require(PermissionCode.HANDOFF_READ);access.require(PermissionCode.ALARM_READ);
  int expected=parse(raw);
  Prepared prepared=transaction.execute(ignored->prepare(id,expected,key));
  DeliveryOutcome result;
  try{
   var row=prepared.handoff();
   result=channel.deliver(new HandoffDispatch(row.handoffId(),row.sourceKind(),row.sourceId(),row.handoffType(),
     row.recipientId(),prepared.target().recipientName(),prepared.material(),prepared.attempt().createdAt()));
   if(result==null||result.deliveryStatus()==null||result.receiptStatus()==null
      ||!HandoffRules.DELIVERY_STATUSES.contains(result.deliveryStatus())||!HandoffRules.RECEIPT_STATUSES.contains(result.receiptStatus())
      ||"PENDING_DELIVERY".equals(result.deliveryStatus()))result=unknown();
  }catch(RuntimeException error){result=unknown();}
  final DeliveryOutcome outcome=result;
  return transaction.execute(ignored->{
   repository.completeNotification(prepared.attempt().deliveryId(),outcome);
   record("handoff_notification_result",prepared.handoff().handoffId(),"delivery_id="+prepared.attempt().deliveryId()+"; delivery_status="+outcome.deliveryStatus()+"; receipt_status="+outcome.receiptStatus()+"; blocked_reason="+outcome.blockedReason());
   return HandoffReadService.dto(repository.latestDelivery(prepared.handoff().handoffId()));
  });
 }
 private Prepared prepare(String id,int expected,String key){
  HandoffRow row=visible(id,true);
  var source=events.lock(row.sourceId(),access.require(PermissionCode.ALARM_READ));
  if(source==null)throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","来源事件不存在或不在当前权限范围内");
  idempotency.claim(key,"handoff-notify:"+row.handoffId()+":"+expected);
  var latest=repository.latestDelivery(row.handoffId());
  if(latest==null||latest.attemptNo()!=expected)throw conflict("HANDOFF_NOTICE_CHANGED","通知记录已变化，请先查询最新结果");
  String reason=stateBlocker(row,latest);
  if(reason!=null)throw conflict("HANDOFF_NOTIFY_BLOCKED",reason);
  if(!"CONFIRMED".equals(source.state()))throw conflict("HANDOFF_NOTIFY_BLOCKED","当前事件不再属于已核实属实的事件");
  var target=directory.forHandoff(row.handoffType(),row.recipientId());
  reason=channelBlocker(row,target);if(reason!=null)throw conflict("HANDOFF_CHANNEL_UNAVAILABLE",reason);
  var snapshot=repository.snapshot(row.handoffId());
  if(snapshot==null)throw conflict("HANDOFF_MATERIAL_UNAVAILABLE","原交接材料缺失，不能发出通知");
  String material=material(snapshot.json());
  var at=clock.now().atOffset(ZoneOffset.UTC);
  var attempt=new DeliveryInsert(UUID.randomUUID().toString(),row.handoffId(),latest.attemptNo()+1,"SUBMITTED","PENDING","DELIVERY_IN_PROGRESS",at,null,null,null);
  repository.insertDelivery(attempt);
  repository.notificationRecipient(attempt.deliveryId(),encode(target));
  // 本次接收资料与尝试编号写入追加审计；不回写已冻结的交接/接收快照。
  record("handoff_notification_requested",row.handoffId(),"delivery_id="+attempt.deliveryId()+"; attempt_no="+attempt.attemptNo()+"; recipient_snapshot="+encode(target));
  return new Prepared(row,attempt,target,material);
 }
 private HandoffRow visible(String value,boolean lock){
  var decision=access.require(PermissionCode.HANDOFF_READ);String id=HandoffReadService.id(value);
  var row=lock?repository.lockNotification(id,decision):repository.find(id,decision);
  if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","交接记录不存在或不在当前权限范围内");
  if(!"UAV_EVENT".equals(row.sourceKind())||!"UAV_PUNISHMENT".equals(row.handoffType()))throw conflict("HANDOFF_NOTIFY_UNSUPPORTED","此入口仅用于通知处罚部门");
  return row;
 }
 private String stateBlocker(HandoffRow row,DeliveryRow latest){
  if(latest==null)return "投递记录缺失，请核对交接历史";
  if(repository.hasDelivered(row.handoffId()))return "已送达或已签收，无需重复通知";
  if("SUBMITTED".equals(latest.deliveryStatus())||"PENDING".equals(latest.receiptStatus())||"TIMEOUT".equals(latest.receiptStatus())
    ||(latest.submittedAt()!=null&&!"FAILED".equals(latest.deliveryStatus())))return "已提交或结果尚未确认，请查询送达与回执，不能重复通知";
  if(!Set.of("PENDING_DELIVERY","FAILED").contains(latest.deliveryStatus()))return "通知状态未知，请核对原记录";
  if(latest.blockedReason()!=null&&(latest.blockedReason().contains("UNKNOWN")||latest.blockedReason().contains("未知")))return "原发送结果未知，请先核对";
  return null;
 }
 private String channelBlocker(HandoffRow row,RecipientSnapshot target){
  if(repository.findEnabledRecipient(row.recipientId(),row.handoffType())==null)return "原处罚接收方已停用";
  if(target==null||!target.configured())return target==null||target.blockedReason()==null?"接收方通知配置不可用":target.blockedReason();
  if(!Objects.equals(target.recipientId(),row.recipientId()))return "通知接收方与原交接不一致";
  if(!"MOCK".equals(target.channelType())||!environment.acceptsProfiles(Profiles.of("local","test"))
    ||environment.acceptsProfiles(Profiles.of("prod","production"))||!Set.of("mock","replay").contains(row.sourceMode())||!channel.simulated())return "真实通知渠道尚未接通，当前来源不能使用模拟投递";
  return null;
 }
 private int parse(String raw){try{var body=json.readTree(raw==null?"":raw);if(body==null||!body.isObject()||body.size()!=1||!body.has("expected_attempt_no")||!body.get("expected_attempt_no").isIntegralNumber()||!body.get("expected_attempt_no").canConvertToInt()||body.get("expected_attempt_no").asInt()<0)throw new IllegalArgumentException();return body.get("expected_attempt_no").asInt();}catch(Exception error){throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请提供原通知次数 expected_attempt_no，不允许提交送达或回执结果");}}
 private String material(String raw){try{var value=json.readTree(raw);if(value.isTextual())value=json.readTree(value.textValue());if(!value.isObject())throw new IllegalArgumentException();return json.writeValueAsString(value);}catch(Exception error){throw conflict("HANDOFF_MATERIAL_UNAVAILABLE","原交接材料无法读取");}}
 private RecipientSnapshot savedRecipient(String deliveryId){String raw=repository.notificationRecipient(deliveryId);if(raw==null)return null;try{return json.readValue(raw,RecipientSnapshot.class);}catch(Exception error){throw new IllegalStateException("通知接收快照无法读取",error);}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
 private void record(String action,String id,String detail){var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),actor.roleCode(),"handoff",action,"handoff",id,detail,"SUCCESS","","");}
 private static DeliveryOutcome unknown(){return new DeliveryOutcome("SUBMITTED","PENDING",null,"DELIVERY_OUTCOME_UNKNOWN",null,null,null);}
 private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
