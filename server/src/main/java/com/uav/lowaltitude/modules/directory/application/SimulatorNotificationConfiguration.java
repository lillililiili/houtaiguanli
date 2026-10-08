package com.uav.lowaltitude.modules.directory.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.OrgOption;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.PunishmentRecipient;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Explicit authenticated switch of the old expiring QA transport, never recipient data.
 * D-1（2026-10-08 rose 定"自动配"）：接收端连上时，三条还没配过的全局通道（飞手短信、飞手电话、通知上级）一并接到数据模拟器；
 * 处罚发给哪个单位系统猜不了，由模拟器"实时收发设置"里选的处罚接收单位决定。调用方只在带数据模拟器的测试模式下开放。
 */
@Service
public class SimulatorNotificationConfiguration {
 static final int MAX_PUNISHMENT_RECIPIENTS=5;
 private final DirectoryRepository repository; private final AccessService access;
 private final AuditService audit; private final AppClock clock;
 public SimulatorNotificationConfiguration(DirectoryRepository repository,AccessService access,AuditService audit,AppClock clock){
  this.repository=repository;this.access=access;this.audit=audit;this.clock=clock;
 }
 private void authorize(){access.require("notificationSettings.auth");access.require("organizations.auth");}
 @Transactional public void activate(){
  authorize();
  long now=clock.nowMillis();
  int switched=repository.activateSimulatorChannels(now), prepared=repository.prepareSimulatorGlobalChannels(now);
  if(switched+prepared>0){var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),"notification_simulator_connected","local_interface","receiver",
   "切换既有测试通知通道至独立数据模拟器；配置数="+switched+"；自动接通未配置的飞手短信、飞手电话、通知上级通道数="+prepared,null);}
 }
 public record PunishmentRecipients(List<OrgOption> organizations,List<String> selected,List<PunishmentRecipient> enabledRecipients){}
 @Transactional(readOnly=true) public PunishmentRecipients punishmentRecipients(){authorize();return view();}
 /** 选中的单位接到数据模拟器并启用；之前在这里选过、这次没选的只停用接收方，已提交的移送照旧能查能补发。 */
 @Transactional public PunishmentRecipients selectPunishmentRecipients(List<String> orgIds){
  authorize();
  var wanted=new LinkedHashSet<String>(orgIds==null?List.of():orgIds);
  if(wanted.size()>MAX_PUNISHMENT_RECIPIENTS)throw bad("处罚接收单位最多选 "+MAX_PUNISHMENT_RECIPIENTS+" 个");
  var names=new HashMap<String,String>();for(var org:repository.enabledOrganizations())names.put(org.orgId(),org.name());
  for(String org:wanted)if(!names.containsKey(org))throw bad("所选处罚接收单位不存在或已停用，请重新读取单位列表");
  var existing=new HashMap<String,DirectoryRepository.SimulatorPunishment>();for(var row:repository.simulatorPunishments())existing.put(row.settingId(),row);
  long now=clock.nowMillis();int enabled=0,disabled=0;
  for(String org:wanted){
   String id=recipientId(org);var row=existing.get(id);
   if(row!=null&&row.recipientEnabled()&&row.settingEnabled()&&"API".equals(row.channelType())&&DirectoryRepository.SIMULATOR_ENDPOINT.equals(row.endpointRef()))continue;
   repository.enableRecipient(id,names.get(org));repository.simulatorPunishmentSetting(id,org,row!=null,now);enabled++;
  }
  for(var row:existing.values())if(!wanted.contains(row.orgId()))disabled+=repository.disableRecipient(row.recipientId());
  if(enabled+disabled>0){var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),"notification_simulator_punishment_recipients","local_interface","receiver",
   "数据模拟器处罚接收单位：启用 "+enabled+" 个，停用 "+disabled+" 个；当前选择="+String.join(",",wanted),null);}
  return view();
 }
 private PunishmentRecipients view(){
  var selected=repository.simulatorPunishments().stream().filter(DirectoryRepository.SimulatorPunishment::recipientEnabled).map(DirectoryRepository.SimulatorPunishment::orgId).toList();
  return new PunishmentRecipients(repository.enabledOrganizations(),selected,repository.enabledPunishmentRecipients());
 }
 static String recipientId(String orgId){return UUID.nameUUIDFromBytes((DirectoryRepository.SIMULATOR_PUNISHMENT_ROUTE+orgId).getBytes(StandardCharsets.UTF_8)).toString();}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
}
