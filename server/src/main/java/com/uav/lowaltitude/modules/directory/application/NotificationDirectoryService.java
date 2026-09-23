package com.uav.lowaltitude.modules.directory.application;

import java.util.*;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsPolicy;
import com.uav.lowaltitude.modules.alarm.application.AutoVoicePolicy;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.*;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.SettingRow;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** 统一的是对象关联和历史摘要；每条业务仍由原有状态机判断能否通知。 */
@Service
public class NotificationDirectoryService {
 public static final String SUPERIOR_RECIPIENT="fixed-superior-recipient";
 private static final Set<String> GLOBAL=Set.of("RISK_NOTICE","ADVISORY_SMS","ADVISORY_VOICE");
 private final AutoSmsPolicy smsPolicy;private final AutoVoicePolicy voicePolicy;private final AdvisoryVoiceRecording recordings;
 private final DirectoryRepository repo;private final AccessService access;private final IdempotencyGuard idempotency;
 private final AppClock clock;private final AuditService audit;private final Environment env;private final HandoffChannelPort channel;
 public NotificationDirectoryService(DirectoryRepository repo,AccessService access,IdempotencyGuard idempotency,AppClock clock,AuditService audit,Environment env,HandoffChannelPort channel,AutoSmsPolicy smsPolicy,AutoVoicePolicy voicePolicy,AdvisoryVoiceRecording recordings){this.smsPolicy=smsPolicy;this.voicePolicy=voicePolicy;this.recordings=recordings;this.repo=repo;this.access=access;this.idempotency=idempotency;this.clock=clock;this.audit=audit;this.env=env;this.channel=channel;}
 @Transactional(readOnly=true) public Page<NotificationSetting> list(String purpose,int page,int size){access.require("notificationSettings.read");DirectoryService.pages(page,size);var rows=repo.settings(purpose,page,size,currentScope());return new Page<>(rows.items().stream().map(this::view).toList(),page,size,rows.total());}
 @Transactional(readOnly=true) public NotificationSetting get(String id){access.require("notificationSettings.read");var row=require(id);requireScope(row);return view(row);}
 @Transactional(readOnly=true) public Diagnostics diagnostics(String id){access.require("notificationSettings.read");var setting=require(id);requireScope(setting);var view=view(setting);String reason=view.blockedReason();
  if(reason==null&&"ADVISORY_SMS".equals(setting.purpose())&&!smsPolicy.enabled())reason="后台自动短信服务尚未启用";
  if(reason==null&&"ADVISORY_VOICE".equals(setting.purpose())){if(!voicePolicy.enabled())reason="后台电话录音通知服务尚未启用";else if(recordings.current()==null)reason="未配置有效的已有 WAV 录音文件、模板名称和文稿";}
  return new Diagnostics(id,reason==null?view.availability():"UNAVAILABLE",reason,view.historyCount(),repo.settingPending(id),setting.bindingId()==null?0:repo.bindingPlans(setting.bindingId()));}
 @Transactional public NotificationSetting create(NotificationInput input,String key){access.require("notificationSettings.auth");if(GLOBAL.contains(input.purpose()))throw bad("此通知用途为全局唯一配置，请修改已有配置");var body=validate(input);idempotency.claim(key,"notification-create:"+repo.encode(body));String id=UUID.randomUUID().toString(),recipient=null;
  if("UAV_PUNISHMENT".equals(body.purpose())){recipient=UUID.randomUUID().toString();repo.insertRecipient(recipient,repo.organization(body.recipientOrgId()).name());}
  try{repo.insertSetting(id,routingKey(body),recipient,body,clock.nowMillis());}catch(DataIntegrityViolationException e){throw conflict("NOTIFICATION_DUPLICATE","此用途与接收对象已有配置，请修改现有记录");}audit("notification_setting_created",id,"purpose="+body.purpose()+"; enabled="+body.enabled());return view(require(id));
 }
 @Transactional public NotificationSetting update(String id,NotificationInput input,String key){access.require("notificationSettings.auth");var before=require(id);requireScope(before);if(input.expectedVersion()==null)throw bad("修改配置需要expected_version");if(!before.purpose().equals(input.purpose()))throw bad("通知用途不可修改");var body=validate(input);
  if(repo.settingHistory(id)>0&&(!Objects.equals(before.orgId(),body.recipientOrgId())||!Objects.equals(before.bindingId(),body.sourceBindingId())))throw conflict("NOTIFICATION_TARGET_IN_USE","已有发送历史，不能替换本配置的接收单位或来源关联；请保留历史并建立新对象配置");
  idempotency.claim(key,"notification-update:"+id+":"+repo.encode(body));try{if(repo.updateSetting(id,routingKey(body),body,clock.nowMillis())!=1)throw conflict("VERSION_CONFLICT","通知配置已更新，请刷新后重试");}catch(DataIntegrityViolationException e){throw conflict("NOTIFICATION_DUPLICATE","此用途与接收对象已有配置");}
  if("UAV_PUNISHMENT".equals(body.purpose()))repo.updateRecipient(before.recipientId(),repo.organization(body.recipientOrgId()).name(),body.enabled());
  audit("notification_setting_updated",id,"previous_version="+before.version()+"; channel_type="+body.channelType()+"; enabled="+body.enabled());return view(require(id));
 }
 private NotificationInput validate(NotificationInput b){String org=blank(b.recipientOrgId()),contact=blank(b.contactId()),binding=blank(b.sourceBindingId());
  if(GLOBAL.contains(b.purpose())&&(org!=null||contact!=null||binding!=null))throw bad("上级及飞手通知为全局用途，不能配置固定单位、联系人或来源范围");
  if("PLAN_FEEDBACK".equals(b.purpose())){if(binding==null)throw bad("计划反馈必须选择该来源的报送单位关联");var source=repo.binding(binding);if(source==null)throw bad("来源关联不存在");if(org!=null&&!org.equals(source.orgId()))throw bad("计划反馈单位必须是来源关联的报送单位");org=source.orgId();}
  else if(binding!=null)throw bad("只有计划反馈可以配置来源关联");
  if(Set.of("UAV_PUNISHMENT","DEVICE_MAINTENANCE").contains(b.purpose())&&org==null)throw bad("此用途必须选择接收单位");
  if(org!=null&&(repo.organization(org)==null||!repo.orgVisible(org,currentScope())))throw new ApiException(HttpStatus.NOT_FOUND,"DIRECTORY_NOT_FOUND","接收单位不存在或不在管理范围内");
  if(contact!=null){var c=repo.contact(contact);if(c==null||!Objects.equals(c.orgId(),org))throw bad("联系人必须属于所选接收单位");String role=switch(b.purpose()){case "PLAN_FEEDBACK"->"PLAN_LIAISON";case "DEVICE_MAINTENANCE"->"MAINTENANCE";default->"UNIT_LIAISON";};if(!c.roles().contains(role))throw bad("联系人不具备本通知用途的业务角色");}
  String endpoint=blank(b.endpointRef());if(endpoint!=null&&(endpoint.contains("://")||!endpoint.matches("[a-zA-Z0-9_.:/-]{1,256}")))throw bad("端点引用只能填写部署配置标识，不能填写URL、手机号或凭据");
  if(Boolean.TRUE.equals(b.enabled())&&"NONE".equals(b.channelType()))throw bad("启用前必须选择渠道类型");
  if(Boolean.TRUE.equals(b.enabled())&&!"MOCK".equals(b.channelType())&&endpoint==null)throw bad("启用前必须填写部署端点引用");
  if("ADVISORY_SMS".equals(b.purpose())&&!Set.of("NONE","MOCK","SMS").contains(b.channelType()))throw bad("飞手短信用途只能配置短信或模拟渠道");
  if("ADVISORY_VOICE".equals(b.purpose())&&!Set.of("NONE","MOCK","VOICE").contains(b.channelType()))throw bad("飞手语音用途只能配置语音或模拟渠道");
  if(!Set.of("ADVISORY_SMS","ADVISORY_VOICE").contains(b.purpose())&&!Set.of("NONE","MOCK","API").contains(b.channelType()))throw bad("单位通知当前支持系统接口或模拟渠道");
  return new NotificationInput(b.purpose(),org,contact,binding,b.channelType(),endpoint,b.enabled(),b.validUntil(),b.expectedVersion());
 }
 private String routingKey(NotificationInput b){return GLOBAL.contains(b.purpose())?b.purpose():b.purpose()+":"+("PLAN_FEEDBACK".equals(b.purpose())?b.sourceBindingId():b.recipientOrgId());}
 private NotificationSetting view(SettingRow row){String reason=reason(row);String availability=reason==null?"SIMULATED":"UNAVAILABLE";return new NotificationSetting(row.id(),row.purpose(),row.orgId(),row.contactId(),row.bindingId(),row.recipientId(),name(row),row.orgName(),row.contactName(),mask(row.phone()),row.sourceName(),row.channelType(),row.endpointRef(),row.enabled(),row.validUntil(),row.version(),availability,reason,repo.settingHistory(row.id()),template(row.purpose()),templateVersion(row.purpose()),receipt(row.purpose()));}
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
 private void requireScope(SettingRow row){if(row.orgId()!=null&&!repo.orgVisible(row.orgId(),currentScope()))throw new ApiException(HttpStatus.NOT_FOUND,"NOTIFICATION_SETTING_NOT_FOUND","通知配置不存在或不在管理范围内");}
 private void audit(String action,String id,String detail){var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),actor.roleCode(),"notificationSettings",action,"notification_setting",id,detail,"SUCCESS","","");}
 public static String mask(String phone){if(phone==null||phone.isBlank())return null;String value=phone.replaceAll("[ ()-]","");if(value.length()<7)return "已登记联系方式";return value.substring(0,3)+"****"+value.substring(value.length()-4);}
 private static String blank(String v){return DirectoryRepository.blank(v);}
 private static ApiException bad(String m){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",m);}
 private static ApiException conflict(String code,String m){return new ApiException(HttpStatus.CONFLICT,code,m);}
}
