package com.uav.lowaltitude.integration.mock;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskEvaluationService;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SpaceFactRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 阶段 9 演示数据：异物目标、一个机场与两条空中异物风险。只在双门禁的 local/test 环境注册。
 * 风险经 {@link RiskIngestionService} 写入，与真实评估同一条路径——种子直插 flight_risk 会绕过
 * 来源校验与幂等，让"种子数据"和"引擎数据"在库里形状不同，后续对账无从谈起。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(90)
public class LocalStage9SpaceRiskSeeder implements ApplicationRunner {
    public static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    public static final String PLAN = "seed-stage3-plan-legal", ROUTE_VERSION = "seed-stage3-rv-legal";
    public static final String AIRPORT_ID = "seed-stage9-airport";
    public static final String AIRPORT_ICAO = "ZSDY";
    public static final String TARGET_FLOCK_A = "seed-stage9-target-flock-a", TARGET_FLOCK_B = "seed-stage9-target-flock-b";
    public static final String TARGET_BALLOON = "seed-stage9-target-balloon";
    public static final String RULE_SET_VERSION = "space-risk-demo-v1";
    /** H2 上没有评估器结论，风险原因必须自报家门，不能让演示数据看着像研判结果。 */
    public static final String DEMO_SUFFIX = "（演示数据，未经评估器）";
    /** 演示风险的稳定标识时刻：只用来拼 source_risk_id，让重启后的重复播种命中幂等，不当作观测时刻。 */
    public static final Instant T0 = Instant.parse("2026-09-05T02:10:00Z");

    private final JdbcTemplate jdbc;
    private final RiskIngestionService ingestion;
    private final SpaceRiskRepository spaceRisks;
    private final AppClock clock;
    private final SpaceRiskSpatialPort spatial;
    private final SpaceRiskEvaluationService evaluation;
    private final TransactionTemplate transactions;

    public LocalStage9SpaceRiskSeeder(JdbcTemplate jdbc, RiskIngestionService ingestion, SpaceRiskRepository spaceRisks,
            AppClock clock, SpaceRiskSpatialPort spatial, SpaceRiskEvaluationService evaluation, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.ingestion = ingestion; this.spaceRisks = spaceRisks; this.clock = clock;
        this.spatial = spatial; this.evaluation = evaluation; this.transactions = transactions;
    }

    /**
     * 决策 9-24：风险按空间后端分流。PostGIS 可用时不插演示风险，改为播种后立刻跑一次真实 C04 评估，
     * 页面上的风险就都出自评估器，一条不重复；H2 算不了空间关系（评估只会回 UNAVAILABLE），
     * 只能保留两条直插演示风险，并在原因里写明"未经评估器"，免得把演示数据当成研判结论。
     * 播种落库必须先提交，评估服务是 REQUIRES_NEW：还在未提交的事务里，它一个目标都看不见。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!planExists()) return;
        boolean evaluable = spatial.available();
        transactions.executeWithoutResult(status -> seed(evaluable));
        if (!evaluable) return;
        // 评估窗口就取演示计划自己的窗口：窗口固定，source_risk_id 也就固定，重复播种由 ingest 幂等挡住。
        evaluation.evaluate(C04DecisionTable.RULE_CODE, planWindowStart(), planWindowEnd(), "MANUAL", null);
    }

    private void seed(boolean evaluable) {
        OffsetDateTime at = T0.atOffset(ZoneOffset.UTC);
        // 观测时刻必须落在阶段 3 演示计划的窗口内，否则 C04 取不到任何目标：计划窗口是首次播种时按 AppClock 定的
        // （clock.now()+5 分钟起算一小时），与固定常量 T0 永不重叠，种子数据就永远跑不出一次真实评估。
        // 直接读库里那条计划的实际窗口，两边都是"不存在才插入"，播种与计划因此永远同源。
        OffsetDateTime observedAt = planWindowStart().plusMinutes(1);
        activateDemoRuleSet(at);
        // 目标细类使用字典别名可匹配的值：鸟群与气球，其余异物细类本期不造数据。
        // 走廊内那只鸟群给 AMSL 高度：航线基准是 AMSL，只有同基准才判得出高度带，评估才会给出 HIGH。
        // 另一只只有 AGL 高度，用来演示"基准不同 → 高度带 UNKNOWN → 降为 MEDIUM"。
        target(TARGET_FLOCK_A, "目标-20260905-901", "BIRD", "BIRD_FLOCK", 118.021, 37.021, null, new BigDecimal("60.00"), observedAt);
        target(TARGET_FLOCK_B, "目标-20260905-902", "BIRD", "BIRD_FLOCK", 118.028, 37.029, new BigDecimal("260.00"), null, observedAt);
        target(TARGET_BALLOON, "目标-20260905-903", "UNKNOWN", "BALLOON", 118.035, 37.036, null, null, observedAt);
        airport(at);
        if (evaluable) return;
        // 两条演示风险：走廊内鸟群（高）与邻近航线气球（中）。source_risk_id 与真实评估同形，重跑幂等。
        risk(TARGET_FLOCK_A, "BIRD_FLOCK", "HIGH", "SPACE_OBJECT_IN_CORRIDOR", "鸟群进入航线走廊，规模约 30 只" + DEMO_SUFFIX,
                new BigDecimal("42.00"), "INSIDE", "CLIMB", "AMSL", 30, 118.021, 37.021, new BigDecimal("60.00"), observedAt);
        risk(TARGET_BALLOON, "BALLOON", "MEDIUM", "SPACE_OBJECT_NEAR_ROUTE", "气球邻近航线，距中心线约 240 米" + DEMO_SUFFIX,
                new BigDecimal("240.00"), "NEAR", "UNKNOWN", null, 1, 118.035, 37.036, null, observedAt.plusSeconds(30));
    }

    private OffsetDateTime planWindowStart() { return planTime("start_at"); }

    private OffsetDateTime planWindowEnd() { return planTime("end_at"); }

    private OffsetDateTime planTime(String column) {
        Timestamp value = jdbc.queryForObject("select " + column + " from flight_plan where plan_id=?", Timestamp.class, PLAN);
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }

    /**
     * 决策 9-16：DEMO 规则集只在 local/test 由种子激活，迁移不再置为 ACTIVE。
     * 条件更新 active_version_id IS NULL：人工激活或回滚过的版本不被种子覆盖（与阶段 7 种子同一写法）。
     */
    private void activateDemoRuleSet(OffsetDateTime at) {
        jdbc.update("update rule_set set active_version_id=?,updated_at=? where rule_set_code=?"
                + " and active_version_id is null and previous_active_version_id is null",
                RULE_SET_VERSION, ts(at), "SPACE-RISK-DEMO");
    }

    private boolean planExists() {
        Integer count = jdbc.queryForObject("select count(*) from flight_plan where plan_id=?", Integer.class, PLAN);
        return count != null && count > 0;
    }

    private void target(String id, String no, String objectType, String subtype, double lon, double lat,
            BigDecimal agl, BigDecimal amsl, OffsetDateTime at) {
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,?,?,?,?,'mock',?,?,?,?,0 where not exists (select 1 from target where target_id=?)",
                id, no, objectType, subtype, ts(at), ts(at), ORG, DISTRICT, ts(at), ts(at), id);
        // 高度只在有基准时写：AGL 与 AMSL 不互推，缺基准就让高度保持未知。
        jdbc.update("insert into target_latest_state (target_id,location,height_agl_m,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " select ?,CAST(? AS GEOMETRY),?,?,?,?,CAST('[]' AS JSON),?,?,0 where not exists (select 1 from target_latest_state where target_id=?)",
                id, "SRID=4326;POINT (" + lon + " " + lat + ")", agl, amsl, ts(at), ts(at), ts(at), ts(at), id);
    }

    /** 东营胜利机场演示坐标：note 明确标注为演示数据，不能当成真实台账。 */
    private void airport(OffsetDateTime at) {
        jdbc.update("insert into airport (airport_id,icao_code,name,reference_point,elevation_amsl_m,owner_org_id,district_id,enabled,note,created_by,created_at,version)"
                // 建档人写 NULL：演示机场不是某个管理员报上来的，挂在真实账号名下就是伪造归属；
                // 也因此不依赖用户种子——postgres-test 这类没有用户的环境同样能把机场播下去。
                + " select ?,?,?,CAST(? AS GEOMETRY),?,?,?,TRUE,?,NULL,?,0"
                + " where not exists (select 1 from airport where airport_id=?)",
                AIRPORT_ID, AIRPORT_ICAO, "东营胜利机场（演示）", "SRID=4326;POINT (118.788 37.585)", new BigDecimal("6.00"),
                ORG, DISTRICT, "演示数据：坐标与跑道为示意值，非真实机场台账", ts(at), AIRPORT_ID);
        jdbc.update("insert into airport_runway (runway_id,airport_id,designator,heading_deg,length_m,centerline,created_at)"
                + " select ?,?,?,?,?,CAST(? AS GEOMETRY),? where exists (select 1 from airport where airport_id=?)"
                + " and not exists (select 1 from airport_runway where runway_id=?)",
                "seed-stage9-runway-01", AIRPORT_ID, "18/36", new BigDecimal("180.00"), new BigDecimal("2600.00"),
                "SRID=4326;LINESTRING (118.788 37.575,118.788 37.595)", ts(at), AIRPORT_ID, "seed-stage9-runway-01");
        jdbc.update("insert into airport_procedure_route (route_id,airport_id,kind,name,centerline,protect_width_m,min_altitude_m,max_altitude_m,altitude_datum,created_at)"
                + " select ?,?,'APPROACH',?,CAST(? AS GEOMETRY),?,?,?,'AMSL',? where exists (select 1 from airport where airport_id=?)"
                + " and not exists (select 1 from airport_procedure_route where route_id=?)",
                "seed-stage9-approach-18", AIRPORT_ID, "18 号进近", "SRID=4326;LINESTRING (118.788 37.560,118.788 37.575)",
                new BigDecimal("600.00"), new BigDecimal("0.00"), new BigDecimal("300.00"), ts(at), AIRPORT_ID, "seed-stage9-approach-18");
        jdbc.update("insert into airport_protected_target (protected_target_id,airport_id,name,kind,location,radius_m,created_at)"
                + " select ?,?,?,?,CAST(? AS GEOMETRY),?,? where exists (select 1 from airport where airport_id=?)"
                + " and not exists (select 1 from airport_protected_target where protected_target_id=?)",
                "seed-stage9-protected-tower", AIRPORT_ID, "塔台", "TOWER", "SRID=4326;POINT (118.790 37.586)",
                new BigDecimal("800.00"), ts(at), AIRPORT_ID, "seed-stage9-protected-tower");
        jdbc.update("insert into airport_notification_target (notification_target_id,airport_id,name,role,channel_kind,enabled,created_at)"
                + " select ?,?,?,?,'PHONE',TRUE,? where exists (select 1 from airport where airport_id=?)"
                + " and not exists (select 1 from airport_notification_target where notification_target_id=?)",
                "seed-stage9-notify-tower", AIRPORT_ID, "机场塔台值班席", "塔台管制", ts(at), AIRPORT_ID, "seed-stage9-notify-tower");
    }

    private void risk(String targetId, String subtype, String severity, String reasonCode, String reasonText,
            BigDecimal distanceM, String relation, String band, String altitudeDatum, int count,
            double lon, double lat, BigDecimal altitude, OffsetDateTime at) {
        // 标识时刻用固定的 T0 而不是传入的观测时刻：观测时刻随计划窗口浮动，拿它拼标识会让每次重新播种都造出新风险。
        String sourceRiskId = "C04:" + RULE_SET_VERSION + ":" + PLAN + ":" + targetId + ":" + T0.toEpochMilli();
        String riskId = ingestion.ingest(new TrustedRiskFact(SpaceRiskEvaluationService.SOURCE_MOCK, sourceRiskId, PLAN, ROUTE_VERSION,
                null, targetId, null, SpaceRiskEvaluationService.RISK_TYPE, severity, reasonCode, reasonText, at, at,
                null, null, "mock"));
        if (spaceRisks.factExists(riskId)) return;
        // 演示数据给出数量与趋势，因此没有未知项；真实评估里这两项当前没有数据源（决策 9-18）。
        spaceRisks.insertFact(new SpaceFactRow(riskId, subtype, null, "space-risk-c04-v1", RULE_SET_VERSION, 1, distanceM,
                relation, band, altitudeDatum, count, "FLAT", "[]",
                BigDecimal.valueOf(lon), BigDecimal.valueOf(lat), altitude, at.minusMinutes(30), at,
                clock.now().atOffset(ZoneOffset.UTC)));
    }

    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(value.toInstant()); }
}
