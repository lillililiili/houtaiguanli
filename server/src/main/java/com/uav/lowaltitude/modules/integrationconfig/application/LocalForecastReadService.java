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
  for(var row:repository.weatherMessages())try{
   var p=json.readValue(row.payload(),WeatherInput.class);
   if((p.planId()!=null&&p.planId().equals(plan.planId()))||areaMatches(p.areaName(),plan.districtName())){
    var periods=p.periods().stream().map(x->new ForecastPeriod(x.from(),x.to(),x.summary(),x.temperatureC(),x.windSpeedMs(),x.gustMs(),x.windDirectionDeg(),x.precipitationProbabilityPct(),x.humidityPct())).toList();
    return new ForecastAvailability(plan.planId(),"READY",null,new Forecast(p.areaName(),"外部接口模拟器",p.publishedAt(),plan.sourceMode(),periods));
   }
  }catch(Exception e){throw new IllegalStateException("外部模拟预报无法读取",e);}
  return null;
 }
 private static boolean areaMatches(String area,String district){
  String a=normalize(area),d=normalize(district);return !a.isEmpty()&&!d.isEmpty()&&(a.equals(d)||a.contains(d)||d.contains(a));
 }
 private static String normalize(String value){return value==null?"":value.trim().replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT);}
}
