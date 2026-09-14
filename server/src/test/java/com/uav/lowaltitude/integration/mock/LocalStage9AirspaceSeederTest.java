package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 阶段 9 空域种子：test 下存在、重跑不重复、双门禁把生产挡在外面（隔离上下文测试由助手另写）。 */
@SpringBootTest(properties = "app.dev-seed.enabled=true")
@ActiveProfiles("test")
class LocalStage9AirspaceSeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage9AirspaceSeeder seeder;
    @Autowired ApplicationArguments arguments;

    @Test
    void seedsTwoAirspacesWithASucceededVersionAndAStagedImportBatch() {
        Map<String, Long> before = counts();
        // 阶段 3/7 的空域版本是既有研判的输入证据：跑一次种子前后必须逐行不变。
        List<String> otherAirspacesBefore = otherAirspaceVersions();
        seeder.run(arguments);
        assertThat(otherAirspaceVersions()).isEqualTo(otherAirspacesBefore);
        // 只补缺行：重跑不产生第二份空域、版本或导入批次。
        assertThat(counts()).isEqualTo(before);

        assertThat(count("airspace where airspace_id in (?,?)", LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED, LocalStage9AirspaceSeeder.AIRSPACE_SINGLE)).isEqualTo(2);
        assertThat(count("airspace_version where airspace_id=?", LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED)).isEqualTo(2);
        // 演示接替：第 1 版已被第 2 版关闭，第 2 版仍然开放。
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", java.sql.Timestamp.class,
                LocalStage9AirspaceSeeder.VERSION_1).toInstant()).isEqualTo(LocalStage9AirspaceSeeder.T0);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", java.sql.Timestamp.class,
                LocalStage9AirspaceSeeder.VERSION_2)).isNull();
        assertThat(jdbc.queryForObject("select superseded_version_id from airspace_version_origin where airspace_version_id=?", String.class,
                LocalStage9AirspaceSeeder.VERSION_2)).isEqualTo(LocalStage9AirspaceSeeder.VERSION_1);
        // 种子来源标 SEED（不是人工也不是导入），且没有操作者。
        assertThat(count("airspace_version_origin where airspace_version_id in (?,?,?) and origin_kind='SEED' and actor_id is null",
                LocalStage9AirspaceSeeder.VERSION_1, LocalStage9AirspaceSeeder.VERSION_2, LocalStage9AirspaceSeeder.VERSION_SINGLE)).isEqualTo(3);
        // 种子空域全部用规范种类，不含历史写法。
        assertThat(count("airspace_version where airspace_id in (?,?)"
                + " and kind_code not in ('PROHIBITED','RESTRICTED','ALTITUDE_LIMIT','PERMITTED','TEMPORARY_CONTROL')",
                LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED, LocalStage9AirspaceSeeder.AIRSPACE_SINGLE)).isZero();
        // 一个待决定的导入批次：一条可接受、一条被拒。
        assertThat(count("airspace_import_batch where batch_id=? and status='STAGED'", LocalStage9AirspaceSeeder.BATCH)).isEqualTo(1);
        assertThat(count("airspace_import_item where batch_id=? and accepted=true", LocalStage9AirspaceSeeder.BATCH)).isEqualTo(1);
        assertThat(count("airspace_import_item where batch_id=? and accepted=false", LocalStage9AirspaceSeeder.BATCH)).isEqualTo(1);

    }

    @Test
    void restartDoesNotOverrideManuallyAddedVersions() {
        String manualVersion = "seed-stage9-manual-check";
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,change_reason,created_at)"
                + " select ?,?,3,'PERMITTED',CAST(? AS GEOMETRY),?,?,? where not exists (select 1 from airspace_version where airspace_version_id=?)",
                manualVersion, LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED,
                "SRID=4326;MULTIPOLYGON(((118.5 37.5,118.51 37.5,118.51 37.51,118.5 37.51,118.5 37.5)))",
                java.sql.Timestamp.from(LocalStage9AirspaceSeeder.T0.plusSeconds(7200)), "人工追加", java.sql.Timestamp.from(LocalStage9AirspaceSeeder.T0), manualVersion);
        try {
            seeder.run(arguments);
            // 人工追加的版本必须原样保留：种子只补缺行，不做"恢复出厂设置"。
            assertThat(count("airspace_version where airspace_version_id=?", manualVersion)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select change_reason from airspace_version where airspace_version_id=?", String.class, manualVersion)).isEqualTo("人工追加");
        } finally {
            // 本用例自己清理：同一个类共用上下文与库，留下的第 3 版会让另一个用例数错版本数。
            jdbc.update("delete from airspace_version where airspace_version_id=?", manualVersion);
        }
    }

    @Test
    void profileAndPropertyGatesExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage9AirspaceSeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage9AirspaceSeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(profile).isNotNull();
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("test").contains(name))).isTrue();
        assertThat(expression.matches(name -> Set.of("local").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
    }

    /** 除本种子以外的全部空域版本快照（含生效区间）：用于证明种子没有改动别人的历史版本。 */
    private List<String> otherAirspaceVersions() {
        return jdbc.queryForList("select airspace_version_id||'|'||kind_code||'|'||valid_from||'|'||coalesce(cast(valid_to as varchar),'-')"
                + " from airspace_version where airspace_id not in (?,?) order by airspace_version_id", String.class,
                LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED, LocalStage9AirspaceSeeder.AIRSPACE_SINGLE);
    }

    private Map<String, Long> counts() {
        return Map.of(
                "airspace", count("airspace where airspace_id in (?,?)", LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED, LocalStage9AirspaceSeeder.AIRSPACE_SINGLE),
                "version", count("airspace_version where airspace_id in (?,?)", LocalStage9AirspaceSeeder.AIRSPACE_SUCCEEDED, LocalStage9AirspaceSeeder.AIRSPACE_SINGLE),
                "origin", count("airspace_version_origin where airspace_version_id in (?,?,?)",
                        LocalStage9AirspaceSeeder.VERSION_1, LocalStage9AirspaceSeeder.VERSION_2, LocalStage9AirspaceSeeder.VERSION_SINGLE),
                "batch", count("airspace_import_batch where batch_id like 'seed-stage9-%'"),
                "item", count("airspace_import_item where batch_id like 'seed-stage9-%'"));
    }

    private long count(String fromWhere, Object... args) {
        return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class, args);
    }
}
