package com.uav.lowaltitude.modules.flight.application;

import java.util.*;
import java.util.function.IntFunction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.*;
import com.uav.lowaltitude.modules.flight.infrastructure.LocalFlightPlanInputRepository;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.*;
import com.uav.lowaltitude.modules.directory.application.DirectoryService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Service @Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
public class LocalPlanFilingService {
 private final FlightReadService flights;private final LocalFlightPlanInputRepository repository;
 private final DirectoryService directory;private final AppClock clock;
 public LocalPlanFilingService(FlightReadService flights,LocalFlightPlanInputRepository repository,DirectoryService directory,AppClock clock){this.flights=flights;this.repository=repository;this.directory=directory;this.clock=clock;}
 @Transactional(readOnly=true) public Detail detail(String id){var plan=flights.flightPlan(id);simulated(plan.sourceMode());return new Detail(plan,directory.subjects(id));}
 @Transactional public Map<String,Object> save(String id,long version,Filing data){
  var plan=flights.flightPlan(id);simulated(plan.sourceMode());
  if(plan.version()!=version)throw conflict("VERSION_CONFLICT","计划已更新，请重新读取后补录");
  if((data.takeoffLongitude()==null)!=(data.takeoffLatitude()==null)||(data.landingLongitude()==null)!=(data.landingLatitude()==null))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","起降点经纬度必须成对填写");
  String source=blank(data.sourceId());
  if(plan.source()!=null&&!Objects.equals(plan.source().sourceId(),source))throw conflict("PLAN_SOURCE_IMMUTABLE","已有计划来源不可替换或清空");
  if(source!=null&&!repository.sourceAvailable(source))throw conflict("SIMULATION_SOURCE_REQUIRED","请选择已启用的模拟或回放计划来源");
  var before=directory.subjects(id);
  if(repository.updateFiling(id,version,data,clock.nowMillis())!=1)throw conflict("VERSION_CONFLICT","计划已更新，请重新读取后补录");
  // Reuse the existing directory authorization and association rules, within this transaction.
  if(blank(data.sourceBindingId())!=null||blank(data.operatorOrgId())!=null||blank(data.pilotContactId())!=null||before.sourceBindingId()!=null||before.operatorOrgId()!=null||before.pilotContactId()!=null)
   directory.updateSubjects(id,new SubjectInput(blank(data.sourceBindingId()),blank(data.operatorOrgId()),blank(data.pilotContactId()),version+1,"模拟器提交计划资料"),UUID.randomUUID().toString());
  return Map.of("plan_id",id,"plan_no",plan.planNo(),"source_mode",plan.sourceMode(),"version",flights.flightPlan(id).version());
 }
 @Transactional(readOnly=true) public Options options(){
  var warnings=new ArrayList<String>();List<Option> orgs=List.of();List<Pilot> pilots=List.of();List<SourceBinding> bindings=List.of();
  try{
   orgs=all(p->directory.options("organizations",null,null,p,100));
   pilots=all(p->directory.contacts(null,null,true,p,100)).stream().filter(c->c.roles().contains("PILOT")&&(c.validUntil()==null||c.validUntil()>clock.nowMillis())).map(c->new Pilot(c.contactId(),c.orgId(),c.name())).toList();
   bindings=all(p->directory.bindings(null,null,null,p,100)).stream().filter(SourceBinding::enabled).toList();
  }catch(ApiException e){if(e.getStatus()!=HttpStatus.FORBIDDEN)throw e;warnings.add("单位与飞手档案：无读取权限，可填写申报名称；关联档案需要单位管理权限");}
  var sources=new ArrayList<>(repository.sources());
  if(!repository.sourceExists(com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.SIMULATOR_SOURCE_ID))sources.add(0,new Source(com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.SIMULATOR_SOURCE_ID,com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.SIMULATOR_SOURCE_NAME,"mock"));
  var ids=sources.stream().map(Source::sourceId).collect(java.util.stream.Collectors.toSet());
  return new Options(sources,orgs,pilots,bindings.stream().filter(b->ids.contains(b.sourceId())).toList(),warnings);
 }
 private static <T> List<T> all(IntFunction<Page<T>> read){var result=new ArrayList<T>();int page=1;while(true){var batch=read.apply(page++);result.addAll(batch.items());if(batch.items().isEmpty()||result.size()>=batch.total())return result;}}
 private static void simulated(String mode){if(!Set.of("mock","replay").contains(mode))throw conflict("SIMULATION_SCOPE_REQUIRED","仅允许补录模拟或回放计划");}
 private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
 private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
