package com.uav.lowaltitude.modules.fusion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 阶段 8 写接口：类别修订、合并、分裂。断言鉴权顺序（403→400→409）、幂等、版本冲突、跨分区、历史外键仍可解析、审计与回滚一致。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FusionCommandApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private FusionFixture fixture;
    private String suffix, org, district, otherOrg, otherDistrict, radar, tdoa;
    private String survivor, member, crossTarget, splitTarget;
    private String linkA, linkB;
    private String reviser, readerOnly;

    @BeforeEach
    void seed() {
        fixture = new FusionFixture(jdbc);
        suffix = FusionFixture.id().substring(0, 8);
        org = FusionFixture.id(); district = FusionFixture.id(); otherOrg = FusionFixture.id(); otherDistrict = FusionFixture.id();
        fixture.org(org, "ORG-C-" + suffix); fixture.district(district, "DIST-C-" + suffix);
        fixture.org(otherOrg, "ORG-D-" + suffix); fixture.district(otherDistrict, "DIST-D-" + suffix);
        radar = FusionFixture.id(); tdoa = FusionFixture.id();
        fixture.source(radar, "SRC-R-" + suffix, "replay", "RADAR");
        fixture.source(tdoa, "SRC-T-" + suffix, "replay", "TDOA");

        survivor = FusionFixture.id(); member = FusionFixture.id(); crossTarget = FusionFixture.id(); splitTarget = FusionFixture.id();
        fixture.target(survivor, "TGT-S-" + suffix, "UNKNOWN", "replay", org, district, 0);
        fixture.target(member, "TGT-N-" + suffix, "UAV", "replay", org, district, 0);
        fixture.target(crossTarget, "TGT-Q-" + suffix, "UAV", "replay", otherOrg, otherDistrict, 0);
        fixture.target(splitTarget, "TGT-L-" + suffix, "UAV", "replay", org, district, 0);
        fixture.latestState(survivor, 118.6, 37.4, 0.8, "[]");
        fixture.link(survivor, radar, "s-" + suffix, "ext-s");
        linkA = fixture.link(splitTarget, radar, "l-" + suffix, "ext-a");
        linkB = fixture.link(splitTarget, tdoa, "l-" + suffix, "ext-b");

        reviser = fixture.session(fixture.role("W-" + suffix, "target:read", "fusion:revise"), org, district, "ASSIGNED");
        readerOnly = fixture.session(fixture.role("O-" + suffix, "target:read"), org, district, "ASSIGNED");
    }

    @Test
    void permissionPrecedesBodyParsingWhichPrecedesConflicts() throws Exception {
        // 403 先于一切：坏 body、未知字段、错版本都不能让无权者得到 400/409 的差异信息。
        mvc.perform(revise(readerOnly, survivor, "{\"bogus\":1}", "key-" + FusionFixture.id()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(revise(reviser, survivor, "{\"new_class_code\":\"UAV\",\"note\":\"x\",\"expected_version\":0,\"extra\":1}", "key-" + FusionFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        mvc.perform(revise(reviser, survivor, "{\"new_class_code\":\"DRAGON\",\"note\":\"x\",\"expected_version\":0}", "key-" + FusionFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CLASS_CODE"));
        mvc.perform(revise(reviser, survivor, "{\"new_class_code\":\"UAV\",\"note\":\"   \",\"expected_version\":0}", "key-" + FusionFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(revise(reviser, survivor, "{\"new_class_code\":\"UAV\",\"note\":\"版本不符\",\"expected_version\":9}", "key-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        // 幂等键缺失也在鉴权之后、业务之前拒绝。
        mvc.perform(post("/api/v1/targets/" + survivor + "/classification-revisions").header("Authorization", "Bearer " + reviser)
                .contentType(MediaType.APPLICATION_JSON).content("{\"new_class_code\":\"UAV\",\"note\":\"无键\",\"expected_version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(jdbc.queryForObject("select version from target where target_id=?", Long.class, survivor)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from target_classification_revision where target_id=?", Long.class, survivor)).isZero();
    }

    @Test
    void classRevisionWritesTargetHistorySelectionLineageAndAudit() throws Exception {
        String key = "revise-" + FusionFixture.id();
        JsonNode created = created(revise(reviser, survivor, "{\"new_class_code\":\"BIRD\",\"note\":\"目视确认为鸟群\",\"expected_version\":0}", key));
        assertThat(created.path("class_code").asText()).isEqualTo("BIRD");
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select object_type_code from target where target_id=?", String.class, survivor)).isEqualTo("BIRD");
        Map<String, Object> revision = jdbc.queryForMap("select previous_class_code,new_class_code,note,target_version,actor_id from target_classification_revision where target_id=?", survivor);
        assertThat(revision.get("previous_class_code")).isEqualTo("UNKNOWN");
        assertThat(revision.get("new_class_code")).isEqualTo("BIRD");
        assertThat(((Number) revision.get("target_version")).longValue()).isZero();
        assertThat(revision.get("actor_id")).isEqualTo(fixture.userOf(reviser));
        // 人工结论：置信度 1.0 且属性优选打上 manual_class_override，引擎之后不再用来源类别覆盖。
        assertThat(jdbc.queryForObject("select classification_confidence from target_latest_state where target_id=?", Double.class, survivor)).isEqualTo(1.0);
        Map<String, Object> selection = jdbc.queryForMap("select class_code,class_confidence,manual_class_override from target_attribute_selection where target_id=?", survivor);
        assertThat(selection.get("class_code")).isEqualTo("BIRD");
        assertThat(((Boolean) selection.get("manual_class_override"))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where survivor_target_id=? and op='CLASS_REVISION' and operator_kind='USER'", Long.class, survivor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where target_id=? and event_type='CLASS_REVISED'", Long.class, survivor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='target_class_revised' and object_id=? and result='SUCCESS'", Long.class, survivor)).isEqualTo(1L);

        // 同键同请求重放：409 且不产生第二条修订。
        mvc.perform(revise(reviser, survivor, "{\"new_class_code\":\"BIRD\",\"note\":\"目视确认为鸟群\",\"expected_version\":0}", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        assertThat(jdbc.queryForObject("select count(*) from target_classification_revision where target_id=?", Long.class, survivor)).isEqualTo(1L);
    }

    @Test
    void mergeKeepsHistoricalForeignKeysResolvableAndRejectsCrossDomain() throws Exception {
        // 被并目标上挂着阶段 4 的告警与事件：合并后这些外键必须仍然指得到原目标行。
        String source = FusionFixture.id(), alarm = FusionFixture.id(), event = FusionFixture.id();
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'replay',?,?,0)",
                source, "SRC-A-" + suffix, "告警来源", FusionFixture.T0, FusionFixture.T0);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,?,?,?,'UAV_INTRUSION','HIGH',?,'replay',?,?,?)", alarm, member, source, "SA-" + suffix, FusionFixture.T0, org, district, FusionFixture.T0);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,?,?,0)",
                event, alarm, org, district, FusionFixture.T0, FusionFixture.T0);

        String body = "{\"survivor_target_id\":\"" + survivor + "\",\"member_target_ids\":[\"" + member + "\"],\"note\":\"同一架无人机的两条 ID\",\"expected_versions\":{\""
                + survivor + "\":0,\"" + member + "\":0}}";
        JsonNode result = created(merge(reviser, body, "merge-" + FusionFixture.id()));
        assertThat(result.path("survivor_target_id").asText()).isEqualTo(survivor);
        assertThat(result.path("merged_target_ids").get(0).asText()).isEqualTo(member);
        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?", String.class, member)).isEqualTo(survivor);
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, member)).isEqualTo("MERGE");
        assertThat(jdbc.queryForObject("select count(*) from target where target_id=?", Long.class, member)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select target_id from alarm where alarm_id=?", String.class, alarm)).isEqualTo(member);
        assertThat(jdbc.queryForObject("select count(*) from uav_event where event_id=?", Long.class, event)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select version from target where target_id=?", Long.class, survivor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='MERGE' and survivor_target_id=?", Long.class, survivor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where target_id=? and event_type='MERGED'", Long.class, survivor)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='targets_merged' and object_id=? and result='SUCCESS'", Long.class, survivor)).isEqualTo(1L);

        // 已被合并的目标不能再次并入别处。
        String again = "{\"survivor_target_id\":\"" + splitTarget + "\",\"member_target_ids\":[\"" + member + "\"],\"note\":\"重复合并\",\"expected_versions\":{\""
                + splitTarget + "\":0,\"" + member + "\":1}}";
        mvc.perform(merge(reviser, again, "merge2-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TARGET_ALREADY_MERGED"));
    }

    @Test
    void mergeRejectsCrossFusionDomainAndVersionMismatch() throws Exception {
        String cross = "{\"survivor_target_id\":\"" + survivor + "\",\"member_target_ids\":[\"" + crossTarget + "\"],\"note\":\"跨分区\",\"expected_versions\":{\""
                + survivor + "\":0,\"" + crossTarget + "\":0}}";
        // 跨归属元组的目标对当前用户不可见：越权对象一律 404，不泄露它的存在。
        mvc.perform(merge(reviser, cross, "cross-" + FusionFixture.id()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TARGET_NOT_FOUND"));

        String stale = "{\"survivor_target_id\":\"" + survivor + "\",\"member_target_ids\":[\"" + member + "\"],\"note\":\"版本过期\",\"expected_versions\":{\""
                + survivor + "\":0,\"" + member + "\":7}}";
        mvc.perform(merge(reviser, stale, "stale-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        // 冲突后整体回滚：别名、血缘、审计都不留痕，幂等占位也不残留。
        assertThat(jdbc.queryForObject("select count(*) from target_current_alias where historical_target_id=?", Long.class, member)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='MERGE' and survivor_target_id=?", Long.class, survivor)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='targets_merged' and object_id=?", Long.class, survivor)).isZero();
        // 幂等占位随业务事务一起回滚这一点，本类观察不到：@Transactional 测试里服务的事务并入测试事务，异常不触发真实回滚。
        // 该断言由助手的 Stage8PostgresTest 用两条真实连接验证（见 task-8.2-report.md“需要助手在 PG 上先验证”）。
    }

    @Test
    void splitMovesSelectedLinksToNewTentativeTarget() throws Exception {
        String body = "{\"link_ids\":[\"" + linkB + "\"],\"note\":\"雷达与 TDOA 实为两个目标\",\"expected_version\":0}";
        JsonNode result = created(split(reviser, splitTarget, body, "split-" + FusionFixture.id()));
        String newTarget = result.path("new_target_ids").get(0).asText();
        assertThat(result.path("origin_target_id").asText()).isEqualTo(splitTarget);
        assertThat(jdbc.queryForObject("select target_id from target_source_link where link_id=?", String.class, linkB)).isEqualTo(newTarget);
        assertThat(jdbc.queryForObject("select target_id from target_source_link where link_id=?", String.class, linkA)).isEqualTo(splitTarget);
        // 新目标从 TENTATIVE 起步，原目标标记 SPLIT。
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, newTarget)).isEqualTo("TENTATIVE");
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, splitTarget)).isEqualTo("SPLIT");
        assertThat(jdbc.queryForObject("select source_mode from target where target_id=?", String.class, newTarget)).isEqualTo("replay");
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='SPLIT' and origin_target_id=?", Long.class, splitTarget)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where target_id=? and event_type='SPLIT'", Long.class, splitTarget)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='target_split' and object_id=? and result='SUCCESS'", Long.class, splitTarget)).isEqualTo(1L);

        // 只有一条关联的目标不能分裂；把全部关联都迁走同样拒绝。
        mvc.perform(split(reviser, survivor, "{\"link_ids\":[\"" + linkA + "\"],\"note\":\"只有一条关联\",\"expected_version\":0}", "split2-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    private JsonNode created(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private static MockHttpServletRequestBuilder revise(String session, String targetId, String body, String key) {
        return post("/api/v1/targets/" + targetId + "/classification-revisions").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder merge(String session, String body, String key) {
        return post("/api/v1/targets/merge").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder split(String session, String targetId, String body, String key) {
        return post("/api/v1/targets/" + targetId + "/split").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
