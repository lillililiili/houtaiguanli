package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** 仅双门禁 local/test 生成演示来源；生产与 production,local 混合 profile 都不会注册。 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
// 种子按阶段顺序执行：后阶段样例引用前阶段的计划/风险，靠明确 @Order 而不是 Bean 名称顺序。
@Order(45)
public class LocalStage4AlarmSeeder implements ApplicationRunner {
    private static final String SOURCE = "seed-stage4-alarm-source";
    private final JdbcTemplate jdbc;
    private final UavEventRepository events;
    private final AppClock clock;

    public LocalStage4AlarmSeeder(JdbcTemplate jdbc, UavEventRepository events, AppClock clock) {
        this.jdbc = jdbc; this.events = events; this.clock = clock;
    }

    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        Instant at = clock.now();
        org("seed-stage4-alarm-org", "SEED-STAGE4-ALARM", "告警演示机构", at);
        district("seed-stage4-alarm-district", "SEED-STAGE4-ALARM", "告警演示区域", at);
        org("seed-stage4-alarm-other-org", "SEED-STAGE4-ALARM-OTHER", "告警跨域机构", at);
        district("seed-stage4-alarm-other-district", "SEED-STAGE4-ALARM-OTHER", "告警跨域区域", at);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) select ?,'STAGE4-ALARM-MOCK','告警模拟源',true,'mock',?,?,0 where not exists(select 1 from integration_source where source_id=?)", SOURCE, ts(at), ts(at), SOURCE);
        jdbc.update("update integration_source set name='告警模拟源' where source_id=? and name<>'告警模拟源'", SOURCE);
        alarm("pending", "seed-stage4-alarm-org", "seed-stage4-alarm-district", "HIGH", at);
        alarm("evidence", "seed-stage4-alarm-org", "seed-stage4-alarm-district", "MEDIUM", at);
        alarm("same-target-a", "seed-stage4-alarm-org", "seed-stage4-alarm-district", "HIGH", at);
        alarm("same-target-b", "seed-stage4-alarm-org", "seed-stage4-alarm-district", "LOW", at);
        alarm("no-target", "seed-stage4-alarm-org", "seed-stage4-alarm-district", "LOW", at);
        alarm("cross", "seed-stage4-alarm-other-org", "seed-stage4-alarm-other-district", "HIGH", at);
        // createForAlarm 的唯一 alarm_id 是接收链同一语义；重启不覆盖人工已核实 state/version。
        event("pending", "PENDING_VERIFICATION", "seed-stage4-alarm-org", "seed-stage4-alarm-district", at);
        event("evidence", "PENDING_VERIFICATION", "seed-stage4-alarm-org", "seed-stage4-alarm-district", at);
        event("same-target-a", "PENDING_VERIFICATION", "seed-stage4-alarm-org", "seed-stage4-alarm-district", at);
        event("same-target-b", "PENDING_VERIFICATION", "seed-stage4-alarm-org", "seed-stage4-alarm-district", at);
        event("cross", "PENDING_VERIFICATION", "seed-stage4-alarm-other-org", "seed-stage4-alarm-other-district", at);
    }

    private void alarm(String suffix, String org, String district, String severity, Instant at) {
        String target = suffix.startsWith("same-target") ? "seed-stage4-target-shared" : null;
        if (target != null) target(org, district, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) select ?,?,?,?,?,?,?,?,'mock',?,?,? where not exists(select 1 from alarm where alarm_id=?)", "seed-stage4-alarm-" + suffix, target, SOURCE, alarmNo(suffix), "UAV_INTRUSION", severity, ts(at), ts(at), org, district, ts(at), "seed-stage4-alarm-" + suffix);
        jdbc.update("update alarm set source_alarm_id=? where alarm_id=? and source_alarm_id<>?", alarmNo(suffix), "seed-stage4-alarm-" + suffix, alarmNo(suffix));
    }
    /* 告警来源编号按固定顺序编为 GJ-日期-序号，旧库里的英文占位编号一并改写。 */
    private static String alarmNo(String suffix) {
        int seq = switch (suffix) { case "pending" -> 1; case "evidence" -> 2; case "same-target-a" -> 3; case "same-target-b" -> 4; case "no-target" -> 5; default -> 6; };
        return String.format("告警-0905-%03d", seq);
    }
    private void event(String suffix, String state, String org, String district, Instant at) {
        // PostgreSQL 下重复主键会让整个种子事务中止，不能依赖捕获 DuplicateKeyException 实现幂等：先查再插。
        Integer existing = jdbc.queryForObject("select count(*) from uav_event where event_id=?", Integer.class, "seed-stage4-event-" + suffix);
        if (existing != null && existing > 0) return;
        events.createForAlarm("seed-stage4-event-" + suffix, "seed-stage4-alarm-" + suffix, state, org, district, at.atOffset(java.time.ZoneOffset.UTC));
    }
    private void target(String org, String district, Instant at) { jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) select 'seed-stage4-target-shared','目标-0905-101','mock',?,?,?, ?,0 where not exists(select 1 from target where target_id='seed-stage4-target-shared')", org, district, ts(at), ts(at));
        jdbc.update("update target set target_no='目标-0905-101' where target_id='seed-stage4-target-shared' and target_no<>'目标-0905-101'"); }
    private void org(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_org where org_id=?)", id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_org set name=? where org_id=? and name<>?", name, id, name);
    }
    private void district(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_district where district_id=?)", id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_district set name=? where district_id=? and name<>?", name, id, name);
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
