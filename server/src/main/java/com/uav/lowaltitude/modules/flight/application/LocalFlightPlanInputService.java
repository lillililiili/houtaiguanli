package com.uav.lowaltitude.modules.flight.application;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.flight.infrastructure.LocalFlightPlanInputRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
@Service @Profile("(local | test) & !prod & !production")
public class LocalFlightPlanInputService {
 private final FlightReadService flights;private final LocalFlightPlanInputRepository repository;private final AppClock clock;
 public LocalFlightPlanInputService(FlightReadService flights,LocalFlightPlanInputRepository repository,AppClock clock){this.flights=flights;this.repository=repository;this.clock=clock;}
 @Transactional public Map<String,String> create(String version,String serial,long start,long end){
  var rv=flights.routeVersion(version);var route=flights.route(rv.routeId());
  if(!route.enabled()||!Set.of("mock","replay").contains(route.sourceMode())||route.ownerOrgId()==null||route.districtId()==null)throw new ApiException(HttpStatus.CONFLICT,"SIMULATION_SCOPE_REQUIRED","请选择有归属且启用的模拟航线");
  if(start>=end||end-start>7*86400000L||start<rv.validFrom()||(rv.validTo()!=null&&end>rv.validTo()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","计划时间须在航线有效期内，起止有序且不超过7天");
  String id=UUID.randomUUID().toString(),no="EXT-SIM-"+id;
  repository.insert(id,no,serial,version,route.ownerOrgId(),route.districtId(),start,end,clock.nowMillis());
  return Map.of("plan_id",id,"plan_no",no,"source_mode","mock");
 }
}
