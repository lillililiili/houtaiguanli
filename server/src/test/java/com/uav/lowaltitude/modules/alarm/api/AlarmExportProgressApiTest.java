package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.domain.NoCounterRules;
import com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 告警导出分“核实状态”“处置进度”两列（2026-10-08 新-2 第 5 点）。处置进度和告警页“状态”列同一套先后和写法：
 * 不反制的决定、已移送处罚、反制或干扰到了哪一步、通知到了哪一步；页面上只显示核实结论的留空。
 * 反制进度和页面一样只写给有处置查看权限的人。H2 与隔离 PostgreSQL 同跑，不写验收数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class AlarmExportProgressApiTest {
    /** 告警页 NOTIFY_PHASE 的写法（AlarmsPage.vue）；导出必须一字不差。 */
    static final Map<String, String> PAGE_PHASE = Map.of("AUTO_SMS", "自动短信", "WATCHING", "观察中",
            "AUTO_CALL", "自动电话", "AWAIT_COUNTER", "待定是否反制");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @SpyBean AppClock clock;
    String org, district, actor, token, reader, readerToken, recipient, policy;
    Instant now;

    @BeforeEach void fixture() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        doReturn(now).when(clock).now(); doReturn(now.toEpochMilli()).when(clock).nowMillis();
        org = id(); district = id(); recipient = id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, "导出进度机构");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", district, district, "导出进度区域");
        actor = user("alarm:read", "disposal:read"); token = session(actor);
        reader = user("alarm:read"); readerToken = session(reader);
        policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code FETCH FIRST 1 ROW ONLY", String.class);
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'导出进度处罚部门','UAV_PUNISHMENT',true,?,?)", recipient, ts(now), ts(now));
    }

    @Test void exportSplitsVerificationStateAndDisposalProgressLikeTheAlarmPage() throws Exception {
        Alarm waiting = alarm("CONFIRMED", 1);
        Alarm executing = alarm("CONFIRMED", 2);
        authorization(executing, "COUNTERMEASURE", "EXECUTING", 30);
        Alarm jammed = alarm("CONFIRMED", 3);
        authorization(jammed, "JAMMING", "COMPLETED", 30);
        Alarm requested = alarm("CONFIRMED", 4);
        authorization(requested, "COUNTERMEASURE", "REQUESTED", 30);
        Alarm stopped = alarm("CONFIRMED", 5);
        authorization(stopped, "COUNTERMEASURE", "COMPLETED", 60);
        authorization(stopped, "JAMMING", "STOPPED", 10);
        Alarm failedLater = alarm("CONFIRMED", 6);
        authorization(failedLater, "COUNTERMEASURE", "COMPLETED", 60);
        authorization(failedLater, "JAMMING", "FAILED", 10);
        Alarm handed = alarm("CONFIRMED", 7);
        authorization(handed, "JAMMING", "COMPLETED", 60);
        handoff(handed, "PENDING_DELIVERY");
        Alarm handoffFailed = alarm("CONFIRMED", 8);
        authorization(handoffFailed, "JAMMING", "COMPLETED", 60);
        handoff(handoffFailed, "FAILED");
        Alarm verifyingHanded = alarm("PENDING_VERIFICATION", 9);
        handoff(verifyingHanded, "DELIVERED");
        Alarm verifying = alarm("PENDING_VERIFICATION", 10);
        Alarm falsePositive = alarm("FALSE_POSITIVE", 11);
        Alarm decided = alarm("CONFIRMED", 12);
        authorization(decided, "COUNTERMEASURE", "COMPLETED", 60);
        noCounter(decided, decided.target);
        Alarm changed = alarm("CONFIRMED", 13);
        noCounter(changed, id());
        Alarm withoutEvent = alarm(null, 14);

        List<String> lines = csv(token);
        assertThat(lines.get(0).replace("\uFEFF", "")).startsWith("编号,告警类别,违规原因,等级,核实状态,处置进度,发生时间");
        Map<String, String[]> rows = rows(lines);
        // 核实状态内容不变：仍是核实结论。
        assertThat(rows.get(waiting.number)[4]).isEqualTo("告警已确认");
        assertThat(rows.get(handed.number)[4]).isEqualTo("告警已确认");
        assertThat(rows.get(verifying.number)[4]).isEqualTo("待核实");
        assertThat(rows.get(falsePositive.number)[4]).isEqualTo("误报");
        // 还没有反制、移送或不反制决定的，和页面一样写通知阶段（与本事件的处置建议同一个判断）。
        // 本用例的事件没有关联任务和飞手，短信发不出去，页面写“待定是否反制”。
        assertThat(rows.get(waiting.number)[5]).isEqualTo(pagePhase(waiting, token)).isEqualTo("待定是否反制");
        assertThat(rows.get(executing.number)[5]).isEqualTo("反制中");
        assertThat(rows.get(jammed.number)[5]).isEqualTo("已干扰");
        assertThat(rows.get(requested.number)[5]).isEqualTo("待审批");
        assertThat(rows.get(stopped.number)[5]).as("取最近申请的一条").isEqualTo("反制已中止");
        assertThat(rows.get(failedLater.number)[5]).as("失败的不算").isEqualTo("已反制");
        assertThat(rows.get(handed.number)[5]).as("移送在反制之前看").isEqualTo("已移送处罚");
        assertThat(rows.get(handoffFailed.number)[5]).as("移送发送失败不算已移送").isEqualTo("已干扰");
        assertThat(rows.get(verifyingHanded.number)[5]).isEqualTo("已移送处罚");
        assertThat(rows.get(verifying.number)[5]).isEmpty();
        assertThat(rows.get(falsePositive.number)[5]).isEmpty();
        assertThat(rows.get(decided.number)[5]).as("不反制的决定最先看").isEqualTo("不反制 · 处置已结束");
        assertThat(rows.get(changed.number)[5]).isEqualTo("风险变化待决策");
        assertThat(rows.get(withoutEvent.number)[5]).isEmpty();
        // 时间列跟着后移一列，仍是发生时间。
        assertThat(rows.get(waiting.number)[6]).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test void counterProgressIsOnlyWrittenForPeopleWhoMaySeeDisposalsLikeThePage() throws Exception {
        Alarm executing = alarm("CONFIRMED", 1);
        authorization(executing, "COUNTERMEASURE", "EXECUTING", 30);
        Alarm handed = alarm("CONFIRMED", 2);
        authorization(handed, "JAMMING", "COMPLETED", 60);
        handoff(handed, "SUBMITTED");
        Map<String, String[]> rows = rows(csv(readerToken));
        assertThat(rows.get(executing.number)[5]).isEqualTo(pagePhase(executing, readerToken)).isEqualTo("待定是否反制");
        assertThat(rows.get(handed.number)[5]).isEqualTo("已移送处罚");
        assertThat(rows(csv(token)).get(executing.number)[5]).isEqualTo("反制中");
    }

    Alarm alarm(String state, long ageSeconds) {
        String target = id(), source = id(), alarm = id(), event = state == null ? null : id(), number = "EP-" + id().substring(0, 8);
        Timestamp received = ts(now.minusSeconds(ageSeconds));
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,'replay',?,?,0)", source, "S-" + source.substring(0, 8), "观测来源", received, received);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'UAV','replay',?,?,?,?)", target, "T-" + target, org, district, received, received);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,?,?,?,?,'RULE_LEGALITY','HIGH',?,?,'replay',?,?,?)", alarm, target, source, number, number, received, received, org, district, received);
        if (event != null) jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,0)", event, alarm, state, org, district, received, received);
        return new Alarm(alarm, target, event, number);
    }

    void authorization(Alarm row, String action, String state, long ageSeconds) {
        String id = id();
        boolean approved = !"REQUESTED".equals(state);
        Instant at = now.minusSeconds(ageSeconds);
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,created_at,updated_at) VALUES (?,?,?,'UAV_EVENT',?,?,?,'COUNTERMEASURE_4CH','测试',?,?,?,?,?,?,?,?,?,?,'replay',?,?)",
                id, "AUTH-" + id.substring(0, 8), action, row.event, row.target, id(), actor, ts(at), approved ? actor : null, approved ? ts(at) : null,
                approved ? ts(at) : null, approved ? ts(at.plusSeconds(600)) : null, state, policy, org, district, ts(at), ts(at));
    }

    void handoff(Alarm row, String delivery) {
        String id = id();
        jdbc.update("INSERT INTO handoff(handoff_id,source_kind,source_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) VALUES(?,'UAV_EVENT',?,?,'UAV_PUNISHMENT',?,1,?,?,'replay',?,?)", id, row.event, row.event, recipient, org, district, actor, ts(now));
        jdbc.update("INSERT INTO handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) VALUES(?,?,1,?,'NOT_EXPECTED',NULL,?)", id(), id, delivery, ts(now));
    }

    /** 不反制决定；目标与事件不一致时按“风险变化，要重新决策”处理。 */
    void noCounter(Alarm row, String decidedTarget) throws Exception {
        var basis = new NoCounterRules.Basis(id(), now.toEpochMilli(), now.toEpochMilli(), "LEGAL", "LOW", List.of());
        jdbc.update("INSERT INTO uav_no_counter_decision(decision_id,event_id,event_version,actor_id,actor_name,decided_at,reason,evaluation_id,target_id,owner_org_id,district_id,source_mode,basis_text) VALUES (?,?,0,?,'导出进度测试',?,'当前无风险',?,?,?,?,'replay',?)",
                id(), row.event, actor, now.toEpochMilli(), basis.evaluationId(), decidedTarget, org, district,
                json.writeValueAsString(new NoCounterRepository.FrozenBasis(basis, now.toEpochMilli(), List.of(basis.evaluationId()))));
    }

    String user(String... permissions) {
        String user = id(), role = "ROLE-EP-" + id().substring(0, 8);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        for (String permission : permissions)
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,?,'READ',FALSE,current_timestamp)", role, permission);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", user, "ep-" + id().substring(0, 8), "导出进度读者", role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)", user, org, district);
        return user;
    }

    String session(String user) {
        String session = id();
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES (?,?,?,'127.0.0.1',0)", session, user, now.plusSeconds(3600).toEpochMilli());
        return session;
    }

    /** 告警页对这条事件会写的通知阶段：读同一份处置建议，按页面的字翻；没有阶段就是空。 */
    String pagePhase(Alarm row, String bearer) throws Exception {
        String body = mvc.perform(get("/api/v1/uav-events/" + row.event + "/advisory").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String phase = json.readTree(body).path("data").path("notify_phase").asText("");
        return PAGE_PHASE.getOrDefault(phase, "");
    }

    List<String> csv(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
    }

    static Map<String, String[]> rows(List<String> lines) {
        Map<String, String[]> rows = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            rows.put(cells[0], cells);
        }
        return rows;
    }

    static String id() { return UUID.randomUUID().toString(); }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    record Alarm(String alarm, String target, String event, String number) { }
}
