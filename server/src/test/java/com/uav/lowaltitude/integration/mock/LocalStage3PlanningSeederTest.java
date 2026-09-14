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
    private int count(String table, String where) { return jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class); }
    private String value(String id) { return jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=?", String.class, id); }
}
