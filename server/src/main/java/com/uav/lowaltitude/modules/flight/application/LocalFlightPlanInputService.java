package com.uav.lowaltitude.modules.flight.application;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.flight.infrastructure.LocalFlightPlanInputRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
@Service @Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
public class LocalFlightPlanInputService {
 private final FlightReadService flights;private final LocalFlightPlanInputRepository repository;private final AppClock clock;
 public LocalFlightPlanInputService(FlightReadService flights,LocalFlightPlanInputRepository repository,AppClock clock){this.flights=flights;this.repository=repository;this.clock=clock;}
 @Transactional public Map<String,String> create(String version,String serial,long start,long end,String requestedMode,String requestedStatus){
  String mode=requestedMode==null?"mock":requestedMode;
  if(!Set.of("mock","replay").contains(mode))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","本地计划输入仅支持 mock 或 replay 来源");
  String status=requestedStatus==null?"PENDING":requestedStatus;
  if(!Set.of("PENDING","EXECUTING","COMPLETED","CANCELLED").contains(status))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","模拟计划状态无效");
  long now=clock.nowMillis();
  if(("EXECUTING".equals(status)&&(start>now||end<=now))||("COMPLETED".equals(status)&&end>now))
   throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","模拟执行状态与计划时间不符");
  var rv=flights.routeVersion(version);var route=flights.route(rv.routeId());
  if(!route.enabled()||!Set.of("mock","replay").contains(route.sourceMode())||route.ownerOrgId()==null||route.districtId()==null)throw new ApiException(HttpStatus.CONFLICT,"SIMULATION_SCOPE_REQUIRED","请选择有归属且启用的模拟航线");
  if(start>=end||end-start>7*86400000L||start<rv.validFrom()||(rv.validTo()!=null&&end>rv.validTo()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","计划时间须在航线有效期内，起止有序且不超过7天");
  String id=UUID.randomUUID().toString(),no=repository.nextPlanNo();
  repository.insert(id,no,serial,version,route.ownerOrgId(),route.districtId(),start,end,now,mode,status);
  var result=new LinkedHashMap<String,String>();result.put("plan_id",id);result.put("plan_no",no);result.put("route_version_id",version);result.put("source_mode",mode);result.put("status_code",status);return result;
 }
}
