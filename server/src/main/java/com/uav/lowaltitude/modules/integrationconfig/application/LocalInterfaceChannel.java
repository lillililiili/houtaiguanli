package com.uav.lowaltitude.modules.integrationconfig.application;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository.Row;
import com.uav.lowaltitude.platform.time.AppClock;
/** Durable pull inbox for the explicitly bound local external-system simulator. */
@Service @Profile("(local | test) & !prod & !production")
public class LocalInterfaceChannel {
 public static final String MARKER="LOCAL_SIMULATOR_WAITING:";
 private final LocalInterfaceRepository repository;private final ObjectMapper json;private final AppClock clock;
 public LocalInterfaceChannel(LocalInterfaceRepository repository,ObjectMapper json,AppClock clock){this.repository=repository;this.json=json;this.clock=clock;}
 public DeliveryOutcome offer(HandoffDispatch dispatch){
  if(!Set.of("RISK_NOTICE","UAV_PUNISHMENT").contains(dispatch.handoffType()))return null;
  var binding=repository.binding(dispatch.sourceKind(),dispatch.sourceId());
  if(binding==null)return null;
  if(!binding.enabled()||binding.expiresAt()<=clock.nowMillis())return new DeliveryOutcome("PENDING_DELIVERY","NOT_EXPECTED",null,"LOCAL_SIMULATOR_RECEIVER_STOPPED",null,null,null);
  String id=UUID.randomUUID().toString();
  try{
   String payload=json.writeValueAsString(dispatch);
   repository.insert(new Row(id,id,dispatch.handoffType(),"OUT",dispatch.handoffId(),binding.actor(),"SUBMITTED",payload,"{}",clock.nowMillis(),0));
   return new DeliveryOutcome("SUBMITTED","PENDING",null,MARKER+id,dispatch.at(),null,null);
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("无法保存外部模拟通知",e);}
 }
}
