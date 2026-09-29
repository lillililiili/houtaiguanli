package com.uav.lowaltitude.modules.integrationconfig.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.awt.geom.Line2D;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalWeatherRiskController.Input;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository.Row;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalWeatherRiskRepository;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;
import static com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService.*;

@Service @Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(name="app.weather-risk.qa.enabled",havingValue="true")
public class LocalWeatherRiskService {
    private final DeviceAccessPolicy interfaces;
    private final AccessControlService access;
    private final FlightReadService flights;
    private final LocalInterfaceRepository messages;
    private final LocalWeatherRiskRepository weather;
    private final RiskIngestionService ingestion;
    private final SimulationPolicy simulation;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;
    public LocalWeatherRiskService(DeviceAccessPolicy interfaces,AccessControlService access,FlightReadService flights,
            LocalInterfaceRepository messages,LocalWeatherRiskRepository weather,RiskIngestionService ingestion,
            SimulationPolicy simulation,ObjectMapper json,AppClock clock,AuditService audit) {
        this.interfaces=interfaces;this.access=access;this.flights=flights;this.messages=messages;this.weather=weather;
        this.ingestion=ingestion;this.simulation=simulation;this.json=json;this.clock=clock;this.audit=audit;
    }
    @Transactional public Map<String,Object> receive(Input input) {
        simulation.requireSimulation();
        var actor=interfaces.requireInterfacesOperate();
        access.require(PermissionCode.RISK_READ);
        // Recheck original plan visibility even for retries; scope and source mode are never caller supplied.
        var plan=flights.flightPlan(input.planId());requireSimulated(plan.sourceMode());
        messages.actorLock(actor.userId());
        var prior=messages.existing(actor.userId(),"WEATHER_RISK",input.messageId());
        try {
            String payload=json.writeValueAsString(input);
            if(prior!=null) {
                if(!json.readTree(prior.payload()).equals(json.readTree(payload)))throw conflict("同一气象风险消息编号的内容已变化");
                return json.readValue(prior.result(),new TypeReference<Map<String,Object>>(){});
            }
            long now=clock.nowMillis();validate(input,now);
            weather.ensureSource(now);
            var received=Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC);
            String risk=ingestion.ingest(new TrustedRiskFact(LocalWeatherRiskRepository.SOURCE,actor.userId()+":"+input.messageId(),
                plan.planId(),plan.route().routeVersionId(),null,null,null,"WEATHER",input.severity(),input.reasonCode(),
                "【本地QA模拟气象风险，非真实预警】"+input.reasonText(),Instant.ofEpochMilli(input.publishedAt()).atOffset(ZoneOffset.UTC),
                received,null,null,"mock"));
            weather.insertFact(risk,input);
            Map<String,Object> result=Map.of("risk_id",risk,"plan_id",plan.planId(),"source_mode","mock","state","ACCEPTED");
            messages.insert(new Row(UUID.randomUUID().toString(),input.messageId(),"WEATHER_RISK","IN",plan.planId(),actor.userId(),
                "ACCEPTED",payload,json.writeValueAsString(result),now,0));
            audit.record(actor.userId(),actor.account(),"local_weather_risk_input","risk",risk,"接收本地QA模拟气象风险",null);
            return result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    static void validate(Input p,long now) {
        if(p.publishedAt()>now || p.validFrom()<p.publishedAt() || p.validTo()<=p.validFrom()
                || p.validTo()-p.publishedAt()>7*86400000L)throw bad("气象发布时间不能在未来，有效时段须有序且在发布时间后7天内");
        var ring=p.polygon();int n=ring.size()-1;
        for(var point:ring)if(point.size()!=2 || point.stream().anyMatch(v->v==null||!Double.isFinite(v))
                || Math.abs(point.get(0))>180 || Math.abs(point.get(1))>90)throw bad("气象范围须为有效WGS84坐标");
        if(!ring.get(0).equals(ring.get(n)))throw bad("气象范围必须闭合");
        double area=0;
        for(int i=0;i<n;i++) {
            var a=ring.get(i);var b=ring.get(i+1);
            if(a.equals(b))throw bad("气象范围不得包含零长度边");
            area+=a.get(0)*b.get(1)-b.get(0)*a.get(1);
            for(int j=i+2;j<n;j++) {
                if(i==0&&j==n-1)continue;
                var c=ring.get(j);var d=ring.get(j+1);
                if(Line2D.linesIntersect(a.get(0),a.get(1),b.get(0),b.get(1),c.get(0),c.get(1),d.get(0),d.get(1)))
                    throw bad("气象范围不得自交");
            }
        }
        if(Math.abs(area)<1e-12)throw bad("气象范围不能为零面积");
    }
}
