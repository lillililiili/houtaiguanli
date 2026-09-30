package com.uav.lowaltitude.modules.handoff.infrastructure;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;

@Component @Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.handoff",name="channel",havingValue="simulator")
public class SimulatorHandoffChannel implements HandoffChannelPort {
 private final RealtimeNotificationTransport transport;private final ObjectMapper json;
 public SimulatorHandoffChannel(RealtimeNotificationTransport transport,ObjectMapper json){this.transport=transport;this.json=json;}
 public boolean simulated(){return true;}
 public DeliveryOutcome deliver(HandoffDispatch dispatch){
  var message=transport.submit(dispatch.handoffType(),dispatch.handoffId(),dispatch.handoffType()+":"+dispatch.handoffId()+":"+dispatch.at().toInstant().toEpochMilli(),json.valueToTree(dispatch));
  return new DeliveryOutcome("SUBMITTED","PENDING",null,RealtimeNotificationTransport.MARKER+message.messageId(),dispatch.at(),null,null);
 }
}
