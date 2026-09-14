package com.uav.lowaltitude.modules.fusion.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 融合效果日指标（H2）：无 fusion:read 403 先于参数解析；分母为 0 → {value:null, availability}；夹具插入 FUSED 层点、血缘与真值后计数正确。
 * 夹具全部带随机后缀并按自身归属过滤，阶段 2/7/8 种子不会污染断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FusionMetricsApiTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private String suffix, org, district, sourceId, sourceCode, session, domain;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "s8m-org-" + suffix; district = "s8m-dist-" + suffix; sourceId = "s8m-src-" + suffix; sourceCode = "s8m-radar-" + suffix;
        domain = "replay|" + org + "|" + district;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "S8M-" + suffix, "效果测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "S8M-" + suffix, "效果测试区域");
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version) values (?,?,?,true,'replay','RADAR',?,?,0)",
                sourceId, sourceCode, "效果测试雷达", ts(T0), ts(T0));
        session = user("fusion:read");
    }

    @Test
    void missingFusionReadIsForbiddenBeforeParameterParsing() throws Exception {
        String targetOnly = user("target:read");
        mvc.perform(get("/api/v1/fusion/metrics/daily?wat=1").header("Authorization", bearer(targetOnly))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window()).header("Authorization", bearer(targetOnly))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fusion/metrics/daily?wat=1").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fusion/metrics/daily").header("Authorization", bearer(session)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
        mvc.perform(get("/api/v1/fusion/metrics/daily?from=5&to=5").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window() + "&domain=browser|x|y").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
    }

    @Test
    void emptyDataYieldsNoRowsAndZeroDenominatorsYieldNullRatios() throws Exception {
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window() + "&domain=" + domain).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.domain").value(domain));
        // 只有一条 SWITCH 血缘：有行但没有任何帧与真值 → 三个比率分母为 0。
        String target = target("t-only");
        lineage("SWITCH", target, null, T0.plusMinutes(1));
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window() + "&domain=" + domain).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].fusion_domain_key").value(domain))
                .andExpect(jsonPath("$.data.items[0].day").value("2026-09-05"))
                .andExpect(jsonPath("$.data.items[0].id_switch_count").value(1))
                .andExpect(jsonPath("$.data.items[0].tracked_targets").value(0))
                .andExpect(jsonPath("$.data.items[0].total_frames").value(0))
                .andExpect(jsonPath("$.data.items[0].interrupt_rate.value").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].interrupt_rate.availability").value("NO_DENOMINATOR"))
                .andExpect(jsonPath("$.data.items[0].duplicate_target_rate.value").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].duplicate_target_rate.availability").value("NO_GROUND_TRUTH"))
                .andExpect(jsonPath("$.data.items[0].association_accuracy.availability").value("NO_GROUND_TRUTH"));
    }

    @Test
    void fusedFramesLineageAndGroundTruthProduceExpectedCounts() throws Exception {
        // 目标 A：FUSED 层 4 点（3 实测/插值 + 1 预测）；血缘 CREATE（不计）+ SWITCH + MERGE（计 2 次）。
        String a = target("a"), b = target("b"), c = target("c");
        String fused = track(a, null, "FUSED", "fused:" + a);
        point(fused, 1, T0, "MEAS", null); point(fused, 2, T0.plusSeconds(1), "MEAS", null);
        point(fused, 3, T0.plusSeconds(2), "PRED", null); point(fused, 4, T0.plusSeconds(3), "BRIDGE", null);
        lineage("CREATE", a, null, T0); lineage("SWITCH", a, null, T0.plusSeconds(1)); lineage("MERGE", null, b, T0.plusSeconds(2));
        // 真值：键 K-A 的 3 条观测落到 a(2 条) 与 b(1 条)（重复目标）；键 K-B 的 2 条观测都落到 c。
        String rawA = track(a, link(a, "R-1"), "RAW", "R-1"), rawB = track(b, link(b, "R-2"), "RAW", "R-2"), rawC = track(c, link(c, "R-3"), "RAW", "R-3");
        truthObservation(rawA, 1, "R-1", T0, "K-A"); truthObservation(rawA, 2, "R-1", T0.plusSeconds(1), "K-A");
        truthObservation(rawB, 1, "R-2", T0.plusSeconds(1), "K-A");
        truthObservation(rawC, 1, "R-3", T0, "K-B"); truthObservation(rawC, 2, "R-3", T0.plusSeconds(1), "K-B");

        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window()).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].tracked_targets").value(1))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].id_switch_count").value(2))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].short_lost_frames").value(1))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].total_frames").value(4))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].interrupt_rate.value").value(0.25))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].interrupt_rate.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].duplicate_targets").value(1))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].truth_targets").value(2))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].duplicate_target_rate.value").value(0.5))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].correct_associations").value(4))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].total_associations").value(5))
                .andExpect(jsonPath("$.data.items[?(@.fusion_domain_key=='" + domain + "')].association_accuracy.value").value(0.8));

        // 被并目标 b 经 alias 归到 a 之后：K-A 只剩一个目标，重复率归零，关联准确率 1。
        jdbc.update("insert into target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at) values (?,?,?,?)",
                b, a, jdbc.queryForObject("select lineage_id from target_lineage where origin_target_id=? and op='MERGE'", String.class, b), ts(T0.plusSeconds(2)));
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window() + "&domain=" + domain).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].duplicate_targets").value(0))
                .andExpect(jsonPath("$.data.items[0].duplicate_target_rate.value").value(0.0))
                .andExpect(jsonPath("$.data.items[0].association_accuracy.value").value(1.0));
        // 窗口在数据之后：没有行；ASSIGNED 用户看不到其他元组。
        long later = T0.plusDays(2).toInstant().toEpochMilli();
        mvc.perform(get("/api/v1/fusion/metrics/daily?from=" + later + "&to=" + (later + 1) + "&domain=" + domain).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        String otherScope = userInScope("fusion:read", "seed-stage3-org", "seed-stage3-district");
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window() + "&domain=" + domain).header("Authorization", bearer(otherScope)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    private String window() { return "from=" + T0.minusHours(1).toInstant().toEpochMilli() + "&to=" + T0.plusHours(1).toInstant().toEpochMilli(); }

    private String target(String tag) {
        String id = "s8m-target-" + tag + "-" + suffix;
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'replay',?,?,?,?,0)",
                id, "MB-S8M-" + tag + "-" + suffix, org, district, ts(T0), ts(T0));
        return id;
    }

    private String link(String target, String external) {
        String id = "s8m-link-" + external + "-" + suffix;
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                id, target, sourceId, "ds-" + suffix, external, ts(T0));
        return id;
    }

    private String track(String target, String linkId, String layer, String external) {
        String id = "s8m-track-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values (?,?,?,?,?,?,?)",
                id, target, linkId, external + "-" + suffix, layer, ts(T0), ts(T0));
        return id;
    }

    private void point(String track, long seq, OffsetDateTime at, String kind, String observationId) {
        jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,point_kind,observation_id,created_at) values (?,?,?,?,?,cast(? as geometry),?,?,?)",
                UUID.randomUUID().toString(), track, seq, ts(at), ts(at), "SRID=4326;POINT(118.6 37.4)", kind, observationId, ts(at));
    }

    /** 一条真值观测：source_observation + 真值行 + RAW 层轨迹点（observation_id 回指观测）。 */
    private void truthObservation(String rawTrack, long seq, String external, OffsetDateTime at, String truthKey) {
        String observation = UUID.randomUUID().toString();
        jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,external_target_id,observed_at,received_at,quality,source_mode,owner_org_id,district_id,created_at) values (?,?,'RADAR',?,?,?,?,cast('{}' as json),'replay',?,?,?)",
                observation, sourceId, "ds-" + suffix, external, ts(at), ts(at), org, district, ts(at));
        jdbc.update("insert into replay_ground_truth (dataset_id,scenario,record_no,true_target_key,source_code,external_target_id,observed_at,created_at) values (?,?,?,?,?,?,?,?)",
                "ds-" + suffix, "S1", seq, truthKey + "-" + suffix, sourceCode, external, ts(at), ts(at));
        point(rawTrack, seq, at, "MEAS", observation);
    }

    private void lineage(String op, String survivor, String origin, OffsetDateTime at) {
        jdbc.update("insert into target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,snapshots,created_at) values (?,?,?,?,?,cast('[]' as json),cast('[]' as json),cast('{}' as json),'test','demo-v1','SYSTEM',cast('{}' as json),?)",
                UUID.randomUUID().toString(), op, ts(at), survivor, origin, ts(at));
    }

    private String user(String... permissions) { return userInScope(permissions, org, district); }
    private String userInScope(String permission, String scopeOrg, String scopeDistrict) { return userInScope(new String[] { permission }, scopeOrg, scopeDistrict); }

    private String userInScope(String[] permissions, String scopeOrg, String scopeDistrict) {
        String tag = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-S8M-" + tag, userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "s8m-" + tag, "效果测试员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, scopeOrg, scopeDistrict);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static Timestamp ts(OffsetDateTime at) { return Timestamp.from(at.toInstant()); }
    private static String bearer(String token) { return "Bearer " + token; }
}
