package com.uav.lowaltitude.modules.handoff.application;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.*;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** Legacy internal-task factory, retained for compatibility fixtures. Current jobs use rule-gated HandoffSubmissionService. */
@Service
public class HandoffFollowupService {
 private static final AccessDecision SYSTEM=new AccessDecision("system:uav-followup",ScopeMode.ALL);
 private final HandoffFollowupRepository followups; private final HandoffRepository handoffs;
 private final UavEventRepository events;private final NoCounterRepository noCounter;private final AutoVoiceRepository voice;
 private final DisposalCompletionPort disposals;private final HandoffMaterialAssembler materials;
 private final AppClock clock;private final AuditService audit;private final ObjectMapper json;
 public HandoffFollowupService(HandoffFollowupRepository followups,HandoffRepository handoffs,UavEventRepository events,NoCounterRepository noCounter,
   AutoVoiceRepository voice,DisposalCompletionPort disposals,HandoffMaterialAssembler materials,AppClock clock,AuditService audit,ObjectMapper json){
  this.followups=followups;this.handoffs=handoffs;this.events=events;this.noCounter=noCounter;this.voice=voice;this.disposals=disposals;this.materials=materials;this.clock=clock;this.audit=audit;this.json=json;
 }
 public List<String> candidates(){return followups.candidates();}
 @Transactional public void create(String eventId){
  if(eventId==null||!followups.eligible(eventId))return;
  var event=events.lock(eventId,SYSTEM);
  if(event==null||!"CONFIRMED".equals(event.state())||handoffs.existingPunishment(eventId)!=null)return;
  // A flight conclusion cannot end field handling while a device remains active or unverified.
  if(!noCounter.operationBlock(eventId).isEmpty())return;
  boolean newEvent=followups.eventCreatedAfterCutoff(eventId);long cutoff=followups.cutoff();
  Object fact=null;
  var completion=disposals.punishmentCompletion(eventId);
  if(completion!=null&&(newEvent||completion.completedAt()!=null&&completion.completedAt()>=cutoff)){
   var completed=json.createObjectNode().put("kind",completion.triggerSource());
   if(completion.completedAt()!=null)completed.put("completed_at",completion.completedAt());
   if(completion.authorizationId()!=null)completed.put("authorization_id",completion.authorizationId());
   completed.set("disposals",json.valueToTree(handoffs.eventDisposals(eventId)));fact=completed;
  }
  var decision=noCounter.decision(eventId);
  if(fact==null&&decision!=null&&(newEvent||decision.at()>=cutoff)&&noCounter.active(eventId))fact=java.util.Map.of("kind","NO_COUNTER","completed_at",decision.at(),"decision",decision);
  var left=voice.departure(eventId);
  if(fact==null&&left!=null&&(newEvent||left.evaluatedAt()>=cutoff)&&"LEFT".equals(left.presence())&&left.position()!=null&&left.position().longitude()!=null&&left.position().latitude()!=null)
   fact=java.util.Map.of("kind","LEFT","completed_at",left.evaluatedAt(),"observation",left);
  if(fact==null)return;
  String snapshot=null;
  var frozen=json.valueToTree(fact);
  try{snapshot=encode(materials.assemble(eventId,true));}
  catch(com.uav.lowaltitude.platform.api.ApiException unavailable){
   ((com.fasterxml.jackson.databind.node.ObjectNode)frozen).put("material_blocked_reason",unavailable.getMessage());
   ((com.fasterxml.jackson.databind.node.ObjectNode)frozen).put("material_blocked_code",unavailable.getCode());
  }
  var at=clock.now().atOffset(ZoneOffset.UTC);String id=UUID.randomUUID().toString();
  handoffs.insertHandoff(new HandoffInsert(id,"UAV_EVENT",eventId,null,eventId,"UAV_PUNISHMENT",null,event.version(),event.ownerOrgId(),event.districtId(),materials.sourceMode(eventId),null,at,"FIELD_HANDLING_ENDED"));
  if(snapshot!=null)handoffs.insertSnapshot(id,HandoffMaterialAssembler.SCHEMA_V2,snapshot,at);
  followups.insert(id,eventId,encode(frozen));
  handoffs.insertDelivery(new DeliveryInsert(UUID.randomUUID().toString(),id,1,"PENDING_DELIVERY","NOT_EXPECTED","PENDING_NOTIFICATION_DECISION",at,null,null,null));
  audit.record(null,"AUTO_FOLLOWUP","SYSTEM","handoff","handoff_followup_created","handoff",id,"event_id="+eventId+"; field_handling="+encode(frozen),"SUCCESS","","");
 }
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
}
