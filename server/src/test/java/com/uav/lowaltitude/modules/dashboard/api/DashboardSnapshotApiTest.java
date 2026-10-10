package com.uav.lowaltitude.modules.dashboard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardSnapshotApiTest {
    private static final String SOURCE = "seed-stage3-source";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private String suffix;
    private String org;
    private String district;
    private String otherOrg;
    private String otherDistrict;
    private String target;
    private String hiddenTarget;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "dash-org-" + suffix;
        district = "dash-dist-" + suffix;
        otherOrg = "dash-other-org-" + suffix;
        otherDistrict = "dash-other-dist-" + suffix;
        catalog(org, district);
        catalog(otherOrg, otherDistrict);
        target = "dash-target-" + suffix;
        hiddenTarget = "dash-hidden-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                target, "T-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,altitude_amsl_m,speed_mps,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (118.5 37.4)',120,12.5,?,?,? FORMAT JSON,?,?,0)
                """, target, ts(now()), ts(now()), "[]", ts(now()), ts(now()));
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                hiddenTarget, "T-H-" + suffix, otherOrg, otherDistrict, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (119.1 37.8)',?,?,'[]' FORMAT JSON,?,?,0)
                """, hiddenTarget, ts(now()), ts(now()), ts(now()), ts(now()));
    }

    @Test
    void pendingAssessmentsOnlyCountLatestRecordsWithinTodayInShanghai() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "assessment:read");
        long start = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long end = start + 86_400_000L;
        String run = "dash-pending-run-" + suffix;
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at)"
                + " select ?,v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',1,1,0,0,'live',? from rule_set_version v order by v.rule_set_version_id fetch first 1 row only",
                run, ts(now()), ts(now()), ts(now()));
        pendingEvaluation(run, "yesterday", "live", start - 1, "PENDING_REVIEW");
        pendingEvaluation(run, "start", "live", start, "PENDING_REVIEW");
        pendingEvaluation(run, "end", "replay", end - 1, "PENDING_REVIEW");
        pendingEvaluation(run, "tomorrow", "replay", end, "PENDING_REVIEW");
        pendingEvaluation(run, "confirmed", "live", start + 1, "CONFIRMED");
        pendingEvaluation(run, "demo", "mock", start + 1, "PENDING_REVIEW");
        pendingEvaluation(run, "hidden", "live", start + 1, "PENDING_REVIEW");
        jdbc.update("update rule_evaluation set owner_org_id=?,district_id=? where evaluation_id=?",
                otherOrg, otherDistrict, "dash-pending-hidden-" + suffix);
        // 同一目标今天已有较新结论，旧待复核记录不能回流到统计中。
        pendingEvaluation(run, "old", "live", start + 1, "PENDING_REVIEW");
        pendingEvaluation(run, "new", "live", start + 2, "CONFIRMED");
        jdbc.update("update rule_evaluation set target_id=? where evaluation_id=?",
                "dash-today-old-" + suffix, "dash-pending-new-" + suffix);

        mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpis.pending_assessment").value(2));
    }

    private void pendingEvaluation(String run, String name, String sourceMode, long at, String reviewState) {
        todayTarget(name, sourceMode);
        String id = "dash-pending-" + name + "-" + suffix;
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,?,?,'FRESH','NONE','UNDETERMINED',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,? from rule_run r where r.run_id=?",
                id, "dash-today-" + name + "-" + suffix, ts(at), ts(at), org, district, sourceMode, ts(now()), run);
        jdbc.update("insert into legality_review(evaluation_id,review_state,version,owner_org_id,district_id,created_at,updated_at) values (?,?,0,?,?,?,?)",
                id, reviewState, org, district, ts(now()), ts(now()));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/dashboard/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void dashboardReadIsRequiredBeforeAnyParameterIsInterpreted() throws Exception {
        String denied = reader("ASSIGNED", org, district);
        grantAction(denied, "target:read", "alarm:read");
        mvc.perform(get("/api/v1/dashboard/snapshot?foo=1&foo=again").header("Authorization", bearer(denied)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void dashboardOnlyReaderGetsNullCountsAndForbiddenAvailability() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.targets").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.alarms").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.assessments").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.handoffs").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.devices").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.flights").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.airspaces").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.risks").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.stats").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.target_risk").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.sensed_today").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.alarms_today").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.pending_assessment").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.pending_handoffs").value(nullValue()))
                .andExpect(jsonPath("$.data.simulated_included.sensed_today").value(nullValue()))
                .andExpect(jsonPath("$.data.simulated_included.alarms_today").value(nullValue()))
                .andExpect(jsonPath("$.data.simulated_included.flights_today").value(nullValue()))
                .andExpect(jsonPath("$.data.simulated_included.devices").value(nullValue()))
                .andExpect(jsonPath("$.data.trend").value(nullValue()))
                .andExpect(jsonPath("$.data.devices").value(nullValue()))
                .andExpect(jsonPath("$.data.flights").value(nullValue()))
                .andExpect(jsonPath("$.data.closure.evidence.status").value("NOT_BUILT"))
                .andExpect(jsonPath("$.data.map.targets").isEmpty())
                .andExpect(jsonPath("$.data.map.alarms").isEmpty());
    }

    @Test
    void assignedReaderSeesOwnLocatedTargetAndRejectsUnknownQuery() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "target:read", "alarm:read");
        String noLocation = "dash-noloc-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                noLocation, "T-N-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,null,?,?,'[]' FORMAT JSON,?,?,0)
                """, noLocation, ts(now()), ts(now()), ts(now()), ts(now()));
        String alarmId = "dash-alarm-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, target, SOURCE, "SRC-" + alarmId, ts(now()), ts(now()), org, district, ts(now()));
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,0)",
                "dash-event-" + suffix, alarmId, "PENDING_VERIFICATION", org, district, ts(now()), ts(now()));
        String orphanAlarm = "dash-orphan-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','LOW',?,?,'mock',?,?,?)",
                orphanAlarm, noLocation, SOURCE, "SRC-" + orphanAlarm, ts(now()), ts(now()), org, district, ts(now()));
        // 统计口径：同一批里再放真实设备（live）和设备模拟器（replay）的目标与告警各一条。
        // 统计卡数这两条，系统自带的演示样例（mock）不计；其中来自模拟器的条数单独给出。
        String liveTarget = "dash-live-target-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','live',?,?,?,?,?,?,0)",
                liveTarget, "T-L-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        String liveAlarm = "dash-live-alarm-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'live',?,?,?)",
                liveAlarm, liveTarget, SOURCE, "SRC-" + liveAlarm, ts(now()), ts(now()), org, district, ts(now()));
        String replayTarget = "dash-replay-target-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','replay',?,?,?,?,?,?,0)",
                replayTarget, "T-R-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        String replayAlarm = "dash-replay-alarm-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'replay',?,?,?)",
                replayAlarm, replayTarget, SOURCE, "SRC-" + replayAlarm, ts(now()), ts(now()), org, district, ts(now()));
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,0)",
                "dash-replay-event-" + suffix, replayAlarm, "PENDING_VERIFICATION", org, district, ts(now()), ts(now()));

        JsonNode data = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.targets").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.availability.alarms").value("AVAILABLE"))
                // 与运行统计同口径：真实设备和设备模拟器的数据都算，演示样例不算。
                .andExpect(jsonPath("$.data.kpis.sensed_today").value(2))
                .andExpect(jsonPath("$.data.kpis.alarms_today").value(2))
                // 测试环境允许模拟：口径是真实设备加设备模拟器，页面据此写说明。
                .andExpect(jsonPath("$.data.statistics_source_modes[0]").value("live"))
                .andExpect(jsonPath("$.data.statistics_source_modes[1]").value("replay"))
                .andExpect(jsonPath("$.data.statistics_source_modes.length()").value(2))
                // 其中来自设备模拟器的条数单独给出，页面写明，免得被当成现场真实数据。
                .andExpect(jsonPath("$.data.simulated_included.sensed_today").value(1))
                .andExpect(jsonPath("$.data.simulated_included.alarms_today").value(1))
                // 待核实也是计数：模拟器那条算，演示样例那条不算。
                .andExpect(jsonPath("$.data.closure.pending_verification").value(1))
                // 最新告警列表不是计数，仍按全部来源，总数与列表同口径。
                .andExpect(jsonPath("$.data.alarms.total").value(4))
                // 能读目标、不能读风险：今日目标照给，风险分档不给。
                .andExpect(jsonPath("$.data.availability.risks").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.target_risk").value(nullValue()))
                .andReturn().getResponse().getContentAsString()).get("data");

        assertThat(ids(data.get("map").get("targets"))).contains(target).doesNotContain(hiddenTarget, noLocation);
        assertThat(ids(data.get("map").get("alarms"))).contains(alarmId).doesNotContain(orphanAlarm);
        assertThat(ids(data.get("alarms").get("items"))).contains(alarmId, orphanAlarm, liveAlarm, replayAlarm);

        mvc.perform(get("/api/v1/dashboard/snapshot?extra=1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * 设备总数与今日计划也跟运行统计一个口径：真实设备和设备模拟器的算，系统自带的演示样例
     * （mock，设备列表里同样显示为演示的 live+simulated 本机模拟设备，以及后台预置、从不上报的回放设备）不算。
     */
    @Test
    void deviceAndFlightStatisticsCountLiveAndSimulatorButNotDemoSamples() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantModule(token, "monitoring");
        grantAction(token, "device:read", "flight:read");
        device("live", "live", false);
        device("local-sim", "live", true);
        device("mock", "mock", true);
        device("replay", "replay", true);
        reported("replay");
        device("replay-silent", "replay", true);
        plan("live-plan", "live", "EXECUTING");
        plan("mock-plan", "mock", "EXECUTING");
        plan("replay-plan", "replay", "EXECUTING");
        plan("mock-done", "mock", "COMPLETED");
        plan("replay-done", "replay", "COMPLETED");

        mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.devices").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.availability.flights").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.devices.total").value(2))
                .andExpect(jsonPath("$.data.devices.online").value(1))
                .andExpect(jsonPath("$.data.devices.source_mode").value("mixed"))
                .andExpect(jsonPath("$.data.devices.simulated").value(false))
                .andExpect(jsonPath("$.data.simulated_included.devices").value(1))
                .andExpect(jsonPath("$.data.flights.today").value(3))
                .andExpect(jsonPath("$.data.flights.executing").value(2))
                .andExpect(jsonPath("$.data.flights.completed").value(1))
                .andExpect(jsonPath("$.data.simulated_included.flights_today").value(2));

        // 大屏页面直接调的设备概况接口，同一口径。
        mvc.perform(get("/api/v1/device-monitor/overview?statistics_scope=true").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.source_mode").value("mixed"));
        mvc.perform(get("/api/v1/device-monitor/overview?formal_only=true").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/device-monitor/overview?statistics_scope=true&formal_only=true").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * ZT-17 复测 2：今日感知目标和风险分档就是运行统计选今天时的那一份。原先大屏按"今天出现过、不含被合并"数目标、
     * 按研判等级抽样 100 条分档，运行统计按首次发现、含被合并的目标数、按风险记录分档，两处对不上（393 对 414，高风险 3 对 0）。
     * 现在同一批目标（被合并的不另计，系统自带的演示样例不计），同一套分档（超高/高/中/低/未识别），五档相加等于今日感知目标。
     */
    @Test
    void todayTargetsAndRiskTiersAreTheOperationsStatisticsForToday() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantModule(token, "statistics");
        grantAction(token, "target:read", "risk:read");
        todayTarget("live-critical", "live");
        risk("live-critical", "live", "CRITICAL");
        todayTarget("live-plain", "live");
        todayTarget("replay-medium", "replay");
        risk("replay-medium", "replay", "MEDIUM");
        // 模拟器目标挂着的是一条演示样例的风险：风险不计，目标照计，归入未识别。
        todayTarget("replay-demo-risk", "replay");
        risk("replay-demo-risk", "mock", "LOW");
        // 被合并的目标是存活目标的别名：目标和它名下的风险都不另计。
        todayTarget("replay-merged", "replay");
        risk("replay-merged", "replay", "HIGH");
        jdbc.update("insert into target_track_status (target_id,status,since,updated_at) values (?,'MERGE',?,?)",
                "dash-today-replay-merged-" + suffix, ts(now()), ts(now()));
        // 分档看风险记录的等级，不再看研判等级。
        String run = "dash-run-" + suffix;
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at)"
                + " select ?,v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',1,1,0,0,'live',? from rule_set_version v order by v.rule_set_version_id fetch first 1 row only",
                run, ts(now()), ts(now()), ts(now()));
        evaluation(run, "dash-today-live-plain-" + suffix, "live", "HIGH");

        JsonNode dashboard = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.risks").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.kpis.sensed_today").value(4))
                .andExpect(jsonPath("$.data.simulated_included.sensed_today").value(2))
                .andExpect(jsonPath("$.data.target_risk.critical").value(1))
                .andExpect(jsonPath("$.data.target_risk.high").value(0))
                .andExpect(jsonPath("$.data.target_risk.medium").value(1))
                .andExpect(jsonPath("$.data.target_risk.low").value(0))
                .andExpect(jsonPath("$.data.target_risk.ungraded").value(2))
                .andExpect(jsonPath("$.data.target_risk.truncated").value(false))
                // 趋势的今天一格也是这一份；不能读研判时非法数为空（原先拆箱空值，整个大屏 500）。
                .andExpect(jsonPath("$.data.trend.days[6].total").value(4))
                .andExpect(jsonPath("$.data.trend.days[6].illegal").value(nullValue()))
                .andReturn().getResponse().getContentAsString()).get("data");

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        JsonNode stats = json.readTree(mvc.perform(get("/api/v1/stats/operations").param("from", today).param("to", today)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(stats.path("summary").path("total").asLong()).isEqualTo(dashboard.path("kpis").path("sensed_today").asLong());
        JsonNode tiers = dashboard.path("target_risk");
        assertThat(stats.path("by_risk").findValuesAsText("name")).containsExactly("超高风险", "高风险", "中风险", "低风险", "未识别");
        assertThat(stats.path("by_risk").findValues("value").stream().map(JsonNode::asInt).toList())
                .containsExactly(tiers.path("critical").asInt(), tiers.path("high").asInt(), tiers.path("medium").asInt(),
                        tiers.path("low").asInt(), tiers.path("ungraded").asInt());
        assertThat(stats.path("summary").path("high_risk").asInt()).isEqualTo(tiers.path("critical").asInt() + tiers.path("high").asInt());
    }

    /** 大屏“交接待办”就是“移送与处罚”页的“待发送”：无人机事件的处罚移送里还没发出去的；风险的“通知上级”不算（新-2 第 6 点）。 */
    @Test
    void handoffTodoIsThePunishPagesWaitingToSend() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "handoff:read");
        String submitter = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token);
        String punish = recipient("UAV_PUNISHMENT"), notice = recipient("RISK_NOTICE");
        punishment("waiting-replay", "replay", "PENDING_DELIVERY", punish, submitter);
        punishment("waiting-live", "live", "PENDING_DELIVERY", punish, submitter);
        punishment("sent", "replay", "SUBMITTED", punish, submitter);
        todayTarget("notice", "replay");
        risk("notice", "replay", "HIGH");
        handoff("dash-risk-notice-" + suffix, "RISK", null, "dash-risk-notice-" + suffix, "replay", "PENDING_DELIVERY", notice, submitter);

        long punishPage = json.readTree(mvc.perform(get("/api/v1/handoffs?source_kind=UAV_EVENT&delivery_status=PENDING_DELIVERY&page=1&size=1")
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("total").asLong();
        JsonNode data = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(punishPage).isEqualTo(2);
        assertThat(data.path("kpis").path("pending_handoffs").asLong()).isEqualTo(punishPage);
        assertThat(data.path("closure").path("pending_handoffs").asLong()).isEqualTo(punishPage);
    }

    private String recipient(String type) {
        String id = "dash-rcpt-" + type.charAt(0) + "-" + suffix;
        jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values(?,?,?,true,?,?)",
                id, "大屏交接接收方", type, ts(now()), ts(now()));
        return id;
    }

    /** 无人机事件和它的处罚移送，最新一次投递是给定状态。 */
    private void punishment(String name, String sourceMode, String delivery, String recipient, String submitter) {
        String alarm = "dash-alarm-" + name + "-" + suffix, event = "dash-event-" + name + "-" + suffix;
        jdbc.update("insert into alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,?,'UAV_INTRUSION','HIGH',?,?,?,?,?,?)",
                alarm, SOURCE, alarm, ts(now()), ts(now()), sourceMode, org, district, ts(now()));
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,1)",
                event, alarm, org, district, ts(now()), ts(now()));
        handoff("dash-handoff-" + name + "-" + suffix, "UAV_EVENT", event, null, sourceMode, delivery, recipient, submitter);
    }

    private void handoff(String id, String kind, String event, String risk, String sourceMode, String delivery, String recipient, String submitter) {
        String type = "RISK".equals(kind) ? "RISK_NOTICE" : "UAV_PUNISHMENT";
        jdbc.update("insert into handoff(handoff_id,source_kind,source_id,event_id,risk_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values(?,?,?,?,?,?,?,1,?,?,?,?,?)",
                id, kind, event != null ? event : risk, event, risk, type, recipient, org, district, sourceMode, submitter, ts(now()));
        jdbc.update("insert into handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,created_at) values(?,?,1,?,'NOT_EXPECTED',?)",
                UUID.randomUUID().toString(), id, delivery, ts(now()));
    }

    /** 今天首次发现的目标，在读者的单位与区域里。 */
    private void todayTarget(String name, String sourceMode) {
        String id = "dash-today-" + name + "-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV',?,?,?,?,?,?,?,0)",
                id, "T-TODAY-" + name + "-" + suffix, sourceMode, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
    }

    private void risk(String name, String sourceMode, String severity) {
        String id = "dash-risk-" + name + "-" + suffix;
        jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values(?,?,?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE',?,'PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','大屏统计口径测试',?,?,?,?,?,?,0)",
                id, SOURCE, id, "dash-today-" + name + "-" + suffix, severity, ts(now()), sourceMode, org, district, ts(now()), ts(now()));
    }

    private void evaluation(String run, String targetId, String sourceMode, String grade) {
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,'FRESH','FULL','ILLEGAL',60,?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,? from rule_run r where r.run_id=?",
                "dash-eval-" + sourceMode + "-" + suffix, targetId, grade, org, district, sourceMode, ts(now()), run);
    }

    private void device(String name, String sourceMode, boolean simulated) {
        String id = "dash-device-" + name + "-" + suffix;
        jdbc.update("insert into ops_device (device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at)"
                + " values (?,?,?,'雷达','融合感知箱',true,?,?,0,0,0)", id, "D-" + name + "-" + suffix, "统计口径设备", sourceMode, simulated);
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at)"
                + " values (?,?,?,current_timestamp,current_timestamp)", id, org, district);
    }

    /** 设备上报过心跳：设备模拟器的设备要上报过才计入统计。 */
    private void reported(String name) {
        jdbc.update("insert into ops_device_state (device_id,connectivity,has_alarm,health_code,observed_at,received_at,last_heartbeat_at,simulated,version)"
                + " values (?,'ONLINE',false,'GOOD',?,?,?,true,0)", "dash-device-" + name + "-" + suffix, now(), now(), now());
    }

    /** 覆盖今日窗口的计划；航线必须与计划同一范围元组，否则读模型本就查不到。 */
    private void plan(String name, String sourceMode, String status) {
        String route = "dash-route-" + name + "-" + suffix;
        String version = "dash-rv-" + name + "-" + suffix;
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,true,?,?,?,?,?,0)", route, "R-" + name + "-" + suffix, "统计口径航线", sourceMode, org, district,
                ts(now()), ts(now()));
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " values (?,?,1,CAST(? AS GEOMETRY),100,10,300,'AGL',?,?)", version, route,
                "SRID=4326;LINESTRING (118.50 37.40,118.51 37.41)", ts(now()), ts(now()));
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,0)", "dash-plan-" + name + "-" + suffix, "P-" + name + "-" + suffix, status,
                sourceMode, ts(now() - 3_600_000), ts(now() + 3_600_000), version, org, district, ts(now()), ts(now()));
    }

    @Test
    void mapTargetsIncludeLocatedTargetsOutsideTodayWindow() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "target:read");
        // 这一条是正式接入但三天前的目标：地图上要有，今日统计里不能有。
        String stale = "dash-stale-" + suffix;
        long threeDaysAgo = now() - 3L * 24 * 60 * 60 * 1000;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','live',?,?,?,?,?,?,0)",
                stale, "T-S-" + suffix, org, district, ts(threeDaysAgo), ts(threeDaysAgo), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (118.61 37.41)',80,?,?,'[]' FORMAT JSON,?,?,0)
                """, stale, ts(threeDaysAgo), ts(threeDaysAgo), ts(now()), ts(now()));

        JsonNode data = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        // 今日统计里只有夹具那条演示样例（不计），三天前的那条在窗口外。
        assertThat(data.path("kpis").path("sensed_today").asInt()).isZero();
        assertThat(data.path("simulated_included").path("sensed_today").asInt()).isZero();
        assertThat(ids(data.get("map").get("targets"))).contains(target, stale);
    }

    private static Set<String> ids(JsonNode items) {
        Set<String> out = new LinkedHashSet<>();
        items.forEach(item -> {
            if (item.has("target_id")) out.add(item.get("target_id").asText());
            if (item.has("alarm_id")) out.add(item.get("alarm_id").asText());
        });
        return out;
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, orgId.toUpperCase(), orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, districtId.toUpperCase(), districtId);
    }

    private String reader(String scope, String orgId, String districtId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-DASH-" + id;
        String user = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "dash-" + id, "dash-tester", role, scope);
        if ("ASSIGNED".equals(scope)) {
            jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user, orgId, districtId);
        }
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void grantAction(String token, String... permissions) {
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',false,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", permission, token);
        }
    }

    private void grantModule(String token, String module) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',true,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", module, token);
    }

    private static Timestamp ts(long millis) {
        return Timestamp.from(Instant.ofEpochMilli(millis));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
