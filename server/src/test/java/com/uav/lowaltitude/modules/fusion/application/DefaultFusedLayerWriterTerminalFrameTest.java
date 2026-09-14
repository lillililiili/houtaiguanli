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
 * 领导集成回归（阶段 8 验收在真实 PostgreSQL 上暴露）：TERMINATED 的无源帧先关闭融合轨迹再写最新状态，
 * 最新状态必须保留最后可信位置，而不是因为"找不到开放轨迹"写成空位置。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DefaultFusedLayerWriterTerminalFrameTest {

    @Autowired FusedLayerWriter writer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void terminatedFrameWithoutSourcesKeepsLastTrustedPositionInLatestState() {
        String targetId = "seed-target-uav-wgs84";           // 阶段 2 种子目标（mock 分区）
        String sourceId = "seed-stage2-source-mock";          // 阶段 2 种子来源
        String linkId = "seed-link-uav-001";
        FusionDomainKey domain = new FusionDomainKey("mock", null, null);
        Instant t0 = Instant.parse("2026-09-06T00:00:00Z");
        SourceEstimate radar = new SourceEstimate(sourceId, "seed-stage2-source-mock", "RADAR", "CONFIRMED", linkId, null, null, t0,
                118.5, 37.4, 15.0, 120.0, null, 8.0, 90.0, "UAV", null, null, null, PointKind.MEAS, Map.of());
        writer.write(new TargetFrameResult(targetId, domain, t0, List.of(radar), TrackStatus.STABLE, 0, "demo-v1"));
        Map<String, Object> after = jdbc.queryForMap("select cast(location as varchar) as loc, fusion_confidence from target_latest_state where target_id=?", targetId);
        assertThat(String.valueOf(after.get("loc"))).contains("118.5").contains("37.4");

        // 无源 + TERMINATED：轨迹被关闭，但位置仍是最后可信点，置信度按降级下降。
        Instant t1 = t0.plusSeconds(20);
        writer.write(new TargetFrameResult(targetId, domain, t1, List.of(), TrackStatus.TERMINATED, 1, "demo-v1"));
        Map<String, Object> terminal = jdbc.queryForMap("select cast(location as varchar) as loc, fusion_confidence, unknown_fields from target_latest_state where target_id=?", targetId);
        assertThat(String.valueOf(terminal.get("loc"))).contains("118.5").contains("37.4");
        Object rawUnknown = terminal.get("unknown_fields");
        String unknown = rawUnknown instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(rawUnknown);
        // H2 的 JSON 列读出带转义引号，去掉反斜杠后按字段名断言：位置已保留，不得再标 location 未知。
        assertThat(unknown.replace("\\", "")).doesNotContain("\"location\"");
        assertThat(jdbc.queryForObject("select count(*) from track where target_id=? and layer='FUSED' and ended_at is not null", Long.class, targetId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from track_point p join track t on t.track_id=p.track_id where t.target_id=? and t.layer='FUSED' and p.point_kind='PRED'", Long.class, targetId)).isEqualTo(1L);
    }
}
