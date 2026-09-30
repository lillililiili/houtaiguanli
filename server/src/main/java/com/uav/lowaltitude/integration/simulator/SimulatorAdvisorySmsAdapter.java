package com.uav.lowaltitude.integration.simulator;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import com.uav.lowaltitude.modules.alarm.application.AdvisorySmsPort;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;

@Component @Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.notifications",name="transport",havingValue="simulator")
public class SimulatorAdvisorySmsAdapter implements AdvisorySmsPort {
 private final RealtimeNotificationTransport transport;private final AutoSmsRepository tasks;
 private final ObjectProvider<NotificationDirectoryService> directory;private final ObjectMapper json;
 public SimulatorAdvisorySmsAdapter(RealtimeNotificationTransport transport,AutoSmsRepository tasks,ObjectProvider<NotificationDirectoryService> directory,ObjectMapper json){this.transport=transport;this.tasks=tasks;this.directory=directory;this.json=json;}
 public boolean simulationAvailable(String mode){return Set.of("mock","replay").contains(mode==null?"":mode)&&transport.online();}
 public Delivery simulate(String mode,String recipient,String content){throw new IllegalStateException("数据模拟器通知必须使用已关联事件与独立发送编号");}
 public Delivery simulateAutomatic(String mode,String recipientName,String content,String key){
  if(!simulationAvailable(mode)||key==null||!key.startsWith("auto-advisory:"))throw new IllegalStateException("数据模拟器接收端不可用");
  String event=key.substring("auto-advisory:".length());var task=tasks.find(event);
  if(task==null||!"SENDING".equals(task.status())||!key.equals(task.providerKey())||task.token()==null)throw new IllegalStateException("短信发送尝试已失效");
  var recipient=directory.getObject().advisoryHistoryRecipient("ADVISORY_SMS",event);
  if(recipient==null)throw new IllegalStateException("短信接收快照缺失");
  transport.submit("ADVISORY_SMS",event,key+":"+task.token(),json.valueToTree(Map.of("provider_key",key,"claim_token",task.token(),"recipient",recipient,"content",content,"requested_at",task.updatedAt())));
  return new Delivery(true,"SUBMITTED");
 }
}
