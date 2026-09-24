package com.uav.lowaltitude.modules.integrationconfig.application;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.WeatherInput;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
/** Called only after the normal plan reader has authorized the plan. */
@Service @Profile("(local | test) & !prod & !production")
public class LocalForecastReadService {
 private final LocalInterfaceRepository repository;private final ObjectMapper json;
 public LocalForecastReadService(LocalInterfaceRepository repository,ObjectMapper json){this.repository=repository;this.json=json;}
 public ForecastAvailability read(String planId){
  var row=repository.weather(planId);if(row==null)return null;
  try{var p=json.readValue(row.payload(),WeatherInput.class);var periods=p.periods().stream().map(x->new ForecastPeriod(x.from(),x.to(),x.summary(),x.temperatureC(),x.windSpeedMs(),x.gustMs(),x.windDirectionDeg(),x.precipitationProbabilityPct(),x.humidityPct())).toList();
   return new ForecastAvailability(planId,"READY",null,new Forecast(p.areaName(),"外部接口模拟器",p.publishedAt(),"mock",periods));
  }catch(Exception e){throw new IllegalStateException("外部模拟预报无法读取",e);}
 }
}
