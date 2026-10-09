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
  if(plan.version()!=version)throw conflict("VERSION_CONFLICT","任务已更新，请重新读取后补录");
  if((data.takeoffLongitude()==null)!=(data.takeoffLatitude()==null)||(data.landingLongitude()==null)!=(data.landingLatitude()==null))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","起降点经纬度必须成对填写");
  String source=blank(data.sourceId());
  if(plan.source()!=null&&!Objects.equals(plan.source().sourceId(),source))throw conflict("PLAN_SOURCE_IMMUTABLE","已有任务来源不可替换或清空");
  validateSource(data);validateCarried(data);
  var before=directory.subjects(id);
  if(repository.updateFiling(id,version,data,clock.nowMillis())!=1)throw conflict("VERSION_CONFLICT","任务已更新，请重新读取后补录");
  // Reuse the existing directory authorization and association rules, within this transaction.
  String binding=blank(data.sourceBindingId()),operator=blank(data.operatorOrgId()),pilot=blank(data.pilotContactId());
  String basis=carriedBasis(data,plan.planNo());
  try{
   if(binding==null&&blank(data.reportingOrgCode())!=null)binding=reportingBinding(data,basis);
   if(pilot==null&&blank(data.pilotPhone())!=null){if(operator==null)operator=operatorOrg(data,basis);pilot=pilotContact(operator,data,basis);}
  }catch(ApiException e){
   // The generic denial does not say which part of the task needs the directory permission.
   if(e.getStatus()!=HttpStatus.FORBIDDEN||!"FORBIDDEN".equals(e.getCode()))throw e;
   throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","任务带了飞手手机号或报送单位，平台要据此找或建档案并关联，需要单位管理权限；请换有单位管理权限的账号提交，或去掉这几项只保存申报名称");
  }
  if(binding!=null||operator!=null||pilot!=null||before.sourceBindingId()!=null||before.operatorOrgId()!=null||before.pilotContactId()!=null)
   directory.updateSubjects(id,new SubjectInput(binding,operator,pilot,version+1,"模拟器提交任务资料"),UUID.randomUUID().toString());
  return Map.of("plan_id",id,"plan_no",plan.planNo(),"source_mode",plan.sourceMode(),"version",flights.flightPlan(id).version());
 }
 /*
  * D-2（2026-10-08 用户确认“模拟器带上”）：执行飞手和报送单位由上级任务带来，平台不设录入页。收任务时按编码找报送单位、
  * 按手机号在运营单位里找飞手档案，找不到就按任务资料建好并写明随上级任务下发，再和任务关联。任务资料里已选了档案的项不动；
  * 找到的档案照旧走关联校验（停用的飞手、停用的关联照样拒收）；同名单位不止一个时不替用户猜。
  */
 private void validateCarried(Filing data){
  if(blank(data.pilotContactId())==null&&blank(data.pilotPhone())!=null){
   if(blank(data.pilotName())==null)throw bad("任务带了飞手手机号，还需要飞手姓名");
   if(blank(data.operatorOrgId())==null&&blank(data.operatorName())==null)throw bad("任务带了飞手手机号，还需要申报单位名称，用来找或建飞手所属单位");
  }
  if(blank(data.sourceBindingId())==null&&blank(data.reportingOrgName())!=null&&blank(data.reportingOrgCode())==null)throw bad("报送单位需要编码，平台按编码找报送单位");
  if(blank(data.sourceBindingId())==null&&blank(data.reportingOrgCode())!=null&&blank(data.sourceId())==null)throw bad("报送单位要和任务来源一起提供");
 }
 private String carriedBasis(Filing data,String planNo){String source=blank(data.sourceId());return "随上级任务下发："+(source==null?"":repository.sourceName(source)+" ")+"任务 "+planNo;}
 private String reportingBinding(Filing data,String basis){
  String source=blank(data.sourceId()),code=blank(data.reportingOrgCode());
  var found=all(p->directory.bindings(null,source,code,p,100)).stream().filter(b->code.equals(b.externalOrgCode())).findFirst();
  if(found.isPresent())return found.get().bindingId();
  var org=all(p->directory.organizations(code,p,100)).stream().filter(o->code.equals(o.orgCode())).findFirst().map(Organization::orgId).orElse(null);
  if(org==null){String name=blank(data.reportingOrgName());org=directory.createOrganization(new OrganizationInput(code,name==null?code:name,null,"OTHER",null,null,basis,null,null),UUID.randomUUID().toString()).orgId();}
  return directory.createBinding(new BindingInput(source,code,org,true,null),UUID.randomUUID().toString()).bindingId();
 }
 private String operatorOrg(Filing data,String basis){
  String name=blank(data.operatorName());
  var same=all(p->directory.organizations(name,p,100)).stream().filter(o->name.equals(o.name())).toList();
  if(same.size()>1)throw conflict("OPERATOR_ORG_AMBIGUOUS","同名的单位“"+name+"”不止一个，请在任务资料里选择报备单位档案");
  if(same.size()==1)return same.get(0).orgId();
  return directory.createOrganization(new OrganizationInput(null,name,null,"OPERATOR",null,null,basis,null,null),UUID.randomUUID().toString()).orgId();
 }
 private String pilotContact(String operator,Filing data,String basis){
  String phone=phone(data.pilotPhone());
  var pilots=all(p->directory.contacts(operator,null,null,p,100)).stream().filter(c->c.roles().contains("PILOT")&&phone.equals(phone(c.phone()))).toList();
  long now=clock.nowMillis();
  // Prefer a usable record; a disabled one is still linked so the association check reports it instead of a duplicate being made.
  var usable=pilots.stream().filter(c->c.enabled()&&(c.validUntil()==null||c.validUntil()>now)).findFirst();
  if(usable.isPresent())return usable.get().contactId();
  if(!pilots.isEmpty())return pilots.get(0).contactId();
  return directory.createContact(new ContactInput(operator,blank(data.pilotName()),List.of("PILOT"),blank(data.pilotPhone()),null,null,true,null,now,basis,null),UUID.randomUUID().toString()).contactId();
 }
 public void validateSource(Filing data){
  String source=blank(data.sourceId());
  if(source!=null&&!repository.sourceAvailable(source))throw conflict("SIMULATION_SOURCE_REQUIRED","请选择已启用的模拟或回放任务来源");
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
 private static void simulated(String mode){if(!Set.of("mock","replay").contains(mode))throw conflict("SIMULATION_SCOPE_REQUIRED","仅允许补录模拟或回放任务");}
 private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
 private static String phone(String s){return s==null?"":s.replaceAll("[ ()-]","");}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
 private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
