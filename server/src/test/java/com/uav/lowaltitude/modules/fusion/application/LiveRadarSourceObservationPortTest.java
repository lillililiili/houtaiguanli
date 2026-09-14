package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;

/**
 * 实测雷达提升端口在**开关打开**时的行为（决策 8.5-13/8.5-14）。
 *
 * 这一类不依赖 E1 的 `LiveRadarFrameMapper`：端口只写 {@code inbox_message} 信封，映射是管线领取之后的事。
 * 因此三件事现在就能钉死——信封字段与 `payload_hash`、未注册设备拒收、同键重发的两种结局（同哈希丢弃 / 不同哈希冲突）。
 *
 * 开关在这里是打开的（{@link TestPropertySource}），所以注入到的必然是实测实现而不是 Noop；
 * 顺带证明了两个实现的 `@ConditionalOnProperty` 确实互斥（不互斥的话这个上下文根本起不来）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.fusion.live-promotion.enabled=true",
        // 摄取 Worker 不起：本类只验证"写进 inbox 的信封长什么样"，不希望后台线程把行领走改状态。
        "app.fusion.enabled=false",
        "app.fusion.replay.run-on-start=false",
        "app.dev-seed.enabled=false"
})
class LiveRadarSourceObservationPortTest {

    @Autowired SourceObservationPort port;
    @Autowired JdbcTemplate jdbc;

    private static final java.sql.Timestamp T0 = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-07T12:00:00Z"));

    private String deviceCode, sourceId;

    @BeforeEach
    void registerDevice() {
        assertThat(port).as("开关打开时注入到的必须是实测实现，不是 Noop")
                .isInstanceOf(LiveRadarSourceObservationPort.class);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        deviceCode = "RADAR-LIVE-" + suffix;
        sourceId = UUID.randomUUID().toString();
        jdbc.update("insert into integration_source (source_id,source_code,source_type,name,source_mode,enabled,credential_ref,created_at,updated_at,version)"
                + " values (?,?,'RADAR',?,'live',true,null,?,?,0)", sourceId, deviceCode, "实测雷达 " + deviceCode, T0, T0);
    }

    /** 信封三键与哈希：A 侧只管上报，能不能被管线领取全靠这几列写对。 */
    @Test
    void writesTheContractEnvelopeAndNothingElse() {
        long observationsBefore = count("source_observation");
        long targetsBefore = count("target");

        port.accept(List.of(frame(1_000L, 7)));

        Map<String, Object> row = jdbc.queryForMap("select source,source_msg_id,source_id,status,payload_hash,"
                + "cast(payload as varchar) as payload_text from inbox_message where source=?", "live-radar:" + deviceCode);
        assertThat(row).containsEntry("source", "live-radar:" + deviceCode)
                .containsEntry("source_msg_id", "1000:7")          // 契约 §2：<boot_micros>:<frame_id>
                .containsEntry("source_id", sourceId)              // 指向 A 注册的设备来源
                .containsEntry("status", "RECEIVED");
        assertThat((String) row.get("payload_hash")).as("契约要求 SHA-256 十六进制").matches("^[0-9a-f]{64}$");
        assertThat((String) row.get("payload_text")).as("payload 原样透传，端口不替设备改写任何字段")
                .contains("external_track_id").contains("z_m");

        // 只写信封：业务表一行都不能多。映射与写入属于管线（决策 8.5-13）。
        assertThat(count("source_observation")).isEqualTo(observationsBefore);
        assertThat(count("target")).isEqualTo(targetsBefore);
    }

    /**
     * 未注册或已停用的设备一律拒收，且**不自动建来源行**（决策 8.5-14）。
     * 凭一条上报凭空建来源，等于让未注册设备的数据混进统一目标库，而 source_type / schema_status 只有注册流程知道该填什么。
     */
    @Test
    void rejectsFramesFromDevicesThatAreNotRegisteredOrNotEnabled() {
        String unknown = "RADAR-UNKNOWN-" + UUID.randomUUID().toString().substring(0, 8);
        port.accept(List.of(Map.of("device_id", unknown, "boot_micros", 2_000L, "frame_id", 1, "items", List.of())));
        assertThat(count("inbox_message where source=?", "live-radar:" + unknown)).as("未注册设备的帧不得入库").isZero();
        assertThat(count("integration_source where source_code=?", unknown)).as("端口不得自动建来源行").isZero();

        // 停用是运行期的明确决定，不能被一条上报绕过。
        jdbc.update("update integration_source set enabled=false where source_id=?", sourceId);
        port.accept(List.of(frame(2_000L, 2)));
        assertThat(count("inbox_message where source=?", "live-radar:" + deviceCode)).isZero();
    }

    /**
     * 同一条报文被重发的两种结局，必须分开：
     *   同键**同哈希** → 幂等丢弃，不是错误（网络重传很常见）；
     *   同键**不同哈希** → 来源冲突，已存的信封**保持不变**。先到的那份才是当时真正收到的证据，
     *     后到的把它盖掉等于篡改历史输入；端口显式告警但不让整批失败。
     */
    @Test
    void retransmissionsAreIdempotentAndConflictingPayloadsNeverOverwrite() {
        port.accept(List.of(frame(3_000L, 1)));
        String storedHash = jdbc.queryForObject("select payload_hash from inbox_message where source=? and source_msg_id='3000:1'",
                String.class, "live-radar:" + deviceCode);

        // 同键同哈希：丢弃，不入第二条。
        port.accept(List.of(frame(3_000L, 1)));
        assertThat(count("inbox_message where source=? and source_msg_id='3000:1'", "live-radar:" + deviceCode))
                .as("重复帧不是新事实").isEqualTo(1L);

        // 同键不同哈希：拒绝覆盖，原信封原封不动。
        Map<String, Object> tampered = Map.of("device_id", deviceCode, "boot_micros", 3_000L, "frame_id", 1,
                "items", List.of(Map.of("external_track_id", "trk-CHANGED", "longitude", 0.0, "latitude", 0.0)));
        port.accept(List.of(tampered));
        assertThat(count("inbox_message where source=? and source_msg_id='3000:1'", "live-radar:" + deviceCode)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select payload_hash from inbox_message where source=? and source_msg_id='3000:1'",
                String.class, "live-radar:" + deviceCode)).as("冲突不得覆盖已存证据").isEqualTo(storedHash);
        assertThat(jdbc.queryForObject("select cast(payload as varchar) from inbox_message where source=? and source_msg_id='3000:1'",
                String.class, "live-radar:" + deviceCode)).doesNotContain("trk-CHANGED");

        // 一帧冲突不该影响同批的其他帧。
        port.accept(List.of(tampered, frame(3_000L, 2)));
        assertThat(count("inbox_message where source=? and source_msg_id='3000:2'", "live-radar:" + deviceCode)).isEqualTo(1L);
    }

    private Map<String, Object> frame(long bootMicros, int frameId) {
        return Map.of("device_id", deviceCode, "boot_micros", bootMicros, "frame_id", frameId,
                "items", List.of(Map.of("external_track_id", "trk-1", "longitude", 118.62, "latitude", 37.42,
                        "z_m", 120.0, "velocity_x_mps", 3.0, "velocity_y_mps", -1.0, "velocity_z_mps", 0.0,
                        "snr_db", 18.0, "rcs_m2", 0.4, "classification", "UAV")));
    }

    private long count(String tableAndWhere, Object... args) {
        return jdbc.queryForObject("select count(*) from " + tableAndWhere, Long.class, args);
    }
}
