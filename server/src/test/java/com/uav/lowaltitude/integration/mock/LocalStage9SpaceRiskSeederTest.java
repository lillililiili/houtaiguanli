package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 阶段 9 种子：数据存在、重跑不重复、风险来自可信入库路径（来源为模拟规则引擎）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalStage9SpaceRiskSeederTest {
    @Autowired LocalStage9SpaceRiskSeeder seeder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsTargetsAirportAndSpaceRisksThroughIngestion() {
        seeder.run(null);
        assertThat(jdbc.queryForObject("select count(*) from target where target_id like 'seed-stage9-target-%'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select subtype from target where target_id=?", String.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A)).isEqualTo("BIRD_FLOCK");
        assertThat(jdbc.queryForObject("select source_mode from target where target_id=?", String.class, LocalStage9SpaceRiskSeeder.TARGET_BALLOON)).isEqualTo("mock");
        assertThat(jdbc.queryForObject("select count(*) from airport where airport_id=?", Long.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from airport_procedure_route where airport_id=?", Long.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select note from airport where airport_id=?", String.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID)).contains("演示数据");

        // 风险经 RiskIngestionService 写入：来源是模拟规则引擎，不是直插的任意来源。
        Long risks = jdbc.queryForObject("select count(*) from flight_risk where source_id=? and risk_type='SPACE_OBJECT'",
                Long.class, "rule-engine-space-risk-mock");
        assertThat(risks).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id where r.source_id=?",
                Long.class, "rule-engine-space-risk-mock")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select corridor_relation from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id"
                + " where r.target_id=?", String.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A)).isEqualTo("INSIDE");

        // 重跑：同一 source_risk_id 幂等，既不新增风险也不重写事实。
        seeder.run(null);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where source_id=?", Long.class, "rule-engine-space-risk-mock")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id where r.source_id=?",
                Long.class, "rule-engine-space-risk-mock")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from target where target_id like 'seed-stage9-target-%'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from airport where airport_id=?", Long.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID)).isEqualTo(1L);
    }

    /**
     * 种子必须真能被 C04 评估到：目标观测时刻要落在阶段 3 演示计划的窗口内。
     * 原先观测时刻是固定常量 T0(2026-09-05)，而计划窗口按 AppClock 现算，两者永不重叠——
     * 页面上那两条风险是直插演示数据，一次真实评估什么也评不出来。
     */
    @Test
    void seededTargetsAreObservedInsideThePlanWindow() {
        seeder.run(null);
        Long inWindow = jdbc.queryForObject("select count(*) from target_latest_state s"
                + " join flight_plan p on p.plan_id=?"
                + " where s.target_id like 'seed-stage9-target-%' and s.observed_at >= p.start_at and s.observed_at < p.end_at",
                Long.class, LocalStage9SpaceRiskSeeder.PLAN);
        assertThat(inWindow).as("三个种子目标的观测时刻都要落在演示计划窗口内，否则 C04 取不到目标").isEqualTo(3L);

        // 走廊内那只鸟群必须与航线同高度基准（AMSL），否则高度带只能 UNKNOWN，评估给不出 HIGH。
        assertThat(jdbc.queryForObject("select altitude_amsl_m from target_latest_state where target_id=?",
                java.math.BigDecimal.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A)).isNotNull();
        assertThat(jdbc.queryForObject("select height_agl_m from target_latest_state where target_id=?",
                java.math.BigDecimal.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A)).isNull();
    }

    /**
     * 决策 9-24 的 H2 分支：本地没有 PostGIS，评估器算不出空间关系，只能留两条直插演示风险，
     * 而它们必须在原因里自报"未经评估器"——否则页面上分不清哪条是研判结论、哪条是造的数据。
     */
    @Test
    void demoRisksOnNonSpatialBackendSayTheyWereNotEvaluated() {
        seeder.run(null);
        assertThat(jdbc.queryForList("select reason_text from flight_risk where source_id=? and risk_type='SPACE_OBJECT'",
                String.class, "rule-engine-space-risk-mock"))
                .hasSize(2)
                .allSatisfy(text -> assertThat(text).contains(LocalStage9SpaceRiskSeeder.DEMO_SUFFIX));
        // H2 上不该出现评估运行记录：评估器在这里只会回 UNAVAILABLE，种子不做无用的空跑。
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run", Long.class)).isZero();
    }

    /** 机场建档人为空：演示数据没有自然人建档人，种子也就不依赖用户种子（没有用户的环境同样能播下去）。 */
    @Test
    void seededAirportHasNoHumanCreator() {
        seeder.run(null);
        assertThat(jdbc.queryForObject("select created_by from airport where airport_id=?",
                String.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID)).isNull();
    }
}
