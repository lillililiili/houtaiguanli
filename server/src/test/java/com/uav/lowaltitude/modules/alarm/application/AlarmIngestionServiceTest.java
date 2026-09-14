package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.application.AlarmIngestionService.IngestResult;
import com.uav.lowaltitude.platform.api.ApiException;

/** C06 可信入库：幂等、来源禁用、目录停用、事件只建一次；没有 HTTP 入口。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlarmIngestionServiceTest {
    private static final String SOURCE = "rule-engine-legality-mock";
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    @Autowired AlarmIngestionService service;
    @Autowired JdbcTemplate jdbc;
    private String org, district, target;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "org-" + suffix; district = "dist-" + suffix; target = "target-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-" + suffix, "区域");
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", target, "T-" + suffix, org, district);
    }

    @Test
    void sameSourceAlarmIdIsIdempotentAndCreatesExactlyOnePendingEvent() {
        TrustedAlarmFact fact = fact("eval:" + UUID.randomUUID(), "HIGH", Map.of("evaluation_id", "e1", "legal_status", "ILLEGAL"));
        IngestResult first = service.ingest(fact);
        IngestResult second = service.ingest(fact);
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.alarmId()).isEqualTo(first.alarmId());
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(jdbc.queryForObject("select count(*) from alarm where source_id=? and source_alarm_id=?", Long.class, SOURCE, fact.sourceAlarmId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id=?", Long.class, first.alarmId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state_code from uav_event where alarm_id=?", String.class, first.alarmId())).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("select alarm_type from alarm where alarm_id=?", String.class, first.alarmId())).isEqualTo("RULE_LEGALITY");
        assertThat(jdbc.queryForObject("select owner_org_id||'/'||district_id from alarm where alarm_id=?", String.class, first.alarmId())).isEqualTo(org + "/" + district);
    }

    @Test
    void disabledSourceIsRejectedWithoutWritingAlarmOrEvent() {
        jdbc.update("update integration_source set enabled=false where source_id=?", SOURCE);
        String sourceAlarmId = "eval:" + UUID.randomUUID();
        assertThatThrownBy(() -> service.ingest(fact(sourceAlarmId, "HIGH", Map.of())))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode()).isEqualTo("INVALID_ALARM_FACT");
        assertThat(jdbc.queryForObject("select count(*) from alarm where source_alarm_id=?", Long.class, sourceAlarmId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from uav_event where owner_org_id=?", Long.class, org)).isZero();
    }

    @Test
    void disabledDirectoryOrForeignModeTargetBlocksIngestion() {
        jdbc.update("update app_district set enabled=false where district_id=?", district);
        String sourceAlarmId = "eval:" + UUID.randomUUID();
        assertThatThrownBy(() -> service.ingest(fact(sourceAlarmId, "HIGH", Map.of())))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode()).isEqualTo("INVALID_ALARM_FACT");
        jdbc.update("update app_district set enabled=true where district_id=?", district);
        // 目标是 mock 模式却声称 replay 来源：模式必须与目标一致，不允许把模拟研判告警挂到别的来源。
        TrustedAlarmFact wrongMode = new TrustedAlarmFact("rule-engine-legality-replay", sourceAlarmId, target, "RULE_LEGALITY", "HIGH", AT, AT, Map.of(), "replay");
        assertThatThrownBy(() -> service.ingest(wrongMode)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from alarm where source_alarm_id=?", Long.class, sourceAlarmId)).isZero();
    }

    @Test
    void detailIsWhitelistedAndSeverityValidated() {
        assertThatThrownBy(() -> service.ingest(fact("eval:x", "HIGH", Map.of("input_snapshot", "secret"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("白名单");
        assertThatThrownBy(() -> service.ingest(fact("eval:y", "URGENT", Map.of()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.ingest(new TrustedAlarmFact("some-other-source", "eval:z", target, "RULE_LEGALITY", "HIGH", AT, AT, Map.of(), "mock")))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from alarm where owner_org_id=?", Long.class, org)).isZero();
    }

    private TrustedAlarmFact fact(String sourceAlarmId, String severity, Map<String, Object> detail) {
        return new TrustedAlarmFact(SOURCE, sourceAlarmId, target, "RULE_LEGALITY", severity, AT, AT, detail, "mock");
    }
}
