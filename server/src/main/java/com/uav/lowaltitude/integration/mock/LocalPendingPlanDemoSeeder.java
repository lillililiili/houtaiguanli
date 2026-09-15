package com.uav.lowaltitude.integration.mock;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SpaceFactRow;
import com.uav.lowaltitude.platform.time.AppClock;

/** 待执行计划通知演示。固定标识防重复，已通知风险和核验历史在重启后保持原样。 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(95)
public class LocalPendingPlanDemoSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final RiskIngestionService ingestion;
    private final RiskRepository risks;
    private final SpaceRiskRepository space;

    public LocalPendingPlanDemoSeeder(JdbcTemplate jdbc, AppClock clock, RiskIngestionService ingestion,
            RiskRepository risks, SpaceRiskRepository space) {
        this.jdbc = jdbc; this.clock = clock; this.ingestion = ingestion; this.risks = risks; this.space = space;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if(args!=null && args.containsOption("preserve-existing-flight-demos"))return;
        Instant at = clock.now();
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select 'seed-pending-demo-route','航线-通知演示','待执行通知演示航线',true,'seed-stage3-source','mock','seed-stage3-status-org','seed-stage3-status-district',?,?,0"
                + " where not exists(select 1 from route where route_id='seed-pending-demo-route')", ts(at), ts(at));
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " select 'seed-pending-demo-rv','seed-pending-demo-route',1,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,?,?"
                + " from route_version where route_version_id='seed-stage3-rv-legal' and not exists(select 1 from route_version where route_version_id='seed-pending-demo-rv')", ts(at), ts(at));
        for (int i = 1; i <= 5; i++) {
            String id = "seed-pending-demo-" + i;
            Instant start = at.plusSeconds(3600L * (i + 1));
            jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                    + " select ?,?,'PENDING','seed-stage3-source','mock',?,?,'seed-pending-demo-rv','seed-stage3-status-org','seed-stage3-status-district',?,?,0"
                    + " where not exists(select 1 from flight_plan where plan_id=?)",
                    id, "计划-0911-10" + i, ts(start), ts(start.plusSeconds(3600)), ts(at), ts(at), id);
            // 专属演示计划沿用执行状态样例的浮动窗口，不参与其他机构的目标匹配。
            jdbc.update("update flight_plan set status_code='PENDING',start_at=?,end_at=?,updated_at=?,version=version+1 where plan_id=? and source_mode='mock' and status_code <> 'CANCELLED'",
                    ts(start), ts(start.plusSeconds(3600)), ts(at), id);
            if (i <= 3) {
                seedRisk(id, i, at, false);
                seedRisk(id, i, at, true);
            }
        }
    }

    private void seedRisk(String plan, int index, Instant instant, boolean mapSample) {
        String sourceRisk = "pending-plan-notice-demo-" + index + (mapSample ? "-map-v1" : "");
        // 不重置任何已有风险，尤其不能把用户已经通知的记录改回待通知。
        if (risks.findBySource("seed-stage3-source", sourceRisk) != null) return;
        var at = instant.atOffset(ZoneOffset.UTC);
        String subtype = index == 2 ? "BALLOON" : "BIRD_FLOCK";
        String text = index == 2 ? "航线附近气球，待通知上级" : "航线走廊内鸟群，待通知上级";
        String id = ingestion.ingest(new TrustedRiskFact("seed-stage3-source", sourceRisk, plan, "seed-pending-demo-rv",
                null, null, null, "SPACE_OBJECT", index == 2 ? "MEDIUM" : "HIGH",
                index == 2 ? "SPACE_OBJECT_NEAR_ROUTE" : "SPACE_OBJECT_IN_CORRIDOR",
                text + (mapSample ? "（地图模拟样例，未经评估器）" : "（模拟夹具，未经评估器）"), at, at, null, null, "mock"));
        // 位置快照只追加；地图演示使用新来源编号，绝不修改旧记录或关闭 append-only 保护。
        BigDecimal lon = mapSample ? new BigDecimal(index == 2 ? "118.02611" : index == 1 ? "118.024" : "118.028") : null;
        BigDecimal lat = mapSample ? new BigDecimal(index == 2 ? "37.023656" : index == 1 ? "37.024" : "37.028") : null;
        space.insertFact(new SpaceFactRow(id, subtype, null, "space-risk-c04-v1", "space-risk-demo-v1", 1,
                BigDecimal.valueOf(index == 2 ? 240 : 0), index == 2 ? "NEAR" : "INSIDE", "UNKNOWN", null,
                index == 2 ? 1 : 20, "UNKNOWN", "[\"ALTITUDE_DATUM_OR_RANGE_UNKNOWN\"]",
                lon, lat, null, at.minusMinutes(15), at, at));
        String actor = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        if (risks.update(id, 0, "PENDING_NOTIFICATION", at) != 1) throw new IllegalStateException("demo risk verification failed");
        risks.appendVerification(UUID.randomUUID().toString(), id, "CONFIRMED", "模拟夹具预置核验，用于通知流程演示，非真实人工核验",
                "PENDING_VERIFICATION", "PENDING_NOTIFICATION", 0, actor, at);
    }

    private static Timestamp ts(Instant at) { return Timestamp.from(at); }
}
