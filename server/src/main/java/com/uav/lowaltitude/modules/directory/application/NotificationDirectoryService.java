package com.uav.lowaltitude.modules.directory.application;

import java.util.*;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.*;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.SettingRow;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** 统一的是对象关联和历史摘要；每条业务仍由原有状态机判断能否通知。 */
@Service
public class NotificationDirectoryService {
 public static final String SUPERIOR_RECIPIENT="fixed-superior-recipient";
 private final DirectoryRepository repo;private final AppClock clock;private final Environment env;private final HandoffChannelPort channel;
 public NotificationDirectoryService(DirectoryRepository repo,AppClock clock,Environment env,HandoffChannelPort channel){this.repo=repo;this.clock=clock;this.env=env;this.channel=channel;}
 private String reason(SettingRow row){long now=clock.nowMillis();if(!row.enabled())return "通知配置尚未启用";if(row.validUntil()!=null&&row.validUntil()<=now)return "通知配置已超过有效期";
  if(row.bindingId()!=null&&(repo.binding(row.bindingId())==null||!Objects.equals(repo.binding(row.bindingId()).orgId(),row.orgId())||!row.bindingEnabled()||!row.sourceEnabled()))return "计划来源或单位关联不可用";
  if(row.contactId()!=null){var c=repo.contact(row.contactId());if(c==null||!Objects.equals(c.orgId(),row.orgId())||!c.roles().contains(contactRole(row.purpose())))return "接收联系人业务角色或单位关联已变更";}
  if(row.contactId()!=null&&(!row.contactEnabled()||(row.contactValidUntil()!=null&&row.contactValidUntil()<=now)))return "接收联系人已停用或超过有效期";
  if("NONE".equals(row.channelType()))return "通知渠道尚未配置";
  if(!"MOCK".equals(row.channelType()))return "正式通知渠道尚未接通";
  if(!simulationEnvironment())return "当前环境不允许使用模拟通知渠道";
  return null;
 }
 private String name(SettingRow row){if("RISK_NOTICE".equals(row.purpose()))return "上级";if(Set.of("ADVISORY_SMS","ADVISORY_VOICE").contains(row.purpose()))return "关联计划执行飞手";return row.orgName()!=null?row.orgName():row.legacyName();}
 public RecipientSnapshot forPlan(String planId){var plan=repo.subjectInternal(planId);if(plan==null)return missing(null,null,"计划不存在");if(plan.bindingId()==null)return missing(plan.sourceId(),null,"计划尚未关联报送单位");var setting=repo.settingForBinding(plan.bindingId());if(setting==null)return missing(plan.sourceId(),plan.reportingOrgName(),"报送单位尚未配置计划反馈渠道");var snapshot=snapshot(setting,plan.sourceId(),plan.reportingOrgName());if(!repo.bindingSourceEnabled(plan.bindingId()))return blocked(snapshot,"计划来源或单位关联不可用");return snapshot;}
 public RecipientSnapshot forHandoff(String type,String recipientId){if("RISK_NOTICE".equals(type)){if(recipientId!=null&&!recipientId.isBlank()&&!SUPERIOR_RECIPIENT.equals(recipientId))throw bad("风险通知统一通知上级，不能指定其他接收单位");return snapshot(require("risk-superior"),SUPERIOR_RECIPIENT,"上级");}
  var row=repo.settingForRecipient(recipientId);if(row==null)return missing(recipientId,null,"接收单位尚未配置通知渠道");return snapshot(row,recipientId,name(row));
 }
 public RecipientSnapshot forPilotPlan(String purpose,String planId){if(!Set.of("ADVISORY_SMS","ADVISORY_VOICE").contains(purpose))throw bad("飞手通知用途无效");var row=require("ADVISORY_SMS".equals(purpose)?"advisory-sms":"advisory-voice");var plan=repo.subjectInternal(planId);if(plan==null||plan.pilotId()==null)return missing(null,null,"未找到该计划的执行飞手联系人");var c=repo.contact(plan.pilotId());if(c==null||!Objects.equals(c.orgId(),plan.operatorOrgId())||!c.roles().contains("PILOT"))return missing(null,null,"飞手身份与计划运营单位未形成有效关联");String reason=reason(row);if(!c.enabled()||(c.validUntil()!=null&&c.validUntil()<=clock.nowMillis()))reason="执行飞手联系人不可用";if(blank(c.phone())==null||c.verifiedAt()==null||c.verifiedAt()>clock.nowMillis()||blank(c.verificationBasis())==null)reason="执行飞手联系方式尚未有效核验";return new RecipientSnapshot(c.contactId(),c.name(),c.orgId(),c.orgName(),c.contactId(),c.name(),mask(c.phone()),row.channelType(),row.endpointRef(),row.id(),row.version(),reason==null,reason,clock.nowMillis(),template(row.purpose()),templateVersion(row.purpose()),receipt(row.purpose()),c.version());}
 /** 调用方已完成当前事件/目标/时效守卫；仍核对同一最新评估、同一事件与计划范围。 */
 public RecipientSnapshot currentPilotEvent(String purpose,String eventId){return forPilotEvent(purpose,eventId,repo.latestEventEvaluation(eventId));}
 public RecipientSnapshot forPilotEvent(String purpose,String eventId,String evaluationId){String plan=repo.eventPlan(eventId,evaluationId);if(plan==null)return missing(null,null,"当前事件尚无精确关联计划，不能把单位联系人当作执行飞手");var target=forPilotPlan(purpose,plan);var original=repo.advisoryTaskSnapshot(purpose,eventId);if(original!=null&&(!Objects.equals(original.recipientId(),target.recipientId())||!Objects.equals(original.contactVersion(),target.contactVersion())||!Objects.equals(original.configVersion(),target.configVersion())))return blocked(target,"接收飞手或通知配置已变更，不能沿用同一通知编号更换对象重发");return target;}
 @Transactional public void freezeAdvisoryTask(String purpose,String eventId,RecipientSnapshot target){repo.freezeAdvisoryTask(purpose,eventId,target);}
 @Transactional public void freezeAdvisoryRecord(String purpose,String recordId,RecipientSnapshot target){repo.freezeAdvisoryRecord(purpose,recordId,target);}
 public RecipientSnapshot advisoryHistoryRecipient(String purpose,String eventId){return repo.advisoryTaskSnapshot(purpose,eventId);}
 public RecipientSnapshot forMaintenance(String selectedSettingId){
  SettingRow row=blank(selectedSettingId)==null?repo.soleMaintenanceSetting(currentScope()):repo.setting(selectedSettingId);
  return row!=null&&row.orgId()!=null&&!repo.orgVisible(row.orgId(),currentScope())?missing(null,null,"所选运维通知对象不在当前可用范围内")
    :row!=null&&!"DEVICE_MAINTENANCE".equals(row.purpose())?missing(null,null,"所选配置不是设备运维通知用途，请核查通知设置")
    :row==null?missing(null,null,"尚未确定唯一的运维通知对象，请明确配置接收单位"):snapshot(row,row.id(),name(row));
 }
 public String maintenanceBlocker(RecipientSnapshot target,String sourceMode){
  if(!target.configured())return target.blockedReason();
  if(!"MOCK".equals(target.channelType())||!simulationEnvironment()||!Set.of("mock","replay").contains(sourceMode)||!channel.simulated())return "通知渠道尚未接通或不允许此数据来源";
  return null;
 }
 public MaintenanceOutcome dispatchMaintenance(String taskId,String attemptId,RecipientSnapshot target,String sourceMode,String material){
  String blocked=maintenanceBlocker(target,sourceMode);
  if(blocked!=null)return new MaintenanceOutcome(unavailable(blocked),"NOT_SENT");
  var at=clock.now().atOffset(java.time.ZoneOffset.UTC);
  try {
   var result=channel.deliver(new HandoffDispatch(attemptId,"DEVICE_MAINTENANCE",taskId,"DEVICE_MAINTENANCE",target.recipientId(),target.recipientName(),material,at));
   if(result==null||result.deliveryStatus()==null||!Set.of("PENDING_DELIVERY","SUBMITTED","DELIVERED","FAILED").contains(result.deliveryStatus())
       ||result.receiptStatus()==null||!Set.of("NOT_EXPECTED","PENDING","ACKNOWLEDGED","TIMEOUT").contains(result.receiptStatus()))return unknownMaintenance();
   String state=Set.of("DELIVERED","FAILED").contains(result.deliveryStatus())?"COMPLETED":"SUBMITTED".equals(result.deliveryStatus())?"SUBMITTED":"UNKNOWN";
   return new MaintenanceOutcome(result,state);
  }catch(RuntimeException error){return unknownMaintenance();}
 }
 private MaintenanceOutcome unknownMaintenance(){return new MaintenanceOutcome(new DeliveryOutcome("PENDING_DELIVERY","NOT_EXPECTED",null,"通知结果未知，请核对原发送记录后处理，暂不允许再次通知",null,null,null),"UNKNOWN");}
 @Transactional public void freezeMaintenance(String taskId,RecipientSnapshot target,DeliveryOutcome result){repo.maintenanceNotice(taskId,target,result);}
 public record MaintenanceOutcome(DeliveryOutcome result,String state) { }
 public DirectoryRepository.MaintenanceNotice maintenanceNotice(String taskId){return repo.maintenanceNotice(taskId);}
 public DeliveryOutcome deliver(RecipientSnapshot target,String sourceMode,HandoffDispatch dispatch){if(!target.configured())return unavailable(target.blockedReason());if(!"MOCK".equals(target.channelType())||!simulationEnvironment()||!Set.of("mock","replay").contains(sourceMode)||!channel.simulated())return unavailable("通知渠道尚未接通或不允许此数据来源");try{var result=channel.deliver(dispatch);return result==null||result.deliveryStatus()==null||result.receiptStatus()==null
    ||!HandoffRules.DELIVERY_STATUSES.contains(result.deliveryStatus())||!HandoffRules.RECEIPT_STATUSES.contains(result.receiptStatus())
    ||"PENDING_DELIVERY".equals(result.deliveryStatus())?unknownDelivery():result;}catch(RuntimeException e){return unknownDelivery();}}
 @Transactional public void freezeHandoff(String id,RecipientSnapshot snapshot){repo.freeze("handoff",id,snapshot);}
 @Transactional public void freezeFeedback(String id,RecipientSnapshot snapshot){repo.freeze("flight_plan_feedback",id,snapshot);}
 public RecipientSnapshot handoffSnapshot(String id){return repo.decodeSnapshot(repo.snapshot("handoff",id));}
 public RecipientSnapshot feedbackSnapshot(String id){return repo.decodeSnapshot(repo.snapshot("flight_plan_feedback",id));}
 private RecipientSnapshot snapshot(SettingRow row,String recipient,String name){String reason=reason(row);return new RecipientSnapshot(recipient,name,row.orgId(),row.orgName(),row.contactId(),row.contactName(),mask(row.phone()),row.channelType(),row.endpointRef(),row.id(),row.version(),reason==null,reason,clock.nowMillis(),template(row.purpose()),templateVersion(row.purpose()),receipt(row.purpose()),row.contactId()==null?null:repo.contact(row.contactId()).version());}
 private RecipientSnapshot missing(String id,String name,String reason){return new RecipientSnapshot(id,name,null,null,null,null,null,"NONE",null,null,null,false,reason,clock.nowMillis());}
 private RecipientSnapshot blocked(RecipientSnapshot s,String reason){return new RecipientSnapshot(s.recipientId(),s.recipientName(),s.orgId(),s.orgName(),s.contactId(),s.contactName(),s.contactHint(),s.channelType(),s.endpointRef(),s.settingId(),s.configVersion(),false,reason,s.capturedAt(),s.templateCode(),s.templateVersion(),s.receiptRequirement(),s.contactVersion());}
 private DeliveryOutcome unknownDelivery(){return new DeliveryOutcome("SUBMITTED","PENDING",null,"DELIVERY_OUTCOME_UNKNOWN",null,null,null);}
 private DeliveryOutcome unavailable(String reason){return new DeliveryOutcome("PENDING_DELIVERY","NOT_EXPECTED",null,reason,null,null,null);}
 private boolean simulationEnvironment(){return env.acceptsProfiles(Profiles.of("local","test"))&&!env.acceptsProfiles(Profiles.of("prod","production"));}
 private static String contactRole(String purpose){return switch(purpose){case "PLAN_FEEDBACK"->"PLAN_LIAISON";case "DEVICE_MAINTENANCE"->"MAINTENANCE";default->"UNIT_LIAISON";};}
 private static int templateVersion(String purpose){return "UAV_PUNISHMENT".equals(purpose)?2:1;}
 private static String template(String purpose){return switch(purpose){case "PLAN_FEEDBACK"->"PLAN_DEVICE_CHECK_V1";case "RISK_NOTICE"->"RISK_SUPERIOR_NOTICE_V1";case "ADVISORY_SMS"->"PILOT_ADVISORY_SMS_V1";case "ADVISORY_VOICE"->"PILOT_EXISTING_RECORDING_V1";case "UAV_PUNISHMENT"->"UAV_PUNISHMENT_MATERIAL_V2";default->"DEVICE_MAINTENANCE_NOTICE_V1";};}
 private static String receipt(String purpose){return switch(purpose){case "ADVISORY_SMS"->"送达结果单独记录，不代表飞手已读或飞离";case "ADVISORY_VOICE"->"接通与录音播放完成分别记录，不代表飞手已听取";case "UAV_PUNISHMENT"->"签收结果单独记录，不代表处罚办结";case "DEVICE_MAINTENANCE"->"通知送达与设备处理结果分别记录";default->"送达、签收与对方处理结果分别记录";};}
 private SettingRow require(String id){var row=repo.setting(id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"NOTIFICATION_SETTING_NOT_FOUND","通知配置不存在");return row;}
 private com.uav.lowaltitude.modules.identity.domain.AccessDecision currentScope(){var actor=AuthContext.require();return new com.uav.lowaltitude.modules.identity.domain.AccessDecision(actor.userId(),com.uav.lowaltitude.modules.identity.domain.ScopeMode.valueOf(actor.scopeMode()));}
 public static String mask(String phone){if(phone==null||phone.isBlank())return null;String value=phone.replaceAll("[ ()-]","");if(value.length()<7)return "已登记联系方式";return value.substring(0,3)+"****"+value.substring(value.length()-4);}
 private static String blank(String v){return DirectoryRepository.blank(v);}
 private static ApiException bad(String m){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",m);}
}
