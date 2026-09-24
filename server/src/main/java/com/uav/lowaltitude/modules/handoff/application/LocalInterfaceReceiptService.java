package com.uav.lowaltitude.modules.handoff.application;
import java.time.ZoneOffset;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceChannel;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.application.RiskNotificationService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import static com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService.*;

@Service @Profile("(local | test) & !prod & !production")
public class LocalInterfaceReceiptService {
 private final LocalInterfaceRepository messages;private final HandoffRepository handoffs;private final DeviceAccessPolicy interfaces;
 private final AccessControlService access;private final LocalInterfaceSimulatorService simulator;private final ObjectMapper json;
 private final AppClock clock;private final AuditService audit;private final RiskRepository risks;private final RiskNotificationService notifications;
 public LocalInterfaceReceiptService(LocalInterfaceRepository messages,HandoffRepository handoffs,DeviceAccessPolicy interfaces,AccessControlService access,LocalInterfaceSimulatorService simulator,ObjectMapper json,AppClock clock,AuditService audit,RiskRepository risks,RiskNotificationService notifications){this.messages=messages;this.handoffs=handoffs;this.interfaces=interfaces;this.access=access;this.simulator=simulator;this.json=json;this.clock=clock;this.audit=audit;this.risks=risks;this.notifications=notifications;}
 @Transactional public Message accept(String id,ReceiptInput input){
  var actor=interfaces.requireInterfacesOperate();var row=messages.lock(id);
  if(row==null||!row.actor().equals(actor.userId()))throw missing();
  if(!"OUT".equals(row.direction()))throw conflict("输入消息不能提交通知回执");
  var decision=access.require(PermissionCode.HANDOFF_READ);
  var h=handoffs.find(row.subjectId(),decision);if(h==null)throw missing();
  requireSimulated(h.sourceMode());simulator.source(h.sourceKind(),h.sourceId(),true);
  h=handoffs.lockNotification(row.subjectId(),decision);if(h==null)throw missing();
  if(row.state().equals(input.outcome()))return simulator.dto(row);
  if(row.version()!=input.expectedVersion())throw conflict("消息已变化，请刷新后处理");
  var delivery=handoffs.latestDelivery(h.handoffId());String marker=LocalInterfaceChannel.MARKER+id;
  if(delivery==null||!marker.equals(delivery.blockedReason()))throw conflict("本条消息不对应当前待处理投递，不能覆盖历史回执");
  String old=row.state(),next=input.outcome();
  boolean allowed=switch(next){
   case "DELIVERED" -> Set.of("SUBMITTED","TIMEOUT").contains(old);
   case "ACKNOWLEDGED" -> "DELIVERED".equals(old)||("TIMEOUT".equals(old)&&delivery.deliveredAt()!=null);
   case "FAILED" -> "SUBMITTED".equals(old);
   case "TIMEOUT" -> Set.of("SUBMITTED","DELIVERED").contains(old);
   default -> false;
  };
  if(!allowed)throw conflict("回执顺序不允许：先确认送达再签收，成功事实不能回退");
  var at=clock.now().atOffset(ZoneOffset.UTC);var delivered=delivery.deliveredAt();var acknowledged=delivery.acknowledgedAt();
  String ds=delivery.deliveryStatus(),rs="PENDING",reason=marker;
  switch(next){
   case "DELIVERED" -> {ds="DELIVERED";if(delivered==null)delivered=at;}
   case "ACKNOWLEDGED" -> {ds="DELIVERED";rs="ACKNOWLEDGED";acknowledged=at;reason=null;}
   case "FAILED" -> {ds="FAILED";rs="NOT_EXPECTED";reason="LOCAL_SIMULATOR_REJECTED";}
   case "TIMEOUT" -> rs="TIMEOUT";
  }
  var outcome=new DeliveryOutcome(ds,rs,null,reason,delivery.submittedAt(),delivered,acknowledged);
  handoffs.completeLocalSimulatorReceipt(delivery.deliveryId(),marker,outcome);
  if("ACKNOWLEDGED".equals(next)&&"RISK".equals(h.sourceKind())){
   var risk=risks.lock(h.sourceId(),access.require(PermissionCode.RISK_READ));
   if(risk!=null&&"NOTIFIED".equals(risk.state()))notifications.acknowledged(risk.riskId(),risk.version(),h.handoffId(),at);
  }
  var result=new LinkedHashMap<String,Object>();result.put("handoff_id",h.handoffId());result.put("delivery_id",delivery.deliveryId());result.put("delivery_status",ds);result.put("receipt_status",rs);result.put("simulated",true);result.put("received_at",clock.nowMillis());
  try{if(messages.receipt(id,row.version(),next,json.writeValueAsString(result),clock.nowMillis())!=1)throw conflict("消息版本已变化");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  audit.record(actor.userId(),actor.account(),"local_interface_receipt","handoff",h.handoffId(),"模拟回执 "+next+"; message_id="+id+"; delivery_id="+delivery.deliveryId(),null);
  return simulator.dto(messages.find(id));
 }
}
