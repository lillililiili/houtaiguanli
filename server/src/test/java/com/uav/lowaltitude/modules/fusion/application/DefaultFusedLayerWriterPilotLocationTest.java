package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;

/**
 * 阶段 8.5（决策 8.5-5、8.5-27）：飞手位置取"身份主源"的 pilot 字段落 target_latest_state.pilot_location。
 * C02-6 要的是目标级飞手位置，而只有身份类来源（TDOA/DCD/RID）带这个字段；取任意来源会把别的传感器的
 * 站址当成飞手位置，缺失时也绝不补 (0,0)。
 * 改写资格按帧判定：只有携带身份主源的帧才有资格改写这一列——它才是"飞手在哪"的证据来源。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DefaultFusedLayerWriterPilotLocationTest {

    private static final String TARGET = "seed-target-uav-wgs84";      // 阶段 2 种子目标（mock 分区）
    private static final String SOURCE = "seed-stage2-source-mock";    // 阶段 2 种子来源
    private static final String LINK = "seed-link-uav-001";
    private static final FusionDomainKey DOMAIN = new FusionDomainKey("mock", null, null);
    private static final Instant T0 = Instant.parse("2026-09-06T01:00:00Z");

    @Autowired FusedLayerWriter writer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyFramesCarryingAnIdentitySourceMayRewriteThePilotLocation() {
        write(T0, identity(T0, 118.60, 37.40));
        assertThat(pilot("pilot")).contains("118.6").contains("37.4");
        assertThat(pilotObservedAt()).isEqualTo(T0);

        // 光电帧没有身份线索，也就没有身份主源：它对"飞手在哪"不表态，不得把上一帧的飞手位置抹掉。
        Instant t1 = T0.plusSeconds(20);
        write(t1, eoOnly(t1));
        assertThat(pilot("pilot")).contains("118.6").contains("37.4");
        assertThat(pilotObservedAt()).as("保留的是上一次真正报过飞手位置的时刻").isEqualTo(T0);

        // 身份主源在、但这一帧没给飞手位置：这是"确实没报"的证据，写回 NULL。
        Instant t2 = t1.plusSeconds(20);
        write(t2, identity(t2, null, null));
        assertThat(pilot("pilot")).isNull();
        assertThat(pilotObservedAt()).isNull();
    }

    /**
     * 决策 8.5-27：整帧没有来源时不改写属性优选，也不改写飞手位置——
     * "这一帧没有任何来源"不是"从来不知道这些属性来自哪一路"，后者会把已有归属抹成 NULL。
     * 无来源这件事由 target_degradation 那行如实记录，不会被藏起来。
     */
    @Test
    void sourcelessFrameKeepsPreviousSelectionAndPilotLocation() {
        write(T0, identity(T0, 118.60, 37.40));
        Map<String, Object> before = selection();
        assertThat(before.get("identity_source_id")).isEqualTo(SOURCE);

        Instant t1 = T0.plusSeconds(20);
        writer.write(new TargetFrameResult(TARGET, DOMAIN, t1, List.of(), TrackStatus.TERMINATED, 1, "demo-v1"));

        Map<String, Object> after = selection();
        assertThat(after.get("identity_source_id")).as("无源帧不得抹掉身份主源归属").isEqualTo(SOURCE);
        assertThat(after.get("position_source_id")).isEqualTo(before.get("position_source_id"));
        assertThat(after.get("class_source_id")).isEqualTo(before.get("class_source_id"));
        assertThat(pilot("pilot")).contains("118.6").contains("37.4");
        // 降级行仍如实记录"这一帧没有来源"：可用来源为空列表，属性归属被保留不等于把中断藏起来。
        Object available = jdbc.queryForMap("select available_source_ids from target_degradation where target_id=?", TARGET).get("available_source_ids");
        String availableText = available instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(available);
        assertThat(availableText.replace("\\", "").replace("\"", "")).isEqualTo("[]");
    }

    private void write(Instant at, SourceEstimate estimate) {
        writer.write(new TargetFrameResult(TARGET, DOMAIN, at, List.of(estimate), TrackStatus.STABLE, 0, "demo-v1"));
    }

    private String pilot(String alias) {
        Object value = jdbc.queryForMap("select cast(pilot_location as varchar) as " + alias + " from target_latest_state where target_id=?", TARGET).get(alias);
        return value == null ? null : String.valueOf(value);
    }

    private Instant pilotObservedAt() {
        java.sql.Timestamp at = jdbc.queryForObject("select pilot_observed_at from target_latest_state where target_id=?", java.sql.Timestamp.class, TARGET);
        return at == null ? null : at.toInstant();
    }

    private Map<String, Object> selection() {
        return jdbc.queryForMap("select position_source_id,class_source_id,identity_source_id from target_attribute_selection where target_id=?", TARGET);
    }

    /** 身份主源：射频类来源（TDOA）且带身份线索，可携带（或不携带）飞手位置。 */
    private static SourceEstimate identity(Instant at, Double pilotLon, Double pilotLat) {
        return new SourceEstimate(SOURCE, SOURCE, "TDOA", "CONFIRMED", LINK, null, null, at,
                118.5, 37.4, 15.0, 120.0, null, 8.0, 90.0, "UAV", null, "SN-8501", 0.9, PointKind.MEAS, Map.of(),
                pilotLon, pilotLat, "SENSE_DATA");
    }

    /** 光电：有类别、无身份线索，因此不是身份主源。 */
    private static SourceEstimate eoOnly(Instant at) {
        return new SourceEstimate(SOURCE, SOURCE, "EO", "DEMO", LINK, null, null, at,
                118.5, 37.4, 15.0, 120.0, null, 8.0, 90.0, "UAV", 0.8, null, null, PointKind.MEAS, Map.of(),
                null, null, "EO_TRACKING");
    }
}
