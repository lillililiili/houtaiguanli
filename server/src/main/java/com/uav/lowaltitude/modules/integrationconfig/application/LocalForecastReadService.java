package com.uav.lowaltitude.modules.integrationconfig.application;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.WeatherInput;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.FlightPlanDto;
/** Called only after the normal plan reader has authorized the plan. */
@Service @Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
public class LocalForecastReadService {
 private final LocalInterfaceRepository repository;private final ObjectMapper json;
 public LocalForecastReadService(LocalInterfaceRepository repository,ObjectMapper json){this.repository=repository;this.json=json;}
 public ForecastAvailability read(FlightPlanDto plan){
  if(plan.startAt()==null||plan.endAt()==null||plan.startAt()>=plan.endAt()) return null;
  // CDX-P06：同一区域常常每半小时交一份预报，和计划时间重叠的每一份都列出来（以前只取最近收到的一份）；
  // 同一个时段收到更新的预报只留发布时间最新的那份，按开始时间排好。
  var slots=new java.util.HashMap<String,ForecastPeriod>();
  WeatherInput newest=null;
  for(var row:repository.weatherMessages())try{
   var p=json.readValue(row.payload(),WeatherInput.class);
   boolean matchesPlan=(p.planId()!=null&&p.planId().equals(plan.planId()))||areaMatches(p.areaName(),plan.districtName());
   // Select a forecast by its valid periods, not its publication/receipt time.
   // Keep the forecast's other periods for history and partial-coverage display.
   boolean overlaps=p.periods()!=null&&p.periods().stream().anyMatch(x->valid(x)&&x.from()<plan.endAt()&&x.to()>plan.startAt());
   if(!matchesPlan||!overlaps) continue;
   if(newest==null||p.publishedAt()>newest.publishedAt()) newest=p;
   for(var x:p.periods()){
    if(!valid(x)) continue;
    String slot=x.from()+"|"+x.to();var known=slots.get(slot);
    // 发布时间相同时留先读到的，也就是后收到的那份（按接收时间倒序读）。
    if(known==null||known.publishedAt()<p.publishedAt())
     slots.put(slot,new ForecastPeriod(x.from(),x.to(),x.summary(),x.temperatureC(),x.windSpeedMs(),x.gustMs(),x.windDirectionDeg(),x.precipitationProbabilityPct(),x.humidityPct(),p.publishedAt()));
   }
  }catch(Exception e){throw new IllegalStateException("外部模拟预报无法读取",e);}
  if(newest==null) return null;
  var periods=slots.values().stream().sorted(java.util.Comparator.comparingLong(ForecastPeriod::from).thenComparingLong(ForecastPeriod::to)).toList();
  return new ForecastAvailability(plan.planId(),"READY",null,new Forecast(newest.areaName(),"外部接口模拟器",newest.publishedAt(),plan.sourceMode(),periods));
 }
 private static boolean valid(com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.Period x){return x!=null&&x.from()!=null&&x.to()!=null&&x.from()<x.to();}
 private static boolean areaMatches(String area,String district){
  String a=normalize(area),d=normalize(district);return !a.isEmpty()&&!d.isEmpty()&&(a.equals(d)||a.contains(d)||d.contains(a));
 }
 private static String normalize(String value){return value==null?"":value.trim().replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT);}
}
