package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.platform.time.AppClock;

/** 独立气象预警演示源，经风险入库服务关联计划，不虚构目标或现场气象观测。 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true")
@Order(98)
public class LocalWeatherRiskSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final RiskIngestionService ingestion;
    private final AppClock clock;
    private final com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository weather;
    public LocalWeatherRiskSeeder(JdbcTemplate jdbc,RiskIngestionService ingestion,AppClock clock,
            com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository weather){this.jdbc=jdbc;this.ingestion=ingestion;this.clock=clock;this.weather=weather;}
    @Override @Transactional public void run(ApplicationArguments args) {
        var now=clock.now();var time=Timestamp.from(now);
        jdbc.update("""
            INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version)
            SELECT 'seed-weather-demo','WEATHER-DEMO','气象预警演示源','WEATHER_DEMO','1.0',true,'mock',?,?,0
            WHERE NOT EXISTS(SELECT 1 FROM integration_source WHERE source_id='seed-weather-demo')
            """,time,time);
        var kinds=List.of("WEATHER_STRONG_WIND","WEATHER_THUNDERSTORM","WEATHER_LOW_VISIBILITY");
        var descriptions=List.of("计划飞行时段可能出现大风，请确认风速及航空器运行限制。","计划飞行时段可能出现雷雨，请确认预警范围和时段。","计划飞行时段可能出现低能见度，请确认能见度及运行条件。");
        for(int i=0;i<3;i++) {
            String plan="seed-pending-demo-"+(i+1);
            var routes=jdbc.query("SELECT route_version_id,start_at,end_at FROM flight_plan WHERE plan_id=? AND source_mode='mock' AND status_code IN ('PENDING','EXECUTING')",
                (rs,row)->new PlanWindow(rs.getString("route_version_id"),rs.getTimestamp("start_at").toInstant(),rs.getTimestamp("end_at").toInstant()),plan);
            if(routes.isEmpty())continue;
            // 每日固定来源编号；重启不重置用户核验、通知结果。
            String sourceRisk="WX-DEMO-"+DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now)+"-"+(i+1);
            var window=routes.get(0);
            var format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx").withZone(ZoneOffset.ofHours(8));
            String riskId=ingestion.ingest(new TrustedRiskFact("seed-weather-demo",sourceRisk,plan,window.routeVersionId(),null,null,null,
                "WEATHER",i==2?"MEDIUM":"HIGH",kinds.get(i),"【模拟气象预警，非实时天气】"+descriptions.get(i)
                    +"适用时段："+format.format(window.from())+" ～ "+format.format(window.to())+"。等级由演示场景预设，未接入气象实况或预报判定。",
                now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC),null,null,"mock"));
            seedMapFact(riskId, i);
        }
    }

    private void seedMapFact(String riskId, int kind) {
        if(weather.find(riskId)!=null)return;
        // 从已保存的预警读取原始时段；演示计划重启会移动时间，不能拿新计划时间改写旧预警。
        jdbc.query("SELECT reason_text,received_at FROM flight_risk WHERE risk_id=?", rs -> {
            var match=java.util.regex.Pattern.compile("适用时段：(.*?) ～ (.*?)。",java.util.regex.Pattern.DOTALL).matcher(rs.getString("reason_text"));
            if(!match.find())return;
            var fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx");
            long from=java.time.OffsetDateTime.parse(match.group(1),fmt).toInstant().toEpochMilli();
            long to=java.time.OffsetDateTime.parse(match.group(2),fmt).toInstant().toEpochMilli();
            // 明确的固定模拟多边形，位于专属演示航线附近；不从航线缓冲区冒充真实天气边界。
            double x=118.024+kind*0.002, y=37.024+kind*0.002;
            var polygon=List.of(List.of(x-0.004,y-0.002),List.of(x+0.001,y-0.004),List.of(x+0.005,y),
                List.of(x+0.003,y+0.004),List.of(x-0.003,y+0.003),List.of(x-0.004,y-0.002));
            weather.insertDemo(riskId,new com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository.FactRow(polygon,
                rs.getTimestamp("received_at").getTime(),from,to,kind==0?java.math.BigDecimal.valueOf(12):null,
                kind==0?java.math.BigDecimal.valueOf(315):null,kind==2?java.math.BigDecimal.valueOf(800):null,"mock"));
        },riskId);
    }
    private record PlanWindow(String routeVersionId,java.time.Instant from,java.time.Instant to) { }
}
