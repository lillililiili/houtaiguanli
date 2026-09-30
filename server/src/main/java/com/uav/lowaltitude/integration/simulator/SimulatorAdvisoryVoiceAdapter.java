package com.uav.lowaltitude.integration.simulator;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import com.uav.lowaltitude.modules.alarm.application.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;

@Component @Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.notifications",name="transport",havingValue="simulator")
public class SimulatorAdvisoryVoiceAdapter implements AdvisoryVoicePort {
 private final RealtimeNotificationTransport transport;private final AutoVoiceRepository tasks;
 private final ObjectProvider<NotificationDirectoryService> directory;private final ObjectMapper json;
 public SimulatorAdvisoryVoiceAdapter(RealtimeNotificationTransport transport,AutoVoiceRepository tasks,ObjectProvider<NotificationDirectoryService> directory,ObjectMapper json){this.transport=transport;this.tasks=tasks;this.directory=directory;this.json=json;}
 public boolean simulationAvailable(String mode){return Set.of("mock","replay").contains(mode==null?"":mode)&&transport.online();}
 public Delivery simulate(String mode,AdvisoryVoiceRecording.Recording recording,String key){
  if(!simulationAvailable(mode)||key==null||!key.startsWith("auto-advisory-voice:"))throw new IllegalStateException("数据模拟器电话接收端不可用");
  String event=key.substring("auto-advisory-voice:".length());var task=tasks.find(event);
  if(task==null||!"CALLING".equals(task.status())||!key.equals(task.providerKey())||task.token()==null||!task.recordingMatches(recording))throw new IllegalStateException("电话发送尝试或录音已变更");
  var recipient=directory.getObject().advisoryHistoryRecipient("ADVISORY_VOICE",event);
  if(recipient==null)throw new IllegalStateException("电话接收快照缺失");
  transport.submit("ADVISORY_VOICE",event,key+":"+task.token(),json.valueToTree(Map.of("provider_key",key,"claim_token",task.token(),"recipient",recipient,"recording",recording,"requested_at",task.updatedAt())));
  return new Delivery(true,"SUBMITTED",null,null,null);
 }
}
