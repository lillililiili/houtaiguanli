package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalStage8FusionReplaySeeder;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;

/**
 * 阶段 10（决策 10-1，契约 §4/§8）：fusion_event 的 STATUS_STABLE / UNDETERMINED payload 是最新状态摘要，
 * 给 A 的光电跟踪触发用。取不到的键不出现、不写 null；飞手位置有则出键；告警/风险只读现状。
 * 上下文启动时 LocalStage8FusionReplaySeeder 已用真实 DefaultFusedLayerWriter 回放 v2 数据集（独立 H2 库，不与别的用例共享）。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage10_fusion_event;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class FusionEventPayloadTest {
    private static final String TDOA_SOURCE = "seed-stage8-source-tdoa", EO_SOURCE = "seed-stage8-source-eo";
    private static final FusionDomainKey DOMAIN = new FusionDomainKey("replay", LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT);
    private static final Instant T0 = Instant.parse("2026-09-07T01:00:00Z");
    /** 兜底上限，不是业务阈值：不可判定由 fusion_config 的 degradation.* 参数决定，这里只保证循环有尽头。 */
    private static final int MAX_SOURCELESS_FRAMES = 50;

    @Autowired JdbcTemplate jdbc;
    @Autowired FusedLayerWriter writer;
    @Autowired ObjectMapper json;

    @Test
    void everyStableReplayTargetHasExactlyOneStatusStableCarryingTheSummary() {
        List<String> stableTargets = jdbc.queryForList("select s.target_id from target_track_status s join target t on t.target_id=s.target_id"
                + " where t.unified=true and t.source_mode='replay' and s.status='STABLE'", String.class);
        assertThat(stableTargets).as("回放后至少有一个目标处于 STABLE，否则下面的断言是空转").isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id in (select target_id from target_track_status where status='STABLE') and location is not null", Long.class))
                .as("至少一个 STABLE 目标带位置，位置键的正向分支不能空转").isPositive();
        for (String targetId : stableTargets) {
            List<JsonNode> events = events(targetId, "STATUS_STABLE");
            assertThat(events).as(targetId + " 恰一条 STATUS_STABLE").hasSize(1);
            JsonNode payload = events.get(0);
            assertThat(payload.get("event_type").asText()).isEqualTo("STATUS_STABLE");
            assertThat(payload.get("target_id").asText()).isEqualTo(targetId);
            assertThat(payload.get("status").asText()).isEqualTo("STABLE");
            assertThat(payload.get("target_no").asText()).isEqualTo(jdbc.queryForObject("select target_no from target where target_id=?", String.class, targetId));
            assertThat(payload.hasNonNull("degradation_level")).isTrue();
            assertThat(payload.get("determined").isBoolean()).isTrue();
            JsonNode latest = payload.get("latest_state");
            assertThat(latest).as("latest_state 摘要").isNotNull();
            // 位置与 target_latest_state 同源：有位置的目标两键都是数字；AOA 只给方位的目标（aoa-bearing）没有位置，两键都不出现而不是写 0。
            boolean located = jdbc.queryForObject("select count(*) from target_latest_state where target_id=? and location is not null", Long.class, targetId) > 0;
            assertThat(latest.has("longitude")).as(targetId + " longitude 键与最新状态是否有位置一致").isEqualTo(located);
            assertThat(latest.has("latitude")).isEqualTo(located);
            if (located) {
                assertThat(latest.get("longitude").isNumber()).isTrue();
                assertThat(latest.get("latitude").isNumber()).isTrue();
            }
            assertThat(latest.get("altitude_datum").asText()).isEqualTo("UNCONFIRMED");
            assertThat(latest.get("observed_at").isIntegralNumber()).as("observed_at 是 epoch 毫秒").isTrue();
            // v2 数据集的凌云报文不带 altitude：altitude_raw 取不到就不出键，绝不写 null 或融合高度。
            assertThat(latest.has("altitude_raw")).isFalse();
            // 回放种子不产生告警与风险：alarm_active 明确为 false，max_risk_severity 不出键。
            assertThat(payload.get("alarm_active").isBoolean()).isTrue();
            assertThat(payload.get("alarm_active").asBoolean()).isFalse();
            assertThat(payload.has("max_risk_severity")).isFalse();
            assertNoNulls(payload);
        }
    }

    @Test
    void tdoaPilotTargetSummaryCarriesPilotLocation() {
        String targetId = jdbc.queryForObject("select target_id from target_source_link where external_target_id='D-PILOT'", String.class);
        List<JsonNode> events = events(targetId, "STATUS_STABLE");
        assertThat(events).hasSize(1);
        JsonNode payload = events.get(0);
        // demo-v1 里 TDOA 的 class 权重为 0：只有 TDOA 的帧选不出类别来源，class_code 只可能来自 target.object_type_code 的回退（决策 10-11），
        // 与目标行一致；目标行也没有时不出键（类别正例见下一个用例，回退正例见 classCodeFallsBackToTargetObjectTypeCode）。
        String objectType = jdbc.queryForObject("select object_type_code from target where target_id=?", String.class, targetId);
        assertThat(payload.has("class_code")).isEqualTo(objectType != null);
        if (objectType != null) assertThat(payload.get("class_code").asText()).isEqualTo(objectType);
        JsonNode pilot = payload.get("latest_state").get("pilot_location");
        assertThat(pilot).as("tdoa-pilot 场景的飞手位置必须进摘要，A 的光电跟踪要它区分目标与飞手").isNotNull();
        assertThat(pilot.get("longitude").isNumber()).isTrue();
        assertThat(pilot.get("latitude").isNumber()).isTrue();
        // 飞手位置与目标位置是两个不同的点：数据集把飞手放在基线经度上、目标带噪声偏移。
        assertThat(pilot.get("longitude").asDouble()).isNotEqualTo(payload.get("latest_state").get("longitude").asDouble());
    }

    /**
     * 正例走真实写入器：目标挂着未关闭 uav_event、两条未关闭 flight_risk（MEDIUM/HIGH）与一条已排除的 CRITICAL，
     * 摘要必须给 alarm_active=true、max_risk_severity=HIGH（已排除的不算），altitude_raw 取自位置主源（TDOA）的 quality，
     * class_code 来自类别来源（EO，demo-v1 里 TDOA 的 class 权重为 0）。两源位置/速度相同，加权后的值与输入一致。
     * 之后连续无源帧把降级推到不可判定，UNDETERMINED 事件带同一份摘要，class_code 沿用已保留的归属。
     */
    @Test
    @Transactional
    void openAlarmAndRisksShowUpInTheSummaryAndUndeterminedCarriesTheSameShape() {
        String targetId = UUID.randomUUID().toString();
        String targetNo = "S10-" + targetId.substring(0, 8);
        Timestamp now = Timestamp.from(T0);
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version,unified) values (?,?,'replay',?,?,?,?,0,true)",
                targetId, targetNo, LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT, now, now);
        String alarmId = UUID.randomUUID().toString();
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,?,?,?,'UAV','HIGH',?,?,'replay',?,?,?)", alarmId, targetId, TDOA_SOURCE, "s10-" + alarmId, now, now,
                LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT, now);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,?,?,0)",
                UUID.randomUUID().toString(), alarmId, LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT, now, now);
        risk(targetId, "MEDIUM", "PENDING_VERIFICATION", now);
        risk(targetId, "HIGH", "PENDING_NOTIFICATION", now);
        risk(targetId, "CRITICAL", "EXCLUDED", now);

        writer.write(new TargetFrameResult(targetId, DOMAIN, T0, List.of(tdoa(T0, 118.61, 37.41, 118.60, 37.40), eo(T0, 118.61, 37.41)), TrackStatus.STABLE, 0, "demo-v1"));

        List<JsonNode> stable = events(targetId, "STATUS_STABLE");
        assertThat(stable).hasSize(1);
        JsonNode payload = stable.get(0);
        assertThat(payload.get("target_no").asText()).isEqualTo(targetNo);
        assertThat(payload.get("class_code").asText()).isEqualTo("UAV");
        assertThat(payload.get("alarm_active").asBoolean()).isTrue();
        assertThat(payload.get("max_risk_severity").asText()).as("已排除的 CRITICAL 不算，未关闭里最高是 HIGH").isEqualTo("HIGH");
        JsonNode latest = payload.get("latest_state");
        assertThat(latest.get("longitude").asDouble()).isEqualTo(118.61);
        assertThat(latest.get("latitude").asDouble()).isEqualTo(37.41);
        assertThat(latest.get("altitude_raw").asDouble()).isEqualTo(123.4);
        assertThat(latest.get("altitude_datum").asText()).isEqualTo("UNCONFIRMED");
        assertThat(latest.get("speed_mps").asDouble()).isEqualTo(8.0);
        assertThat(latest.get("heading_deg").asDouble()).isEqualTo(90.0);
        assertThat(latest.get("observed_at").asLong()).isEqualTo(T0.toEpochMilli());
        assertThat(latest.get("pilot_location").get("longitude").asDouble()).isEqualTo(118.60);
        assertThat(latest.get("pilot_location").get("latitude").asDouble()).isEqualTo(37.40);
        assertNoNulls(payload);

        // 同一轨迹段内再来一帧 STABLE：不重复发。
        writer.write(new TargetFrameResult(targetId, DOMAIN, T0.plusSeconds(1), List.of(tdoa(T0.plusSeconds(1), 118.62, 37.42, 118.60, 37.40), eo(T0.plusSeconds(1), 118.62, 37.42)),
                TrackStatus.STABLE, 0, "demo-v1"));
        assertThat(events(targetId, "STATUS_STABLE")).hasSize(1);

        // 连续无源帧直到不可判定：转入那一帧发 UNDETERMINED，摘要形状相同；位置保留最后可信点，类别沿用保留的归属，飞手位置沿用库里保留值。
        Instant at = T0.plusSeconds(1);
        for (int miss = 1; miss <= MAX_SOURCELESS_FRAMES && events(targetId, "UNDETERMINED").isEmpty(); miss++) {
            at = at.plusSeconds(1);
            writer.write(new TargetFrameResult(targetId, DOMAIN, at, List.of(), TrackStatus.SHORT_LOST, miss, "demo-v1"));
        }
        List<JsonNode> undetermined = events(targetId, "UNDETERMINED");
        assertThat(undetermined).as("无源帧累积到不可判定后恰发一条 UNDETERMINED").hasSize(1);
        JsonNode lost = undetermined.get(0);
        assertThat(lost.get("status").asText()).isEqualTo("SHORT_LOST");
        assertThat(lost.get("determined").asBoolean()).isFalse();
        assertThat(lost.get("target_no").asText()).isEqualTo(targetNo);
        assertThat(lost.get("class_code").asText()).isEqualTo("UAV");
        assertThat(lost.get("alarm_active").asBoolean()).isTrue();
        assertThat(lost.get("max_risk_severity").asText()).isEqualTo("HIGH");
        JsonNode lostLatest = lost.get("latest_state");
        assertThat(lostLatest.get("longitude").asDouble()).isEqualTo(118.62);
        assertThat(lostLatest.get("latitude").asDouble()).isEqualTo(37.42);
        assertThat(lostLatest.get("observed_at").asLong()).isEqualTo(at.toEpochMilli());
        assertThat(lostLatest.has("altitude_raw")).as("无源帧没有主源原值，不出键").isFalse();
        assertThat(lostLatest.has("speed_mps")).as("无源帧没有速度，不出键").isFalse();
        assertThat(lostLatest.get("pilot_location").get("longitude").asDouble()).isEqualTo(118.60);
        assertNoNulls(lost);
    }

    /**
     * 决策 10-11：只有 TDOA 的目标在 demo-v1 权重下选不出类别来源（TDOA 的 class 权重为 0），摘要的 class_code 回退到
     * target.object_type_code；目标行也没有时不出键。回退只读目标行，不改属性优选（target_attribute_selection.class_code 仍为空）。
     */
    @Test
    @Transactional
    void classCodeFallsBackToTargetObjectTypeCode() {
        String withType = target("UAV");
        writer.write(new TargetFrameResult(withType, DOMAIN, T0, List.of(tdoa(T0, 118.61, 37.41, 118.60, 37.40)), TrackStatus.STABLE, 0, "demo-v1"));
        List<JsonNode> stable = events(withType, "STATUS_STABLE");
        assertThat(stable).hasSize(1);
        assertThat(stable.get(0).get("class_code").asText()).as("融合选不出类别时回退到 target.object_type_code").isEqualTo("UAV");
        assertThat(jdbc.queryForObject("select class_code from target_attribute_selection where target_id=?", String.class, withType))
                .as("回退只进摘要，不写回属性优选").isNull();
        assertNoNulls(stable.get(0));

        String withoutType = target(null);
        writer.write(new TargetFrameResult(withoutType, DOMAIN, T0, List.of(tdoa(T0, 118.61, 37.41, 118.60, 37.40)), TrackStatus.STABLE, 0, "demo-v1"));
        List<JsonNode> untyped = events(withoutType, "STATUS_STABLE");
        assertThat(untyped).hasSize(1);
        assertThat(untyped.get(0).has("class_code")).as("融合与目标行都没有类别：不出键，不写 null").isFalse();
        assertNoNulls(untyped.get(0));
    }

    private String target(String objectTypeCode) {
        String targetId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(T0);
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,object_type_code,created_at,updated_at,version,unified) values (?,?,'replay',?,?,?,?,?,0,true)",
                targetId, "S10-" + targetId.substring(0, 8), LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT, objectTypeCode, now, now);
        return targetId;
    }

    private void risk(String targetId, String severity, String state, Timestamp at) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,"
                + "height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,"
                + "'ROUTE_DEVIATION',?,?,'ROUTE_DEVIATION','阶段 10 事件摘要夹具',?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,0)",
                id, "s10-" + id, targetId, severity, state, at, at, at);
    }

    /** TDOA 身份主源：带身份线索、飞手位置与设备高度原值（quality.altitude_raw）。 */
    private static SourceEstimate tdoa(Instant at, double lon, double lat, double pilotLon, double pilotLat) {
        return new SourceEstimate(TDOA_SOURCE, "S8-TDOA", "TDOA", "DEMO", null, null, null, at,
                lon, lat, 60.0, null, null, 8.0, 90.0, "UAV", 0.8, "SN-S10", 0.9, PointKind.MEAS,
                Map.of("altitude_raw", 123.4, "altitude_datum", "REFERENCE_UNKNOWN"), pilotLon, pilotLat, "SENSE_DATA");
    }

    /** 光电类别来源：有类别与置信度、无身份线索、无飞手位置。 */
    private static SourceEstimate eo(Instant at, double lon, double lat) {
        return new SourceEstimate(EO_SOURCE, "S8-EO", "EO", "DEMO", null, null, null, at,
                lon, lat, 25.0, null, null, 8.0, 90.0, "UAV", 0.8, null, null, PointKind.MEAS, Map.of(), null, null, "EO_TRACKING");
    }

    private List<JsonNode> events(String targetId, String eventType) {
        List<String> rows = jdbc.queryForList("select cast(payload as varchar) from fusion_event where target_id=? and event_type=? order by occurred_at, event_id", String.class, targetId, eventType);
        List<JsonNode> parsed = new ArrayList<>();
        for (String row : rows) {
            try {
                JsonNode node = json.readTree(row);
                // H2 的 CAST(? AS JSON) 把字符串参数存成 JSON 字符串值（回读带转义引号）；PostgreSQL 的 jsonb 才是对象。两边都按对象断言。
                if (node.isTextual()) node = json.readTree(node.asText());
                parsed.add(node);
            } catch (Exception ex) { throw new IllegalStateException("fusion_event.payload 不是合法 JSON: " + row, ex); }
        }
        return parsed;
    }

    /** 契约：取不到的键不出现，不写 null——递归检查整个 payload。 */
    private static void assertNoNulls(JsonNode node) {
        node.fields().forEachRemaining(entry -> {
            assertThat(entry.getValue().isNull()).as("键 " + entry.getKey() + " 不得为 null").isFalse();
            if (entry.getValue().isObject()) assertNoNulls(entry.getValue());
        });
    }
}
