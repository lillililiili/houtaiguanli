package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 13 处置授权固定夹具：一条已批准（LINGYUN_B）、一条已完成（MANUAL）、一条已驳回。
 *
 * 双门禁（!production & (local|test) + app.dev-seed.enabled）：生产既不注册这个 Runner，也不会插入任何授权。
 * 全部写入都是 WHERE NOT EXISTS：重跑幂等，不覆盖任何人工发起或审批过的授权。
 *
 * 已完成那条挂在自己的已核实事件上，处罚交接的前提（决策 13-6）因此在本地有一个可演示的成立案例；
 * 事件与告警都是本种子自建的，不去改阶段 4 的夹具——把别人的待核实事件改成已核实，会让他们的用例莫名其妙地变绿或变红。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn({"localStage4AlarmSeeder"})
@Order(100)
public class LocalStage13DisposalSeeder implements ApplicationRunner {
    static final String ORG = "seed-stage4-alarm-org";
    static final String DISTRICT = "seed-stage4-alarm-district";
    static final String SOURCE = "seed-stage13-disposal-source";
    static final String ALARM = "seed-stage13-alarm-confirmed";
    static final String EVENT = "seed-stage13-event-confirmed";
    static final String AUTH_APPROVED = "seed-stage13-auth-approved";
    static final String AUTH_COMPLETED = "seed-stage13-auth-completed";
    static final String AUTH_REJECTED = "seed-stage13-auth-rejected";

    private final JdbcTemplate jdbc;

    public LocalStage13DisposalSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant at = Instant.parse("2026-09-07T02:00:00Z");
        String actor = adminUserId();
        if (actor == null) return;   // 没有种子管理员就不造授权：授权必须挂在真实的申请人与审批人上。
        alarmAndConfirmedEvent(at);
        // 已批准：演示"可执行/可停止"的形态，有效期从批准起 30 分钟（策略 demo-v1）。
        // 库里没有可用设备时降为人工通道：ck_stage13_authorization_device 要求非 MANUAL 必须带 device_id，
        // 留 null 会让种子在"设备种子关掉、或只装了阶段 13 迁移"的库上启动即炸——
        // 而种子一炸整个 Spring 上下文都起不来，表现是所有用例全红，排查方向会被带偏。
        String device = deviceId();
        String approvedChannel = device == null ? "MANUAL" : "LINGYUN_B";
        authorization(AUTH_APPROVED, "AUTH-20260907-9001", "COUNTERMEASURE", approvedChannel, "APPROVED", actor, at,
                at.plus(30, ChronoUnit.MINUTES), null, null, device);
        event(AUTH_APPROVED, "REQUEST", actor, at);
        event(AUTH_APPROVED, "APPROVE", actor, at,
                device == null ? "本地演示夹具：库中没有可用设备，本条降为人工通道" : "本地演示夹具");
        // 已完成：人工执行并登记结果，处罚交接的前提据此成立。
        authorization(AUTH_COMPLETED, "AUTH-20260907-9002", "JAMMING", "MANUAL", "COMPLETED", actor, at,
                at.plus(30, ChronoUnit.MINUTES), "MANUAL_SUCCEEDED", "本地演示：人工干扰已完成", null);
        event(AUTH_COMPLETED, "REQUEST", actor, at);
        event(AUTH_COMPLETED, "APPROVE", actor, at);
        event(AUTH_COMPLETED, "EXECUTE", actor, at);
        event(AUTH_COMPLETED, "MANUAL_RESULT", actor, at);
        // 已驳回：演示"批不下来"的形态；没有有效期，也没有执行痕迹。
        authorization(AUTH_REJECTED, "AUTH-20260907-9003", "DECOY", "MANUAL", "REJECTED", actor, at, null, null, null, null);
        event(AUTH_REJECTED, "REQUEST", actor, at);
        event(AUTH_REJECTED, "REJECT", actor, at);
    }

    private String adminUserId() {
        return jdbc.query("SELECT user_id FROM app_user WHERE account='admin1'",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    private void alarmAndConfirmedEvent(Instant at) {
        // 自建来源而不是借用阶段 4 的常量：那个常量是私有的，靠字面量去蹭等于建一条看不见的依赖，
        // 别人改了名字这里会在运行期才炸。
        jdbc.update("INSERT INTO integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " SELECT ?,'STAGE13-DISPOSAL-MOCK','处置授权演示源',TRUE,'mock',?,?,0"
                + " WHERE NOT EXISTS (SELECT 1 FROM integration_source WHERE source_id=?)",
                SOURCE, ts(at), ts(at), SOURCE);
        jdbc.update("UPDATE integration_source SET name='处置授权演示源' WHERE source_id=? AND name<>'处置授权演示源'", SOURCE);
        jdbc.update("INSERT INTO alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " SELECT ?,NULL,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM alarm WHERE alarm_id=?)",
                ALARM, SOURCE, "告警-0907-013", ts(at), ts(at), ORG, DISTRICT, ts(at), ALARM);
        jdbc.update("INSERT INTO uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " SELECT ?,?,'CONFIRMED',?,?,?,?,1 WHERE NOT EXISTS (SELECT 1 FROM uav_event WHERE event_id=?)",
                EVENT, ALARM, ORG, DISTRICT, ts(at), ts(at), EVENT);
    }

    private void authorization(String id, String no, String actionType, String channel, String status, String actor,
                               Instant at, Instant validUntil, String resultCode, String resultDetail, String device) {
        boolean approved = validUntil != null;
        jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,"
                + "subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,"
                + "decision_note,valid_from,valid_until,status,execution_command_id,result_code,result_detail,"
                + "policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " SELECT ?,?,?,'UAV_EVENT',?,NULL,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,'demo-v1',?,?,'mock',1,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=?)",
                id, no, actionType, EVENT,
                "MANUAL".equals(channel) ? null : device, channel, "本地演示：阶段 13 处置授权夹具",
                actor, ts(at), approved ? actor : null, approved ? ts(at) : null,
                approved ? "本地演示：已批准" : "本地演示：不予批准",
                approved ? ts(at) : null, approved ? ts(validUntil) : null, status,
                resultCode, resultDetail, ORG, DISTRICT, ts(at), ts(at), id);
    }

    /** 取任意一台已启用设备演示 LINGYUN_B 形态；没有设备时留空，靠 CHECK 之外的业务校验兜底。 */
    private String deviceId() {
        return jdbc.query("SELECT device_id FROM ops_device WHERE enabled=TRUE ORDER BY device_id ASC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    private void event(String authorizationId, String kind, String actor, Instant at) {
        event(authorizationId, kind, actor, at, "本地演示夹具");
    }

    private void event(String authorizationId, String kind, String actor, Instant at, String note) {
        // id 必须留在 VARCHAR(36) 里：拼得太长会在种子阶段炸，而种子一炸整个 Spring 上下文都起不来，
        // 表现出来是"所有测试全红"，排查方向会被带偏。
        String eventId = "s13e-" + authorizationId.substring(authorizationId.lastIndexOf('-') + 1)
                + "-" + kind.toLowerCase();
        jdbc.update("INSERT INTO disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,"
                + "snapshot,occurred_at) SELECT ?,?,?,?,?,NULL,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM disposal_authorization_event WHERE event_id=?)",
                eventId, authorizationId, kind, actor, note, ts(at), eventId);
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
