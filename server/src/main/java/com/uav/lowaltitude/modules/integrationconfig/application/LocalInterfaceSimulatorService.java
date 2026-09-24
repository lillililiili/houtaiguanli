package com.uav.lowaltitude.modules.integrationconfig.application;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository.Row;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.flight.application.LocalFlightPlanInputService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

@Service @Profile("(local | test) & !prod & !production")
public class LocalInterfaceSimulatorService {
 private final DeviceAccessPolicy interfaces;private final AccessControlService access;private final FlightReadService flights;
 private final LocalFlightPlanInputService input;private final LocalInterfaceRepository repository;
 private final RiskRepository risks;private final UavEventRepository events;private final HandoffRepository handoffs;
 private final ObjectMapper json;private final AppClock clock;private final AuditService audit;
 public LocalInterfaceSimulatorService(DeviceAccessPolicy interfaces,AccessControlService access,FlightReadService flights,LocalFlightPlanInputService input,LocalInterfaceRepository repository,RiskRepository risks,UavEventRepository events,HandoffRepository handoffs,ObjectMapper json,AppClock clock,AuditService audit){this.interfaces=interfaces;this.access=access;this.flights=flights;this.input=input;this.repository=repository;this.risks=risks;this.events=events;this.handoffs=handoffs;this.json=json;this.clock=clock;this.audit=audit;}
 @Transactional public Message plan(PlanInput p){
  var actor=interfaces.requireInterfacesOperate();repository.actorLock(actor.userId());
  // Existing-message reads still recheck the original business object's visibility.
  Row previous=repository.existing(actor.userId(),"FLIGHT_PLAN",p.messageId());
  if(previous!=null)return replay(previous,p);
  var result=input.create(p.routeVersionId(),p.uavSn(),p.startAt(),p.endAt());
  return save(p.messageId(),"FLIGHT_PLAN",result.get("plan_id"),actor.userId(),p,result);
 }
 @Transactional public Message weather(WeatherInput p){
  var actor=interfaces.requireInterfacesOperate();repository.actorLock(actor.userId());
  var plan=flights.flightPlan(p.planId());requireSimulated(plan.sourceMode());
  Row previous=repository.existing(actor.userId(),"WEATHER_FORECAST",p.messageId());if(previous!=null)return replay(previous,p);
  if(p.publishedAt()>clock.nowMillis()+30000)throw bad("预报发布时间不能在未来");
  long last=0;
  for(Period period:p.periods()){
   if(period.from()>=period.to()||period.from()<p.publishedAt()||period.from()<last||period.to()-p.publishedAt()>7*86400000L||!Double.isFinite(period.temperatureC())||!Double.isFinite(period.windSpeedMs())||!Double.isFinite(period.gustMs()))throw bad("预报时段须有序、不重叠且在发布时间后7天内，数值须有效");
   last=period.to();
  }
  return save(p.messageId(),"WEATHER_FORECAST",p.planId(),actor.userId(),p,Map.of("plan_id",p.planId(),"status","READY"));
 }
 @Transactional public Binding bind(BindingInput p){
  var actor=interfaces.requireInterfacesOperate();repository.actorLock(actor.userId());
  source(p.sourceKind(),p.sourceId(),true);
  var old=repository.binding(p.sourceKind(),p.sourceId());
  if(old!=null&&!old.actor().equals(actor.userId())&&old.enabled()&&old.expiresAt()>clock.nowMillis())throw conflict("对象已有其他操作人的模拟接收绑定");
  long expires=clock.nowMillis()+20*60000L;repository.bind(p.sourceKind(),p.sourceId(),actor.userId(),p.enabled(),expires);
  audit.record(actor.userId(),actor.account(),"local_interface_binding","local_interface",p.sourceId(),p.enabled()?"启用20分钟本地模拟接收":"停止本地模拟接收",null);
  return new Binding(p.sourceKind(),p.sourceId(),p.enabled(),expires);
 }
 @Transactional(readOnly=true) public Context context(){
  var actor=interfaces.requireInterfacesRead();var params=new LinkedMultiValueMap<String,String>();params.add("size","100");
  List<String> unavailable=new ArrayList<>();
  List<RouteOption> routeOptions=new ArrayList<>();
  try {
   for(var route:flights.routes(params).items())if(route.enabled()&&Set.of("mock","replay").contains(route.sourceMode())){
    var versions=flights.routeVersions(route.routeId(),params).items();
    for(var version:versions)if(version.validTo()==null||version.validTo()>clock.nowMillis()+300000){
     routeOptions.add(new RouteOption(version.routeVersionId(),route.routeId(),route.name(),route.routeNo(),version.validFrom(),version.validTo()));break;
    }
   }
  }catch(ApiException error){permissionSection(error,"航线",unavailable);}
  List<PlanOption> plans=List.of();
  try { plans=flights.flightPlans(params).items().stream().filter(p->Set.of("mock","replay").contains(p.sourceMode())).map(p->new PlanOption(p.planId(),p.planNo(),p.startAt(),p.endAt())).toList(); }
  catch(ApiException error){permissionSection(error,"飞行计划",unavailable);}
  List<SourceOption> sources=new ArrayList<>();
  try { sources.addAll(repository.sources(true,access.require(PermissionCode.RISK_READ))); }
  catch(ApiException error){permissionSection(error,"风险通知",unavailable);}
  try { sources.addAll(repository.sources(false,access.require(PermissionCode.ALARM_READ))); }
  catch(ApiException error){permissionSection(error,"告警事件",unavailable);}
  var messages=repository.messages(actor.userId()).stream().filter(this::visible).map(this::dto).toList();
  var bindings=repository.bindings(actor.userId(),clock.nowMillis()).stream().filter(b->{try{source(b.sourceKind(),b.sourceId(),false);return true;}catch(ApiException error){if(error.getStatus()==HttpStatus.NOT_FOUND||error.getStatus()==HttpStatus.FORBIDDEN)return false;throw error;}}).toList();
  return new Context(routeOptions,plans,bindings,messages,sources,unavailable);
 }
 private static void permissionSection(ApiException error,String section,List<String> warnings){if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error;warnings.add(section+"：无读取权限");}
 public void source(String kind,String id,boolean lock){
  String mode;
  if("RISK".equals(kind)){var permission=access.require(PermissionCode.RISK_READ);var row=lock?risks.lock(id,permission):risks.find(id,permission);if(row==null)throw missing();mode=row.sourceMode();}
  else if("UAV_EVENT".equals(kind)){var permission=access.require(PermissionCode.ALARM_READ);var row=lock?events.lock(id,permission):events.find(id,permission);if(row==null)throw missing();mode=row.sourceMode();}
  else throw bad("不支持的模拟通知来源");
  requireSimulated(mode);
 }
 private boolean visible(Row row){try{if("OUT".equals(row.direction())){var h=handoffs.find(row.subjectId(),access.require(PermissionCode.HANDOFF_READ));if(h==null)return false;source(h.sourceKind(),h.sourceId(),false);}else flights.flightPlan(row.subjectId());return true;}catch(ApiException e){if(e.getStatus()==HttpStatus.NOT_FOUND||e.getStatus()==HttpStatus.FORBIDDEN)return false;throw e;}}
 private Message replay(Row row,Object input){if(!visible(row))throw missing();if(!tree(row.payload()).equals(json.valueToTree(input)))throw conflict("同一消息编号的内容已变化，请使用新编号");return dto(row);}
 private Message save(String external,String kind,String subject,String actor,Object payload,Object result){String id=UUID.randomUUID().toString();Row row=new Row(id,external,kind,"IN",subject,actor,"ACCEPTED",encode(payload),encode(result),clock.nowMillis(),0);repository.insert(row);var user=com.uav.lowaltitude.platform.security.AuthContext.require();audit.record(user.userId(),user.account(),"local_interface_input","local_interface",id,"接收模拟"+kind+"; subject_id="+subject,null);return dto(row);}
 public Message dto(Row row){
  String state=row.state();var result=tree(row.result());
  if("OUT".equals(row.direction())&&"SUBMITTED".equals(state)){
   var delivery=handoffs.latestDelivery(row.subjectId());
   if(delivery==null||!(LocalInterfaceChannel.MARKER+row.id()).equals(delivery.blockedReason())){
    state="UNKNOWN";result=json.valueToTree(Map.of("message","平台投递记录未完成关联，结果未知，请核对原通知；不能重发或补造签收"));
   }
  }
  return new Message(row.id(),row.kind(),row.direction(),row.subjectId(),state,row.version(),row.createdAt(),tree(row.payload()),result);
 }
 private com.fasterxml.jackson.databind.JsonNode tree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("模拟消息读取失败",e);}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 public static void requireSimulated(String mode){if(!Set.of("mock","replay").contains(mode))throw new ApiException(HttpStatus.CONFLICT,"SIMULATION_SCOPE_REQUIRED","本地模拟接口只接受模拟或回放对象");}
 public static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
 public static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"SIMULATION_CONFLICT",message);}
 public static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","对象不存在或不在当前权限范围");}
}
