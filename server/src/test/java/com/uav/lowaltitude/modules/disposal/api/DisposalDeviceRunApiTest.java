package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.application.DisposalDeviceRunTimer;
import com.uav.lowaltitude.modules.disposal.application.DisposalReceiptSync;

/**
 * 反制设备打开后一直算反制中，到时自动全部关闭，设备回“已关闭”才记完成（2026-10-08 验收预跑 3-9 / 3-6，新-20）。
 *
 * 设备回执用受信夹具模拟：把 device_command 改成终态再走回执同步，和设备真回码后的路径相同。
 * 到时用把运行记录的关闭时刻挪到过去来模拟，定时任务由用例直接调 tick()。
 */
@SpringBootTest(properties = {"app.outbox.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:device_run;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisposalDeviceRunApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DisposalReceiptSync receipts;
    @Autowired DisposalDeviceRunTimer timer;
    String actor, operator, eventId, device;

    @BeforeEach
    void fixture() {
        actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        operator = user("disposal:read", "disposal:approve", "disposal:execute", "disposal:stop", "devices", "monitoring");
        eventId = event();
        device = fourChannel();
    }

    @Test
    void counterStaysOnUntilTheAutoAllOffThenCompletesTogetherWithItsJamming() throws Exception {
        String counter = directCounter();
        String start = commandOf(counter);
        assertThat(mask(start)).isEqualTo(15);
        deviceReplies(start, "SUCCEEDED");

        // 设备回“已打开”：仍是反制中，记下 60 秒（策略演示值）后关闭，并立即转干扰。
        assertThat(statusOf(counter)).isEqualTo("EXECUTING");
        Map<String, Object> run = run(counter);
        long onAt = millis(run.get("on_at")), dueAt = millis(run.get("off_due_at"));
        assertThat(dueAt - onAt).isEqualTo(60_000L);
        assertThat(notes(counter, "RECEIPT")).anyMatch(note -> note.contains("反制中") && note.contains("满 60 秒"));
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        assertThat(statusOf(jamming)).isEqualTo("EXECUTING");
        String driveAway = commandOf(jamming);
        assertThat(mask(driveAway)).isEqualTo(13);
        deviceReplies(driveAway, "SUCCEEDED");
        assertThat(statusOf(jamming)).isEqualTo("EXECUTING");
        assertThat(statusOf(counter)).isEqualTo("EXECUTING");
        assertThat(millis(run(jamming).get("off_due_at"))).as("转干扰和来源反制一起关").isEqualTo(dueAt);

        // 还没到时：不关。
        timer.tick();
        assertThat(allOffCommands()).isEmpty();

        // 到时：只给干扰那条下发一次全部关闭，授权跟着关闭指令走。
        due(counter); due(jamming);
        timer.tick();
        List<String> allOff = allOffCommands();
        assertThat(allOff).hasSize(1);
        assertThat(commandOf(jamming)).isEqualTo(allOff.get(0));
        assertThat(commandOf(counter)).isEqualTo(start);
        assertThat(notes(jamming, "DEVICE_ALL_OFF_ISSUED")).containsExactly("反制已满设定时长，系统自动下发全部关闭");
        assertThat(statusOf(jamming)).isEqualTo("EXECUTING");

        // 设备回“已全部关闭”：干扰和来源反制一起完成。
        deviceReplies(allOff.get(0), "SUCCEEDED");
        assertThat(statusOf(jamming)).isEqualTo("COMPLETED");
        assertThat(statusOf(counter)).isEqualTo("COMPLETED");
        assertThat(notes(jamming, "RECEIPT")).contains("设备回执：已全部关闭");
        assertThat(notes(counter, "RECEIPT")).anyMatch(note -> note.startsWith("设备回执：已全部关闭（随信号干扰"));
        timer.tick();
        assertThat(allOffCommands()).hasSize(1);
    }

    @Test
    void emergencyStopWorksWhileTheDeviceIsOnAndNoAutoAllOffFollows() throws Exception {
        String counter = directCounter();
        deviceReplies(commandOf(counter), "SUCCEEDED");
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        deviceReplies(commandOf(jamming), "SUCCEEDED");

        // 以前这里设备开着、授权却已完成，急停报“没有进行中的反制”。
        request("/api/v1/uav-events/" + eventId + "/emergency-stop", operator, key(), Map.of()).andExpect(status().isOk());
        assertThat(statusOf(counter)).isEqualTo("STOPPED");
        assertThat(statusOf(jamming)).isEqualTo("STOPPED");
        assertThat(allOffCommands()).hasSize(1);

        due(counter); due(jamming);
        timer.tick();
        assertThat(allOffCommands()).as("急停已停下的授权不再自动关闭").hasSize(1);
    }

    @Test
    void failedAutoAllOffIsRetriedThenLeftToTheOperatorWhileStillCountingAsOn() throws Exception {
        String counter = directCounter();
        deviceReplies(commandOf(counter), "SUCCEEDED");
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        // 转干扰没打开：来源反制自己到时关闭。
        deviceReplies(commandOf(jamming), "FAILED");
        assertThat(statusOf(jamming)).isEqualTo("FAILED");
        due(counter);

        timer.tick();
        String first = commandOf(counter);
        assertThat(mask(first)).isZero();
        deviceReplies(first, "FAILED");
        assertThat(statusOf(counter)).as("关闭没成功仍算反制中").isEqualTo("EXECUTING");

        timer.tick();
        String second = commandOf(counter);
        assertThat(second).isNotEqualTo(first);
        assertThat(notes(counter, "RECEIPT")).anyMatch(note -> note.startsWith("自动全部关闭没有成功") && note.contains("马上再试"));
        deviceReplies(second, "TIMED_OUT");
        timer.tick();
        String third = commandOf(counter);
        deviceReplies(third, "FAILED");
        timer.tick();
        assertThat(notes(counter, "RECEIPT")).anyMatch(note -> note.contains("试了 3 次都没成功") && note.contains("请按急停"));
        assertThat(jdbc.queryForObject("select gave_up_at from disposal_device_run where authorization_id=?", Timestamp.class, counter))
                .isNotNull();
        assertThat(statusOf(counter)).isEqualTo("EXECUTING");
        timer.tick();
        assertThat(allOffCommands()).hasSize(3);

        // 还开着就还能急停。
        request("/api/v1/uav-events/" + eventId + "/emergency-stop", operator, key(), Map.of()).andExpect(status().isOk());
        assertThat(statusOf(counter)).isEqualTo("STOPPED");
    }

    @Test
    void jammingNotSentBecauseTheDeviceWasBusyIsSentByALaterRoundWhileTheCounterIsStillOn() throws Exception {
        String counter = directCounter();
        // 设备回“已打开”时还有一条别的指令在途：干扰接上了，但没发出去。
        String other = inFlightCommand();
        deviceReplies(commandOf(counter), "SUCCEEDED");
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        assertThat(statusOf(jamming)).isEqualTo("APPROVED");
        assertThat(commandOf(jamming)).isNull();
        int busy = notes(jamming, "DEVICE_BUSY").size();

        // 那条指令还在途：这一轮安静地等，不再记一条“设备忙”。
        timer.tick();
        assertThat(statusOf(jamming)).isEqualTo("APPROVED");
        assertThat(notes(jamming, "DEVICE_BUSY")).hasSize(busy);

        // 设备空出来了：下一轮补发，和来源反制一起到时关闭。
        jdbc.update("update device_command set status='SUCCEEDED',completed_at=? where command_id=?", System.currentTimeMillis(), other);
        timer.tick();
        assertThat(statusOf(jamming)).isEqualTo("EXECUTING");
        assertThat(mask(commandOf(jamming))).isEqualTo(13);
        assertThat(notes(jamming, "EXECUTE")).containsExactly("反制设备打开后自动下发");
        deviceReplies(commandOf(jamming), "SUCCEEDED");
        assertThat(millis(run(jamming).get("off_due_at"))).isEqualTo(millis(run(counter).get("off_due_at")));
    }

    @Test
    void jammingNotChainedWhenTheDeviceOpenedIsChainedByALaterRoundButNotAfterTheCounterIsOff() throws Exception {
        String counter = directCounter();
        // 设备打开那一刻依据一时不满足（这里用事件暂回待核实来模拟）：没接上干扰。
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?", eventId);
        deviceReplies(commandOf(counter), "SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?",
                Integer.class, counter)).isZero();
        timer.tick();
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?",
                Integer.class, counter)).as("依据仍不满足时不接").isZero();

        jdbc.update("update uav_event set state_code='CONFIRMED' where event_id=?", eventId);
        timer.tick();
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        assertThat(statusOf(jamming)).isEqualTo("EXECUTING");
        assertThat(mask(commandOf(jamming))).isEqualTo(13);
    }

    @Test
    void counterAlreadyOffIsNeverChainedLater() throws Exception {
        String counter = directCounter();
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?", eventId);
        deviceReplies(commandOf(counter), "SUCCEEDED");
        due(counter);
        timer.tick();
        deviceReplies(commandOf(counter), "SUCCEEDED");
        assertThat(statusOf(counter)).isEqualTo("COMPLETED");

        jdbc.update("update uav_event set state_code='CONFIRMED' where event_id=?", eventId);
        timer.tick();
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?",
                Integer.class, counter)).isZero();
    }

    @Test
    void deviceStillOnForAnotherEventIsBusyForANewCounter() throws Exception {
        String counter = directCounter();
        deviceReplies(commandOf(counter), "SUCCEEDED");
        String jamming = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, counter);
        deviceReplies(commandOf(jamming), "SUCCEEDED");

        eventId = event();
        JsonNode second = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "同一台设备的第二起事件"))
                .andExpect(status().isCreated()));
        assertThat(second.path("status").asText()).isEqualTo("APPROVED");
        assertThat(second.path("execution_block_reason").asText()).isEqualTo("DEVICE_BUSY");
        assertThat(notes(second.path("authorization_id").asText(), "DEVICE_BUSY"))
                .anyMatch(note -> note.contains("正在执行另一条反制"));
    }

    private String directCounter() throws Exception {
        JsonNode result = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "反制设备运行时长回归"))
                .andExpect(status().isCreated()));
        assertThat(result.path("status").asText()).isEqualTo("EXECUTING");
        return result.path("authorization_id").asText();
    }

    /** 受信设备回执夹具：REST 调用方没有接口能伪造设备回码。 */
    private void deviceReplies(String command, String status) {
        jdbc.update("update device_command set status=?,completed_at=? where command_id=?", status, System.currentTimeMillis(), command);
        receipts.syncByCommand(command);
    }

    /** 占着设备的另一条在途指令（不属于任何处置授权）。 */
    private String inFlightCommand() {
        String id = key();
        long now = System.currentTimeMillis();
        jdbc.update("insert into device_command (command_id,command_no,device_id,command_type,reason,status,source_mode,simulated,"
                + "created_at,updated_at) values (?,?,?,'TEST_IN_FLIGHT','占着设备的另一条指令','QUEUED','live',true,?,?)",
                id, "BUSY-" + id.substring(0, 8), device, now, now);
        return id;
    }

    private void due(String authorization) {
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(120));
        jdbc.update("update disposal_device_run set on_at=?,off_due_at=? where authorization_id=?", past,
                Timestamp.from(past.toInstant().plusSeconds(60)), authorization);
    }

    private List<String> allOffCommands() {
        return jdbc.queryForList("select d.command_id from device_command d join countermeasure_4ch_command c on c.command_id=d.command_id"
                + " where d.device_id=? and c.mask=0 order by d.created_at", String.class, device);
    }

    /** H2 读 TIMESTAMP WITH TIME ZONE 给 OffsetDateTime，PG 驱动可能给 Timestamp。 */
    private static long millis(Object value) {
        if (value instanceof java.time.OffsetDateTime at) return at.toInstant().toEpochMilli();
        return ((Timestamp) value).getTime();
    }

    private Map<String, Object> run(String authorization) {
        return jdbc.queryForMap("select * from disposal_device_run where authorization_id=?", authorization);
    }

    private List<String> notes(String authorization, String kind) {
        return jdbc.queryForList("select note from disposal_authorization_event where authorization_id=? and event_kind=?"
                + " order by occurred_at,event_id", String.class, authorization, kind);
    }

    private Integer mask(String command) {
        return jdbc.queryForObject("select mask from countermeasure_4ch_command where command_id=?", Integer.class, command);
    }

    private String commandOf(String authorization) {
        return jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?",
                String.class, authorization);
    }

    private String statusOf(String authorization) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, authorization);
    }

    private ResultActions request(String path, String token, String key, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private String fourChannel() {
        long now = System.currentTimeMillis();
        String source = key(), id = key(), no = "RUN4-" + key().substring(0, 8);
        jdbc.update("insert into ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,source_mode,"
                + "enabled,allowed_cidrs,simulated,version,created_at,updated_at) values (?,?,?,?,'2.0','live',true,'127.0.0.1/32',true,0,?,?)",
                source, no, "运行时长四通道夹具", com.uav.lowaltitude.integration.device.DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, now, now);
        jdbc.update("insert into ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,"
                + "channel,enabled,source_mode,simulated,version,created_at,updated_at)"
                + " values (?,?,?,?,?,'countermeasure','反制','反制直连',true,'live',true,0,?,?)", id, source, no, no, "运行时长四通道夹具", now, now);
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,current_timestamp,current_timestamp)", id, ORG, DISTRICT);
        jdbc.update("insert into device_connection_profile (device_id,transport,host,port,timeout_millis,retry_count,version,updated_at)"
                + " values (?,'TCP','127.0.0.1',1,1000,0,0,?)", id, now);
        jdbc.update("insert into countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at)"
                + " values (?,1,'ASCII_HEX_SPACED',5000,0,?)", id, now);
        jdbc.update("insert into ops_device_state (device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version)"
                + " values (?,'ONLINE',false,'GOOD',?,?,true,0)", id, now, now);
        return id;
    }

    private String event() {
        String tag = key().substring(0, 8), alarm = "run-alarm-" + tag, event = "run-event-" + tag;
        Timestamp at = Timestamp.from(Instant.parse("2026-10-08T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'run-src','RUN-TEST','运行时长测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='run-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'run-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, tag, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, ORG, DISTRICT, at, at);
        CounterEvidenceFixture.seed(jdbc, event);
        return event;
    }

    private String user(String... permissions) {
        String id = key(), role = "RUN-" + key().substring(0, 8), token = key();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                id, "run-" + key().substring(0, 8), "运行时长测试操作员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", id, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, id, System.currentTimeMillis() + 3_600_000L);
        return token;
    }
}
