package com.uav.lowaltitude.integration.mock;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.Forecast;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.ForecastPeriod;

/** Explicit, finite demo batch. Reads never renew its publication time or write business facts. */
@Component
public class MockWeatherForecast {
    public static final long DURATION_MILLIS=24*60*60*1000L;
    private final Environment environment;
    public MockWeatherForecast(Environment environment) { this.environment=environment; }
    public boolean available() {
        return environment.acceptsProfiles(Profiles.of("(local | test) & !prod & !production"));
    }
    public Forecast forecast(String areaName, long publishedAt) {
        if (!available()) throw new IllegalStateException("Weather simulation is disabled in this environment");
        return new Forecast(areaName,"天气模拟服务",publishedAt,"mock",List.of(
            period(publishedAt,0,"晴",24,2.6,4.1,135,5,56),
            period(publishedAt,1,"多云",23,3.2,5.0,150,15,61),
            period(publishedAt,2,"多云",21,3.8,5.6,165,25,67),
            period(publishedAt,3,"小雨",20,4.1,6.2,180,75,82),
            period(publishedAt,4,"阴",21,3.0,4.8,195,35,76),
            period(publishedAt,5,"多云",23,2.4,3.9,180,10,64)));
    }
    private static ForecastPeriod period(long base,int index,String summary,double temperature,
            double wind,double gust,int direction,int rain,int humidity) {
        long start=base+index*4*60*60*1000L;
        return new ForecastPeriod(start,start+4*60*60*1000L,summary,temperature,wind,gust,direction,rain,humidity);
    }
}
