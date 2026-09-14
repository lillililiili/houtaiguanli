package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeInput;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/** C06 合并：窗口内合并、升级新建、降级不建、过期新组、自动关闭；alarm 行零 UPDATE、uav_event 状态不变。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlarmMergePolicyTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    @Autowired AlarmMergePolicy policy;
    @Autowired JdbcTemplate jdbc;
    private final RuleParams params = new StubParams();
    private String org, district, target, ruleSet, ruleSetVersion, run;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "org-" + suffix; district = "dist-" + suffix; target = "target-" + suffix; ruleSet = "rs-" + suffix; ruleSetVersion = "rsv-" + suffix; run = "run-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-" + suffix, "区域");
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", target, "T-" + suffix, org, district);
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,version,created_at,updated_at) values (?,?,?,0,current_timestamp,current_timestamp)", ruleSet, "RS-" + suffix, "测试规则集");
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) values (?,?,1,'PUBLISHED','DEMO',?,'测试','mock',current_timestamp,current_timestamp)", ruleSetVersion, ruleSet, Timestamp.from(T0.toInstant()));
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) values (?,?,?,'ACTIVE','MANUAL',?,?,'RUNNING',0,0,0,0,'mock',current_timestamp)", run, ruleSet, ruleSetVersion, Timestamp.from(T0.toInstant()), Timestamp.from(T0.toInstant()));
    }

    @Test
    void secondHitInsideDedupWindowIsMergedWithoutNewAlarmOrEventChange() {
        MergeOutcome first = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0), "ILLEGAL", "HIGH", T0), params);
        assertThat(first.kind()).isEqualTo("CREATED");
        assertThat(first.alarmId()).isNotNull();
        assertThat(first.eventId()).isNotNull();
        Map<String, Object> alarmBefore = alarm(first.alarmId());
        Map<String, Object> eventBefore = event(first.alarmId());
        MergeOutcome second = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0.plusMinutes(2)), "ILLEGAL", "HIGH", T0.plusMinutes(2)), params);
        assertThat(second.kind()).isEqualTo("MERGED");
        assertThat(second.alarmId()).isNull();
        assertThat(second.groupId()).isEqualTo(first.groupId());
        assertThat(alarmCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select hit_count from alarm_merge_group where group_id=?", Integer.class, first.groupId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select window_expires_at from alarm_merge_group where group_id=?", Timestamp.class, first.groupId()).toInstant())
                .isEqualTo(T0.plusMinutes(7).toInstant());
        assertThat(jdbc.queryForObject("select count(*) from alarm_merge_member where group_id=?", Long.class, first.groupId())).isEqualTo(2L);
        // alarm 行永不 UPDATE：整行快照必须逐列相同；uav_event 状态与版本也不能被合并改动。
        assertThat(alarm(first.alarmId())).isEqualTo(alarmBefore);
        assertThat(event(first.alarmId())).isEqualTo(eventBefore);
    }

    @Test
    void higherSeverityInsideUpgradeWindowCreatesNewAlarmAndLowerOnlyRecordsDowngrade() {
        MergeOutcome first = policy.apply(input(evaluation("ABNORMAL", "MEDIUM", T0), "ABNORMAL", "MEDIUM", T0), params);
        Map<String, Object> alarmBefore = alarm(first.alarmId());
        MergeOutcome upgraded = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0.plusMinutes(3)), "ILLEGAL", "HIGH", T0.plusMinutes(3)), params);
        assertThat(upgraded.kind()).isEqualTo("UPGRADED");
        assertThat(upgraded.alarmId()).isNotNull().isNotEqualTo(first.alarmId());
        assertThat(upgraded.severity()).isEqualTo("HIGH");
        assertThat(alarmCount()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select current_severity||'/'||latest_alarm_id||'/'||first_alarm_id from alarm_merge_group where group_id=?", String.class, first.groupId()))
                .isEqualTo("HIGH/" + upgraded.alarmId() + "/" + first.alarmId());
        assertThat(jdbc.queryForObject("select severity_before||'>'||severity_after from alarm_merge_member where alarm_id=?", String.class, upgraded.alarmId())).isEqualTo("MEDIUM>HIGH");
        MergeOutcome downgraded = policy.apply(input(evaluation("ABNORMAL", "LOW", T0.plusMinutes(4)), "ABNORMAL", "LOW", T0.plusMinutes(4)), params);
        assertThat(downgraded.kind()).isEqualTo("DOWNGRADED");
        assertThat(downgraded.alarmId()).isNull();
        assertThat(alarmCount()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select current_severity from alarm_merge_group where group_id=?", String.class, first.groupId())).isEqualTo("HIGH");
        assertThat(alarm(first.alarmId())).isEqualTo(alarmBefore);
        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id in (?,?) and state_code='PENDING_VERIFICATION' and version=0", Long.class, first.alarmId(), upgraded.alarmId())).isEqualTo(2L);
    }

    @Test
    void hitAfterDedupWindowClosesOldGroupAndOpensNewOne() {
        MergeOutcome first = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0), "ILLEGAL", "HIGH", T0), params);
        Map<String, Object> alarmBefore = alarm(first.alarmId());
        MergeOutcome late = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0.plusMinutes(6)), "ILLEGAL", "HIGH", T0.plusMinutes(6)), params);
        assertThat(late.kind()).isEqualTo("CREATED");
        assertThat(late.groupId()).isNotEqualTo(first.groupId());
        assertThat(alarmCount()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select state||'/'||closed_reason from alarm_merge_group where group_id=?", String.class, first.groupId())).isEqualTo("AUTO_CLOSED/EXPIRED_BEFORE_NEW_HIT");
        assertThat(jdbc.queryForObject("select state from alarm_merge_group where group_id=?", String.class, late.groupId())).isEqualTo("OPEN");
        assertThat(alarm(first.alarmId())).isEqualTo(alarmBefore);
    }

    @Test
    void autoCloseOnlyClosesExpiredGroupsWhoseLatestEvaluationIsNoLongerAlarmWorthy() {
        MergeOutcome first = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0), "ILLEGAL", "HIGH", T0), params);
        Map<String, Object> alarmBefore = alarm(first.alarmId());
        Map<String, Object> eventBefore = event(first.alarmId());
        // 到期但最近研判仍是 ILLEGAL：不关闭。
        assertThat(policy.autoCloseExpired(T0.plusMinutes(5 + 15 + 1), params)).isZero();
        evaluation("LEGAL", null, T0.plusMinutes(10));
        // 未到 auto_close_min：不关闭。
        assertThat(policy.autoCloseExpired(T0.plusMinutes(5 + 14), params)).isZero();
        assertThat(policy.autoCloseExpired(T0.plusMinutes(5 + 15 + 1), params)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state||'/'||closed_reason from alarm_merge_group where group_id=?", String.class, first.groupId())).isEqualTo("AUTO_CLOSED/WINDOW_EXPIRED");
        assertThat(alarm(first.alarmId())).isEqualTo(alarmBefore);
        assertThat(event(first.alarmId())).isEqualTo(eventBefore);
    }

    @Test
    void rejectedIngestionIsReportedAsBlockedAndSameEvaluationIsIdempotent() {
        jdbc.update("update app_org set enabled=false where org_id=?", org);
        String evaluation = evaluation("ILLEGAL", "HIGH", T0);
        MergeOutcome blocked = policy.apply(input(evaluation, "ILLEGAL", "HIGH", T0), params);
        assertThat(blocked.kind()).isEqualTo("BLOCKED");
        assertThat(alarmCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarm_merge_group where target_id=?", Long.class, target)).isZero();
        jdbc.update("update app_org set enabled=true where org_id=?", org);
        MergeOutcome created = policy.apply(input(evaluation, "ILLEGAL", "HIGH", T0), params);
        assertThat(created.kind()).isEqualTo("CREATED");
        MergeOutcome replay = policy.apply(input(evaluation, "ILLEGAL", "HIGH", T0), params);
        assertThat(replay.kind()).isEqualTo("CREATED");
        assertThat(replay.alarmId()).isEqualTo(created.alarmId());
        assertThat(alarmCount()).isEqualTo(1L);
    }

    @Test
    void windowsUseMergeTimeWhileAlarmKeepsObservedOccurredAt() {
        // 窗口算术只看合并时刻（评估时钟）；as_of 只作为告警 occurred_at。否则手动/回放研判会因旧观测时刻被错误并组或错误开新组。
        OffsetDateTime observed = T0.minusHours(3);
        MergeOutcome first = policy.apply(input(evaluation("ILLEGAL", "HIGH", observed), "ILLEGAL", "HIGH", observed, T0), params);
        assertThat(first.kind()).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select occurred_at from alarm where alarm_id=?", Timestamp.class, first.alarmId()).toInstant()).isEqualTo(observed.toInstant());
        assertThat(jdbc.queryForObject("select received_at from alarm where alarm_id=?", Timestamp.class, first.alarmId()).toInstant()).isEqualTo(T0.toInstant());
        assertThat(jdbc.queryForObject("select window_opened_at from alarm_merge_group where group_id=?", Timestamp.class, first.groupId()).toInstant()).isEqualTo(T0.toInstant());
        assertThat(jdbc.queryForObject("select window_expires_at from alarm_merge_group where group_id=?", Timestamp.class, first.groupId()).toInstant()).isEqualTo(T0.plusMinutes(5).toInstant());
        // 观测时刻已在去重窗之外、但合并时刻在窗内 → 仍是合并。
        MergeOutcome merged = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0.plusMinutes(10)), "ILLEGAL", "HIGH", T0.plusMinutes(10), T0.plusMinutes(2)), params);
        assertThat(merged.kind()).isEqualTo("MERGED");
        assertThat(merged.groupId()).isEqualTo(first.groupId());
        assertThat(jdbc.queryForObject("select window_expires_at from alarm_merge_group where group_id=?", Timestamp.class, first.groupId()).toInstant()).isEqualTo(T0.plusMinutes(7).toInstant());
        assertThat(jdbc.queryForObject("select created_at from alarm_merge_member where group_id=? and member_kind='MERGED'", Timestamp.class, first.groupId()).toInstant())
                .isEqualTo(T0.plusMinutes(2).toInstant());
        // 观测时刻在窗内、但合并时刻已过去重窗 → 关旧组、开新组。
        MergeOutcome late = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0.plusMinutes(1)), "ILLEGAL", "HIGH", T0.plusMinutes(1), T0.plusMinutes(8)), params);
        assertThat(late.kind()).isEqualTo("CREATED");
        assertThat(late.groupId()).isNotEqualTo(first.groupId());
        assertThat(jdbc.queryForObject("select state||'/'||closed_reason from alarm_merge_group where group_id=?", String.class, first.groupId())).isEqualTo("AUTO_CLOSED/EXPIRED_BEFORE_NEW_HIT");
        assertThat(jdbc.queryForObject("select closed_at from alarm_merge_group where group_id=?", Timestamp.class, first.groupId()).toInstant()).isEqualTo(T0.plusMinutes(8).toInstant());
        assertThat(alarmCount()).isEqualTo(2L);
    }

    @Test
    void manualEscalationAlwaysCreatesAlarmAndJoinsOpenGroup() {
        MergeOutcome first = policy.apply(input(evaluation("ILLEGAL", "HIGH", T0), "ILLEGAL", "HIGH", T0), params);
        MergeOutcome manual = policy.escalateManually(input(evaluation("UNDETERMINED", null, T0.plusMinutes(1)), "UNDETERMINED", null, T0.plusMinutes(1)));
        assertThat(manual.kind()).isEqualTo("MANUAL_ESCALATION");
        assertThat(manual.alarmId()).isNotNull().isNotEqualTo(first.alarmId());
        assertThat(manual.groupId()).isEqualTo(first.groupId());
        assertThat(jdbc.queryForObject("select source_alarm_id from alarm where alarm_id=?", String.class, manual.alarmId())).startsWith("manual:");
        assertThat(jdbc.queryForObject("select severity from alarm where alarm_id=?", String.class, manual.alarmId())).isEqualTo("UNKNOWN");
        assertThat(alarmCount()).isEqualTo(2L);
    }

    private MergeInput input(String evaluationId, String legalStatus, String grade, OffsetDateTime at) { return input(evaluationId, legalStatus, grade, at, at); }

    /** occurredAt = 研判 as_of（告警 occurred_at）；mergedAt = 评估/操作时刻（窗口算术与 received_at）。 */
    private MergeInput input(String evaluationId, String legalStatus, String grade, OffsetDateTime occurredAt, OffsetDateTime mergedAt) {
        return new MergeInput(evaluationId, target, org, district, "mock", ruleSet, ruleSetVersion, legalStatus, "FULL", grade,
                grade == null ? null : new BigDecimal("70"), List.of("INSIDE_RESTRICTED_AIRSPACE"), occurredAt, mergedAt);
    }

    private String evaluation(String legalStatus, String grade, OffsetDateTime at) {
        String id = UUID.randomUUID().toString();
        // score 与 grade 必须成对（ck_stage7_rule_evaluation_score_grade）：有等级就给一个分数。
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,"
                + "violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " values (?,?,?,'ACTIVE','TARGET',?,?,?,'REPLAY','FULL',?,?,?,cast('[]' as json),cast('[]' as json),cast('[]' as json),cast('[]' as json),cast('{}' as json),?,?,'mock',?)",
                id, run, ruleSetVersion, target, Timestamp.from(at.toInstant()), Timestamp.from(at.toInstant()), legalStatus, grade == null ? null : new BigDecimal("70"), grade, org, district, Timestamp.from(at.toInstant()));
        return id;
    }

    private long alarmCount() { return jdbc.queryForObject("select count(*) from alarm where target_id=?", Long.class, target); }
    /** JSON 列在 H2 上回读为 byte[]，逐列比较必须转成文本；其余列原样快照。 */
    private Map<String, Object> alarm(String alarmId) {
        return jdbc.queryForMap("select alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,cast(detail as varchar) as detail,"
                + "source_mode,owner_org_id,district_id,created_at from alarm where alarm_id=?", alarmId);
    }
    private Map<String, Object> event(String alarmId) { return jdbc.queryForMap("select state_code,version,updated_at from uav_event where alarm_id=?", alarmId); }

    private static final class StubParams implements RuleParams {
        private final Map<String, String> values = Map.of("C06.dedup_window_min", "5", "C06.upgrade_window_min", "10", "C06.auto_close_min", "15",
                "C06.severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW");
        @Override public String ruleSetVersionId() { return "rsv-test"; }
        @Override public String paramStatus(String ruleCode, String key) { return "DEMO"; }
        @Override public BigDecimal number(String ruleCode, String key) { return new BigDecimal(required(ruleCode, key)); }
        @Override public int integer(String ruleCode, String key) { return Integer.parseInt(required(ruleCode, key)); }
        @Override public boolean bool(String ruleCode, String key) { return Boolean.parseBoolean(required(ruleCode, key)); }
        @Override public String string(String ruleCode, String key) { return required(ruleCode, key); }
        @Override public List<String> list(String ruleCode, String key) { return List.of(required(ruleCode, key).split(",")); }
        private String required(String ruleCode, String key) {
            String value = values.get(ruleCode + "." + key);
            if (value == null) throw new IllegalStateException("missing " + ruleCode + "." + key);
            return value;
        }
    }
}
