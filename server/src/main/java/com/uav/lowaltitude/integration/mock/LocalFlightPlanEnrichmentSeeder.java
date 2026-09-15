package com.uav.lowaltitude.integration.mock;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** 对明确指定的补充演示批次追加数据；不移动计划时间，不重置风险办理或已有轨迹。 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true")
@Order(99)
public class LocalFlightPlanEnrichmentSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final RuleRunService runs;
    private final RiskIngestionService risks;
    private final WeatherRiskRepository weather;
    private final String prefix;
    public LocalFlightPlanEnrichmentSeeder(JdbcTemplate jdbc,AppClock clock,RuleRunService runs,
            RiskIngestionService risks,WeatherRiskRepository weather,
            @Value("${app.dev-seed.flight-enrichment-prefix:}") String prefix) {
        this.jdbc=jdbc;this.clock=clock;this.runs=runs;this.risks=risks;this.weather=weather;this.prefix=prefix;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(prefix.isBlank())return;
        if(!prefix.matches("seed-refill-[0-9]{6}-[0-9]{6}"))throw new IllegalArgumentException("只允许指定补充演示批次");
        for(int n=1;n<=5;n++)seedRisk(prefix+"-"+n,(n-1)%3);
        seedTrajectory(prefix+"-6");
    }
    private Plan plan(String id,String status) {
        return jdbc.query("SELECT * FROM flight_plan WHERE plan_id=? AND status_code=? AND source_mode='mock'",
            (rs,n)->new Plan(id,rs.getString("source_id"),rs.getString("route_version_id"),rs.getString("owner_org_id"),
                rs.getString("district_id"),rs.getTimestamp("start_at").toInstant(),rs.getTimestamp("end_at").toInstant()),id,status)
            .stream().findFirst().orElse(null);
    }
    private void seedRisk(String id,int kind) {
        Plan p=plan(id,"PENDING");if(p==null || !p.end().isAfter(clock.now()))return;
        var now=clock.now();
        String code=List.of("WEATHER_STRONG_WIND","WEATHER_THUNDERSTORM","WEATHER_LOW_VISIBILITY").get(kind);
        String description=List.of("计划时段存在大风风险，请核查风速及航空器抗风限制。", "计划时段存在雷雨风险，请核查预警并避开雷雨影响范围。", "计划时段存在低能见度风险，请核查能见度和运行条件。").get(kind);
        String risk=risks.ingest(new TrustedRiskFact("seed-weather-demo","WX-"+id,id,p.route(),null,null,null,
            "WEATHER",kind==2?"MEDIUM":"HIGH",code,"【模拟气象预警，非实时天气】"+description+"本记录用于起飞前风险演示，未接入真实气象预报。",
            now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC),null,null,"mock"));
        if(weather.find(risk)!=null)return;
        var polygon=List.of(List.of(118.020,37.022),List.of(118.026,37.020),List.of(118.031,37.026),
            List.of(118.027,37.031),List.of(118.021,37.029),List.of(118.020,37.022));
        weather.insertDemo(risk,new WeatherRiskRepository.FactRow(polygon,now.toEpochMilli(),p.start().toEpochMilli(),p.end().toEpochMilli(),
            kind==0?BigDecimal.valueOf(12):null,kind==0?BigDecimal.valueOf(315):null,kind==2?BigDecimal.valueOf(800):null,"mock"));
    }
    private void seedTrajectory(String id) {
        Plan p=plan(id,"EXECUTING");if(p==null)return;
        Instant now=clock.now(),last=now.minusSeconds(10),first=last.minusSeconds(100);
        if(first.isBefore(p.start()) || !last.isBefore(p.end()))return;
        String dataset="flight-refill-path-v1-"+id,target=id+"-t",link=id+"-ln",track=id+"-tr";
        if(jdbc.queryForObject("SELECT count(*) FROM rule_run WHERE replay_dataset_code=? AND status='DONE'",Integer.class,dataset)>0)return;
        // 此批次原本没有飞机编号；只为指定的模拟计划绑定专属编号。
        if(jdbc.update("UPDATE flight_plan SET uav_sn=?,updated_at=?,version=version+1 WHERE plan_id=? AND source_mode='mock' AND uav_sn IS NULL",id,ts(now),id)!=1)
            throw new IllegalStateException("模拟计划已有航空器，请勿覆盖");
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'UAV','QUADCOPTER',?,?,?,'mock',?,?,?,?,0)",
            target,"模拟轨迹-06",id,ts(first),ts(last),p.org(),p.district(),ts(first),ts(last));
        jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,protocol_version,created_at) VALUES (?,?,?,?,?,'1.0',?)",link,target,p.source(),id,id,ts(first));
        jdbc.update("INSERT INTO track(track_id,target_id,link_id,external_track_id,started_at,created_at) VALUES (?,?,?,?,?,?)",track,target,link,id,ts(first),ts(first));
        // 明确的模拟采样沿计划线移动；不当作传感器实测或持续实时飞行。
        for(int i=0;i<21;i++) {
            var seen=ts(first.plusSeconds(i*5L));
            jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at) SELECT ?,?,?,?,?,ST_LineInterpolatePoint(centerline,?),50,40,? FROM route_version WHERE route_version_id=?",
                id+"-p"+i,track,i,seen,seen,i/25.0,seen,p.route());
        }
        jdbc.update("INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,created_at,updated_at,version) SELECT ?,location,50,40,8,90,0.95,0.95,observed_at,received_at,'[]',?,?,0 FROM track_point WHERE point_id=?",
            target,ts(first),ts(last),id+"-p20");
        var asOf=last.atOffset(ZoneOffset.UTC);
        var run=runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE,RunMode.ACTIVE,"REPLAY",dataset,null,asOf);
        var result=runs.runBatch(run,List.of(new Subject(SubjectKind.TARGET,target,p.org(),p.district(),"mock")),asOf);
        if(!result.errors().isEmpty())throw new IllegalStateException("补充模拟轨迹研判失败："+result.errors());
    }
    private record Plan(String id,String source,String route,String org,String district,Instant start,Instant end) { }
    private static Timestamp ts(Instant at){return Timestamp.from(at);}
}
