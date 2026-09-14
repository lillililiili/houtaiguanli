package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;

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
 * 告警/无人机事件的演示"体量"夹具：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 * 目的：让告警页与核实弹窗在每个事件状态（待核实/已确认/误报）、每个等级（CRITICAL/HIGH/MEDIUM/LOW）
 * 和两种告警类型（UAV_INTRUSION/UAV）下都有记录可看，且发生时间分散在多天，方便排序与筛选。
 * 只复用既有夹具的机构、区域、来源与目标（阶段四共享目标、阶段七契约目标、阶段七体量目标），不新建机构。
 * 已核实的事件按服务路径的写法补核实历史（history.version = 核实后的 event.version），核实人是本地 admin1。
 * 全部写入都是 WHERE NOT EXISTS：重跑幂等，不 UPDATE 任何已存在的行，不覆盖人工核实结果。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
// 必须排在 RuleReplayRunner(70) / RuleDemoVolumeReplayRunner(71) 之后：回放回归断言 merge 目标"恰一条告警"，
// 演示告警若先于回归落库，全新库首启会被断言拦下（协作者 B 2026-09-09 报告，决策 15-60）。
@Order(72)
@DependsOn({"localUserSeeder", "localStage4AlarmSeeder", "localStage7RuleEngineSeeder", "localStage7DemoVolumeSeeder"})
public class LocalDemoVolumeAlarmSeeder implements ApplicationRunner {
    /** 阶段四告警夹具的机构/区域/共享目标：常量在 LocalStage4AlarmSeeder 内是字面量，这里照抄（与阶段十三种子同一做法）。 */
    private static final String S4_ORG = "seed-stage4-alarm-org", S4_DISTRICT = "seed-stage4-alarm-district";
    private static final String S4_SHARED_TARGET = "seed-stage4-target-shared";
    private static final String S7_ORG = LocalStage7RuleEngineSeeder.ORG, S7_DISTRICT = LocalStage7RuleEngineSeeder.DISTRICT;
    /** 全部告警挂在阶段七来源上：该来源没有其他告警，(source_id, source_alarm_id) 唯一键不会与规则引擎产生的告警相撞。 */
    private static final String SOURCE_ID = LocalStage7RuleEngineSeeder.SOURCE_ID;

    private static final String PENDING = "PENDING_VERIFICATION", CONFIRMED = "CONFIRMED", FALSE_POSITIVE = "FALSE_POSITIVE";

    private final JdbcTemplate jdbc;

    public LocalDemoVolumeAlarmSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String alarmId(String suffix) { return "seed-vol-alarm-" + suffix; }
    static String eventId(String suffix) { return "seed-vol-event-" + suffix; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String actor = adminUserId();
        if (actor == null) throw new IllegalStateException("demo volume alarm seed requires the local admin1 user (LocalUserSeeder)");

        // 发生时刻按天铺开（UTC；本地演示看到的是 Asia/Shanghai +8h），同一天内再错开小时，排序有区分度。
        // 阶段七目标 → 阶段七机构/区域；无目标或阶段四共享目标 → 阶段四机构/区域（接收链会把告警落在目标所属范围）。
        // 01：已确认 / CRITICAL / 入侵 / 闯入限高空域 H1
        alarm("01", "告警-0901-301", "UAV_INTRUSION", "CRITICAL", LocalStage7RuleEngineSeeder.targetId("airspace-limit"), S7_ORG, S7_DISTRICT, at("2026-09-01T00:10:00Z"));
        confirmed("01", S7_ORG, S7_DISTRICT, at("2026-09-01T00:10:00Z"), actor, at("2026-09-01T01:30:00Z"), "现场核实：目标在限高空域 H1 内以 120 m 飞行，超过限高 60 m，确认属实");
        // 02：已确认 / HIGH / 入侵 / 穿越禁飞区 P1
        alarm("02", "告警-0902-302", "UAV_INTRUSION", "HIGH", LocalStage7DemoVolumeSeeder.targetId("vol-prohibited-1"), S7_ORG, S7_DISTRICT, at("2026-09-02T02:20:00Z"));
        confirmed("02", S7_ORG, S7_DISTRICT, at("2026-09-02T02:20:00Z"), actor, at("2026-09-02T06:45:00Z"),
                "目标穿越禁飞空域 P1，属实");
        // 03：误报 / MEDIUM / 无人机告警 / 偏航
        alarm("03", "告警-0903-303", "UAV", "MEDIUM", LocalStage7DemoVolumeSeeder.targetId("vol-deviation-1"), S7_ORG, S7_DISTRICT, at("2026-09-03T04:05:00Z"));
        falsePositive("03", S7_ORG, S7_DISTRICT, at("2026-09-03T04:05:00Z"), actor, at("2026-09-03T05:10:00Z"), "核对计划后判定：偏离量在走廊容差内，属误报");
        // 04：误报 / LOW / 无人机告警 / 已报备合法飞行
        alarm("04", "告警-0904-304", "UAV", "LOW", LocalStage7DemoVolumeSeeder.targetId("vol-legal-1"), S7_ORG, S7_DISTRICT, at("2026-09-04T01:15:00Z"));
        falsePositive("04", S7_ORG, S7_DISTRICT, at("2026-09-04T01:15:00Z"), actor, at("2026-09-04T01:40:00Z"), "该 SN 有已批准计划 JH-S7-011，且在计划窗与走廊内，属误报");
        // 05：待核实 / HIGH / 无人机告警 / 无计划飞行
        alarm("05", "告警-0905-305", "UAV", "HIGH", LocalStage7DemoVolumeSeeder.targetId("vol-no-plan-1"), S7_ORG, S7_DISTRICT, at("2026-09-05T03:30:00Z"));
        pending("05", S7_ORG, S7_DISTRICT, at("2026-09-05T03:30:00Z"));
        // 06：待核实 / CRITICAL / 无人机告警 / 临时管制 T1 生效期间飞行
        alarm("06", "告警-0906-306", "UAV", "CRITICAL", LocalStage7RuleEngineSeeder.targetId("boundary"), S7_ORG, S7_DISTRICT, at("2026-09-06T02:00:00Z"));
        pending("06", S7_ORG, S7_DISTRICT, at("2026-09-06T02:00:00Z"));
        // 07：待核实 / MEDIUM / 入侵 / 无关联目标（来源只给了告警，未关联到目标）
        alarm("07", "告警-0907-307", "UAV_INTRUSION", "MEDIUM", null, S4_ORG, S4_DISTRICT, at("2026-09-07T00:45:00Z"));
        pending("07", S4_ORG, S4_DISTRICT, at("2026-09-07T00:45:00Z"));
        // 08：已确认 / LOW / 入侵 / 阶段四共享目标
        alarm("08", "告警-0907-308", "UAV_INTRUSION", "LOW", S4_SHARED_TARGET, S4_ORG, S4_DISTRICT, at("2026-09-07T05:20:00Z"));
        confirmed("08", S4_ORG, S4_DISTRICT, at("2026-09-07T05:20:00Z"), actor, at("2026-09-07T06:00:00Z"), "巡查人员现场确认为同一目标的再次出现，属实");
        // 09：待核实 / HIGH / 无人机告警 / 夜航
        alarm("09", "告警-0908-309", "UAV", "HIGH", LocalStage7DemoVolumeSeeder.targetId("vol-night-1"), S7_ORG, S7_DISTRICT, at("2026-09-08T13:05:00Z"));
        pending("09", S7_ORG, S7_DISTRICT, at("2026-09-08T13:05:00Z"));
        // 10：误报 / CRITICAL / 入侵 / 来源误判
        alarm("10", "告警-0908-310", "UAV_INTRUSION", "CRITICAL", LocalStage7DemoVolumeSeeder.targetId("vol-no-plan-2"), S7_ORG, S7_DISTRICT, at("2026-09-08T01:00:00Z"));
        falsePositive("10", S7_ORG, S7_DISTRICT, at("2026-09-08T01:00:00Z"), actor, at("2026-09-08T02:15:00Z"), "现场核实为鸟群，来源分类置信度 0.85 误判，属误报");
        // 11：待核实 / LOW / 无人机告警 / 来源置信度低
        alarm("11", "告警-0908-311", "UAV", "LOW", LocalStage7RuleEngineSeeder.targetId("degraded"), S7_ORG, S7_DISTRICT, at("2026-09-08T07:40:00Z"));
        pending("11", S7_ORG, S7_DISTRICT, at("2026-09-08T07:40:00Z"));
        // 12：已确认 / MEDIUM / 入侵 / 超计划高度
        alarm("12", "告警-0906-312", "UAV_INTRUSION", "MEDIUM", LocalStage7RuleEngineSeeder.targetId("plan-altitude"), S7_ORG, S7_DISTRICT, at("2026-09-06T08:25:00Z"));
        confirmed("12", S7_ORG, S7_DISTRICT, at("2026-09-06T08:25:00Z"), actor, at("2026-09-06T09:00:00Z"), "实测高度 130 m 超过计划上限 100 m，确认属实");
    }

    private String adminUserId() {
        return jdbc.query("SELECT user_id FROM app_user WHERE account='admin1'", rs -> rs.next() ? rs.getString(1) : null);
    }

    /** 告警是来源事实：received_at 比 occurred_at 晚几秒，created_at 取接收时刻；不写 detail/inbox（来源只给了摘要）。 */
    private void alarm(String suffix, String sourceAlarmNo, String type, String severity, String targetId, String org, String district, Instant occurred) {
        String id = alarmId(suffix);
        Instant received = occurred.plusSeconds(3);
        jdbc.update("INSERT INTO alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at)"
                + " SELECT ?,?,?,?,?,?,?,?,'mock',?,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM alarm WHERE alarm_id=? OR (source_id=? AND source_alarm_id=?))",
                id, targetId, SOURCE_ID, sourceAlarmNo, type, severity, ts(occurred), ts(received), org, district, ts(received),
                id, SOURCE_ID, sourceAlarmNo);
    }

    private void pending(String suffix, String org, String district, Instant occurred) {
        event(suffix, PENDING, org, district, occurred.plusSeconds(3), occurred.plusSeconds(3), 0);
    }

    private void confirmed(String suffix, String org, String district, Instant occurred, String actor, Instant verifiedAt, String note) {
        event(suffix, CONFIRMED, org, district, occurred.plusSeconds(3), verifiedAt, 1);
        history(suffix, 1, PENDING, CONFIRMED, CONFIRMED, note, actor, verifiedAt);
    }

    private void falsePositive(String suffix, String org, String district, Instant occurred, String actor, Instant verifiedAt, String note) {
        event(suffix, FALSE_POSITIVE, org, district, occurred.plusSeconds(3), verifiedAt, 1);
        history(suffix, 1, PENDING, FALSE_POSITIVE, FALSE_POSITIVE, note, actor, verifiedAt);
    }

    /** 事件与告警同范围（读取链按 a.owner_org_id=e.owner_org_id 关联）；告警没插成时不插事件，避免 FK 让整个种子事务中止。 */
    private void event(String suffix, String state, String org, String district, Instant created, Instant updated, long version) {
        String id = eventId(suffix), alarm = alarmId(suffix);
        jdbc.update("INSERT INTO uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " SELECT ?,?,?,?,?,?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM alarm WHERE alarm_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM uav_event WHERE event_id=? OR alarm_id=?)",
                id, alarm, state, org, district, ts(created), ts(updated), version, alarm, id, alarm);
    }

    /** 核实历史与服务路径同形：version 是核实后的事件版本，(event_id, version) 唯一。history_id 须留在 VARCHAR(36) 内。 */
    private void history(String suffix, long version, String previous, String resulting, String conclusion, String note, String actor, Instant at) {
        String id = "seed-vol-verify-" + suffix + "-" + version, event = eventId(suffix);
        jdbc.update("INSERT INTO uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at)"
                + " SELECT ?,?,?,?,?,?,?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM uav_event WHERE event_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM uav_event_verification WHERE history_id=? OR (event_id=? AND version=?))",
                id, event, version, previous, resulting, conclusion, note, actor, ts(at), event, id, event, version);
    }

    private static Instant at(String iso) { return Instant.parse(iso); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
