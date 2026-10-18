package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import com.uav.lowaltitude.platform.report.DeviceDisplayNo;

/**
 * 报表与业务页面同一套说法（问题清单 #18/#19）：设备运维用设备管理页的显示编号与中文类型，处理结果读处置单办结记录；
 * 事件处置的“当前状态”与告警列表同源（真正发出才叫“已移送处罚”，建立未发出叫“待发送移送”）。预览、明细与 Excel 同源。
 * 编号、状态都用随机或参数化数据，不依赖验收样例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportDisplayConsistencyApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry r) {
        // 与 BusinessReportingApiTest 同一显式开关：只连一次性 report_test_* 库，用来在 PostgreSQL 上核对 SQL。
        String url = System.getenv("REPORT_TEST_PG_URL");
        if (url != null) {
            if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/report_test_[a-z0-9_]+"))
                throw new IllegalArgumentException("Reporting tests require a disposable report_test_* database");
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            r.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
            r.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
            r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
        }
    }

    static String id() { return UUID.randomUUID().toString(); }
    static String today() { return LocalDate.now(ReportingService.ZONE).toString(); }

    String token() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }

    JsonNode details(String auth, String category, String section) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/stats/reports/details").header("Authorization", auth)
                .param("report_category", category).param("period_type", "DAILY").param("anchor_date", today())
                .param("section", section).param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    static Map<String, JsonNode> byId(JsonNode page) {
        Map<String, JsonNode> rows = new HashMap<>();
        page.path("items").forEach(row -> rows.put(row.path("id").asText(), row));
        return rows;
    }

    String device(String deviceNo, String typeCode, String typeName) {
        String device = id(), source = id();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,source_mode,created_at,updated_at) VALUES(?,?,'报表一致性来源','live',0,0)",
                source, "RC-" + source.substring(0, 8));
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,source_mode,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,'TCP','live',0,0)", device, source, "ext-" + device.substring(0, 8), deviceNo, "报表一致性设备" + typeCode, typeCode, typeName);
        return device;
    }

    String task(String device, String deviceNo, String workflow, String note, String assignee, String recovery) {
        String task = id();
        boolean handled = workflow.equals("COMPLETED") || workflow.equals("LEGACY_HANDLED");
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ops_device_maintenance_task(task_id,device_id,device_no,device_name,reason,simulated,status,active_key,trigger_type,"
                + "reported_by,reported_by_name,reported_at,handled_by,handled_at,handling_note,version,workflow_state,workflow_version,assigned_to_name,recovery_result)"
                + " VALUES(?,?,?,?,'心跳超时',FALSE,?,?,'SYSTEM',NULL,'系统',?,?,?,?,1,?,2,?,?)",
                task, device, deviceNo, "报表一致性设备", handled ? "HANDLED" : "PENDING", handled ? null : "rc-" + task,
                now - 1000, handled ? user : null, handled ? now : null, note, workflow, assignee, recovery);
        return task;
    }

    @Test void maintenanceReportUsesDeviceManagementNumbersChineseTypesAndCompletionRecords() throws Exception {
        String auth = "Bearer " + token();
        // 当前场景：模拟器光电设备；邻近场景：换类型（雷达）、换编号（真实编号不变形）、换办理阶段。
        String eoNo = "map-sim-eo-" + id().substring(0, 13), radarNo = "map-sim-radar-" + id().substring(0, 4), realNo = "DEV-" + id().substring(0, 6);
        String eo = device(eoNo, "EO", "光电"), radar = device(radarNo, "radar", "雷达"), real = device(realNo, "countermeasure", "");
        String completed = task(eo, eoNo, "COMPLETED", "确认设备恢复并办结", "张运维", "PASS");
        String pending = task(radar, radarNo, "PENDING", null, null, "UNKNOWN");
        String processing = task(real, realNo, "PROCESSING", null, "李运维", "FAIL");
        String legacyEmpty = task(eo, eoNo, "LEGACY_HANDLED", null, null, null);
        String legacyNote = task(radar, radarNo, "LEGACY_HANDLED", "更换电源", null, null);

        Map<String, JsonNode> rows = byId(details(auth, "DEVICE_OPERATIONS", "maintenance"));
        assertThat(rows.get(completed).path("result").asText()).isEqualTo("已办结：确认设备恢复并办结（设备已恢复）");
        assertThat(rows.get(pending).path("result").asText()).isEqualTo("尚未办结");
        assertThat(rows.get(processing).path("result").asText()).isEqualTo("处理中（李运维）");
        assertThat(rows.get(legacyEmpty).path("result").asText()).as("旧数据没有记录").isEqualTo("无处理记录");
        assertThat(rows.get(legacyNote).path("result").asText()).isEqualTo("历史反馈：更换电源");
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.path("result").asText()).isNotEqualTo("未知"));

        Map<String, JsonNode> devices = byId(details(auth, "DEVICE_OPERATIONS", "devices"));
        assertThat(devices.get(eo).path("kind").asText()).isEqualTo("光电");
        assertThat(devices.get(radar).path("kind").asText()).isEqualTo("雷达");
        assertThat(devices.get(real).path("kind").asText()).as("台账没有中文名时用类型代码，由标签兜底").isEqualTo("countermeasure");

        byte[] xlsx = mvc.perform(get("/api/v1/stats/reports/export.xlsx").header("Authorization", auth)
                .param("report_category", "DEVICE_OPERATIONS").param("period_type", "DAILY").param("anchor_date", today()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        StringBuilder text = new StringBuilder();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var formatter = new DataFormatter();
            book.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> text.append(formatter.formatCellValue(cell)).append('|'))));
        }
        assertThat(text).contains(DeviceDisplayNo.format(eoNo)).contains(DeviceDisplayNo.format(radarNo)).contains(realNo)
                .doesNotContain(eoNo + "|").contains("已办结：确认设备恢复并办结（设备已恢复）").contains("无处理记录")
                .contains("反制设备").doesNotContain("|countermeasure|").doesNotContain("PROCESSING").contains("|处理中|").contains("|已办结|");
        assertThat(DeviceDisplayNo.format(eoNo)).startsWith("EO-").hasSize(8);
    }

    @Test void eventReportStateMatchesTheAlarmListProgress() throws Exception {
        String auth = "Bearer " + token();
        String org = id(), district = id(), recipient = id();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,'报表一致性机构',TRUE,0,0,0)", org, org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,'报表一致性区域',TRUE,0,0,0)", district, district);
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'报表一致性处罚部门','UAV_PUNISHMENT',true,?,?)",
                recipient, Timestamp.from(now), Timestamp.from(now));
        String pendingDelivery = event(org, district, "CONFIRMED", recipient, "PENDING_DELIVERY", now);
        String delivered = event(org, district, "CONFIRMED", recipient, "DELIVERED", now);
        String submitted = event(org, district, "CONFIRMED", recipient, "SUBMITTED", now);
        String falsePositive = event(org, district, "FALSE_POSITIVE", null, null, now);

        Map<String, JsonNode> rows = byId(details(auth, "EVENT_DISPOSAL", "events"));
        assertThat(rows.get(pendingDelivery).path("state").asText()).isEqualTo("待发送移送");
        assertThat(rows.get(delivered).path("state").asText()).isEqualTo("已移送处罚");
        assertThat(rows.get(submitted).path("state").asText()).isEqualTo("已移送处罚");
        assertThat(rows.get(falsePositive).path("state").asText()).isEqualTo("FALSE_POSITIVE");

        JsonNode preview = json.readTree(mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization", auth)
                .param("report_category", "EVENT_DISPOSAL").param("period_type", "DAILY").param("anchor_date", today()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode events = null;
        for (JsonNode section : preview.path("sections")) if ("events".equals(section.path("key").asText())) events = section;
        assertThat(events).isNotNull();
        Map<String, Long> states = new HashMap<>();
        for (JsonNode distribution : events.path("distributions"))
            if ("state".equals(distribution.path("key").asText()))
                distribution.path("items").forEach(item -> states.put(item.path("name").asText(), item.path("value").asLong()));
        assertThat(states.getOrDefault("已移送处罚", 0L)).isGreaterThanOrEqualTo(2);
        assertThat(states.getOrDefault("待发送移送", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(events.path("total").asLong()).isEqualTo(states.values().stream().mapToLong(Long::longValue).sum());
        assertThat(details(auth, "EVENT_DISPOSAL", "events").path("total").asLong()).isEqualTo(events.path("total").asLong());
    }

    String event(String org, String district, String state, String recipient, String delivery, Instant now) {
        String target = id(), source = id(), alarm = id(), event = id(), number = "RC-" + id().substring(0, 8);
        Timestamp at = Timestamp.from(now.minusSeconds(30));
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,'live',?,?,0)", source, "S-" + source.substring(0, 8), "报表一致性来源", at, at);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'UAV','live',?,?,?,?)", target, "T-" + target, org, district, at, at);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,?,?,?,?,'RULE_LEGALITY','HIGH',?,?,'live',?,?,?)", alarm, target, source, number, number, at, at, org, district, at);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,0)", event, alarm, state, org, district, at, at);
        if (delivery != null) {
            String handoff = id();
            String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
            jdbc.update("INSERT INTO handoff(handoff_id,source_kind,source_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) VALUES(?,'UAV_EVENT',?,?,'UAV_PUNISHMENT',?,1,?,?,'live',?,?)",
                    handoff, event, event, recipient, org, district, user, Timestamp.from(now));
            jdbc.update("INSERT INTO handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) VALUES(?,?,1,?,'NOT_EXPECTED',NULL,?)",
                    id(), handoff, delivery, Timestamp.from(now));
        }
        return event;
    }
}
