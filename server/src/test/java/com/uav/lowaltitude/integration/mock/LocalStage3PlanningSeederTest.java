package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.platform.time.AppClock;

/** local/test 固定夹具覆盖结论、跨范围隔离和无法绘制的安全降级，并验证重复执行不追加记录。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalStage3PlanningSeederTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsFixedLegalIllegalUndeterminedAndCrossScopeExamplesIdempotently() throws Exception {
        var at = Instant.parse("2026-09-05T00:00:00Z");
        var seeder = new LocalStage3PlanningSeeder(jdbc, new AppClock(Clock.fixed(at, ZoneOffset.UTC)));
        seeder.run(null);
        seeder.run(null);
        assertThat(count("assessment_result", "assessment_id like 'seed-stage3-assessment-%'")).isEqualTo(4);
        assertThat(value("seed-stage3-assessment-legal")).isEqualTo("LEGAL");
        assertThat(value("seed-stage3-assessment-illegal")).isEqualTo("ILLEGAL");
        assertThat(value("seed-stage3-assessment-undetermined")).isEqualTo("UNDETERMINED");
        assertThat(count("airspace_version", "airspace_version_id='seed-stage3-av-prohibited'")).isEqualTo(1);
        assertThat(count("route_version", "route_version_id='seed-stage3-rv-undetermined' and altitude_datum is null and corridor_width_m is not null")).isEqualTo(1);
        assertThat(count("flight_plan", "plan_id='seed-stage3-plan-cross-scope' and owner_org_id='seed-stage3-other-org' and district_id='seed-stage3-other-district'")).isEqualTo(1);
        assertThat(count("integration_source", "source_id='seed-stage3-source' and source_code='STAGE3-PLANNING-MOCK'")).isEqualTo(1);
        assertThat(count("flight_plan", "plan_id='seed-stage3-plan-illegal' and source_id='seed-stage3-source'")).isEqualTo(1);
        assertThat(count("assessment_result", "assessment_id='seed-stage3-assessment-undetermined' and unknown_reasons like '%ALTITUDE_DATUM_OR_RANGE_UNKNOWN%'")).isEqualTo(1);
        assertThat(count("flight_plan", "plan_id='seed-stage3-plan-illegal' and start_at is not null and end_at is not null")).isEqualTo(1);
    }
    /**
     * 阶段 19：待执行 / 执行中 / 已完成各一条，且窗口每次启动都按当前时刻重定位。
     *
     * 第二次用"两天后"的时钟再跑一遍，是这条用例的要点：真正的缺陷不是"没有这三条"，
     * 而是插入一次就固定不动——状态推进任务按真实时钟把 end_at 已过的计划一律推成已完成，
     * 演示库第二天起三条全是"已完成"。只断"三条存在"的用例发现不了这件事。
     */
    @Test
    void statusScenariosExistAndTheirWindowsFollowTheClockOnEveryStartup() throws Exception {
        var day1 = Instant.parse("2026-09-05T03:00:00Z");
        new LocalStage3PlanningSeeder(jdbc, new AppClock(Clock.fixed(day1, ZoneOffset.UTC))).run(null);
        assertThat(status("pending")).isEqualTo("PENDING");
        assertThat(status("executing")).isEqualTo("EXECUTING");
        assertThat(status("done")).isEqualTo("COMPLETED");
        // 待执行尚未开始、执行中正在窗口内、已完成已经结束——三条的窗口与状态必须自相符合。
        assertThat(start("pending")).isAfter(day1);
        assertThat(start("executing")).isBefore(day1);
        assertThat(end("executing")).isAfter(day1);
        assertThat(end("done")).isBefore(day1);
        // 待执行的计划本来就没有研判，别顺手配一份让"暂无研判"那条分支演示不到。
        assertThat(count("assessment_result", "plan_id='seed-stage3-plan-pending'")).isZero();

        var day3 = day1.plusSeconds(2 * 86_400);
        new LocalStage3PlanningSeeder(jdbc, new AppClock(Clock.fixed(day3, ZoneOffset.UTC))).run(null);
        assertThat(count("flight_plan", "plan_id in ('seed-stage3-plan-pending','seed-stage3-plan-executing','seed-stage3-plan-done')")).isEqualTo(3);
        assertThat(start("pending")).isAfter(day3);
        assertThat(start("executing")).isBefore(day3);
        assertThat(end("executing")).isAfter(day3);
        assertThat(end("done")).isBefore(day3);
    }

    private String status(String suffix) {
        return jdbc.queryForObject("select status_code from flight_plan where plan_id=?", String.class, "seed-stage3-plan-" + suffix);
    }
    private Instant start(String suffix) { return instant("start_at", suffix); }
    private Instant end(String suffix) { return instant("end_at", suffix); }
    private Instant instant(String column, String suffix) {
        return jdbc.queryForObject("select " + column + " from flight_plan where plan_id=?",
                java.sql.Timestamp.class, "seed-stage3-plan-" + suffix).toInstant();
    }

    private int count(String table, String where) { return jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class); }
    private String value(String id) { return jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=?", String.class, id); }
}
