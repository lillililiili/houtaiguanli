package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture;
import com.uav.lowaltitude.platform.time.AppClock;

/** Isolated H2 fixtures; the transport gateway is replaced so no device can be reached. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:direct_background;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
class DirectDisposalBackgroundTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    @Autowired JdbcTemplate jdbc;
    @Autowired DisposalCommandGuard guard;
    @Autowired DisposalJammingChain chain;
    @MockitoSpyBean AppClock clock;
    @MockitoBean DisposalExecutionGateway gateway;
    private String userId, role, eventId, scopedDeviceId;
    private Instant at;

    @BeforeEach void fixture() {
        at = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        userId = UUID.randomUUID().toString();
        role = "DIRECT-BG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : List.of("disposal:direct", "devices")) {
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", userId, role, "直接授权后台测试", role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values (?,?,?)", userId, ORG, DISTRICT);
        eventId = UUID.randomUUID().toString();
        String alarmId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(at);
        jdbc.update("insert into alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) select ?,source_id,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,? from integration_source limit 1", alarmId, eventId, now, now, ORG, DISTRICT, now);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'CONFIRMED',?,?,?,?,0)", eventId, alarmId, ORG, DISTRICT, now, now);
        CounterEvidenceFixture.seed(jdbc, eventId);
        scopedDeviceId = jdbc.queryForObject("select device_id from ops_device order by device_id limit 1", String.class);
        // The positive background fixture needs an explicitly authorized device, not just a device OP grant.
        if (jdbc.update("update device_business_scope set owner_org_id=?,district_id=? where ops_device_id=?", ORG, DISTRICT, scopedDeviceId) == 0)
            jdbc.update("insert into device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) values(?,?,?,current_timestamp,current_timestamp)", scopedDeviceId, ORG, DISTRICT);
    }

    @Test void activeDirectAuthorizationMayReachDeviceGuard() {
        assertThat(guard.mayStart(authorization("DIRECT", "EXECUTING", "LINGYUN_B", 300))).isTrue();
    }

    @Test void queuedAutomationCommandCannotStartWhenEngineIsDisabled() {
        String id = automationAuthorization("EXECUTING");
        assertThat(guard.mayStart(id)).isFalse();
    }

    @Test void automationCannotChainWhenEngineIsDisabled() {
        String id = automationAuthorization("COMPLETED");
        chain.chain(id);
        assertThat(child(id)).isNull();
        verifyNoInteractions(gateway);
    }

    private String automationAuthorization(String status) {
        String runner = com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal.USER_ID;
        if (jdbc.queryForObject("select count(*) from app_role where role_code='ROLE-AUTOMATION'", Integer.class) == 0)
            jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values('ROLE-AUTOMATION','自动规则测试','',false,true,0,0,0,false)");
        if (jdbc.queryForObject("select count(*) from app_user where user_id=?", Integer.class, runner) == 0)
            jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,'automation-rule','自动规则测试','ROLE-AUTOMATION','DISABLED','unused',0,'ALL',0,0,0,0)", runner);
        String id = authorization("DIRECT", status, "LINGYUN_B", 300);
        jdbc.update("update disposal_authorization set requested_by=? where authorization_id=?", runner, id);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "READ", "AUTH", "DELETE"})
    void queuedDirectCommandRequiresCurrentExplicitOpGrant(String level) {
        String id = authorization("DIRECT", "EXECUTING", "LINGYUN_B", 300);
        if ("DELETE".equals(level)) jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:direct'", role);
        else jdbc.update("update app_role_permission set permission_level=? where role_code=? and permission_code='disposal:direct'", level, role);
        assertThat(guard.mayStart(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ROLE", "SCOPE", "ORG", "DISTRICT", "PASSWORD", "DEVICES"})
    void queuedDirectCommandRechecksIdentityScopeAndDevicePermission(String revoked) {
        String id = authorization("DIRECT", "EXECUTING", "LINGYUN_B", 300);
        revoke(revoked);
        assertThat(guard.mayStart(id)).isFalse();
    }

    @Test void queuedDirectCommandStopsAtAuthorizationExpiry() {
        assertThat(guard.mayStart(authorization("DIRECT", "EXECUTING", "LINGYUN_B", -1))).isFalse();
    }

    @Test void queuedReviewedCommandStopsAtAuthorizationExpiry() {
        assertThat(guard.mayStart(authorization("REVIEW", "EXECUTING", "LINGYUN_B", -1))).isFalse();
    }

    @Test void queuedReviewedCommandCannotStartBeforeValidityWindow() {
        String id = authorization("REVIEW", "EXECUTING", "LINGYUN_B", 300);
        jdbc.update("update disposal_authorization set valid_from=? where authorization_id=?", Timestamp.from(at.plusSeconds(30)), id);
        assertThat(guard.mayStart(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "EXPIRED"})
    void terminalReviewedAuthorizationCannotStartQueuedCommand(String status) {
        assertThat(guard.mayStart(authorization("REVIEW", status, "LINGYUN_B", 300))).isFalse();
    }

    @Test void queuedDirectCommandCannotStartBeforeValidityWindow() {
        String id = authorization("DIRECT", "EXECUTING", "LINGYUN_B", 300);
        jdbc.update("update disposal_authorization set valid_from=? where authorization_id=?", Timestamp.from(at.plusSeconds(30)), id);
        assertThat(guard.mayStart(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "STOPPED", "EXPIRED"})
    void terminalDirectAuthorizationCannotStartNewCommand(String status) {
        assertThat(guard.mayStart(authorization("DIRECT", status, "LINGYUN_B", 300))).isFalse();
    }

    @Test void reviewAndUnknownProtocolAuthorizationsKeepExistingGuardBehavior() {
        assertThat(guard.mayStart(authorization("REVIEW", "EXECUTING", "LINGYUN_B", 300))).isTrue();
        assertThat(guard.mayStart("external-protocol-authorization")).isTrue();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "DIRECT,LINGYUN_B", "REVIEW,LINGYUN_B",
            "DIRECT,COUNTERMEASURE_4CH", "REVIEW,COUNTERMEASURE_4CH",
            "DIRECT,MANUAL", "REVIEW,MANUAL"})
    void retiredContinuationNeverCreatesOrDispatchesAnotherAuthorization(String mode, String channel) {
        String parent = authorization(mode, "COMPLETED", channel, 300);
        chain.chain(parent);
        chain.scheduleAfterComplete(parent);
        chain.scheduleAfterDeviceOn(parent);
        chain.retryWhileOn(parent);
        chain.chain(parent);
        assertThat(child(parent)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=?",
                Integer.class, parent)).isZero();
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "DIRECT,LINGYUN_B", "REVIEW,LINGYUN_B",
            "DIRECT,COUNTERMEASURE_4CH", "REVIEW,COUNTERMEASURE_4CH"})
    void oldChainedAuthorizationCannotStartAgain(String mode, String channel) {
        String parent = authorization(mode, "COMPLETED", channel, 300);
        String child = authorization(mode, "EXECUTING", channel, 300);
        jdbc.update("update disposal_authorization set action_type='JAMMING',chained_from_authorization_id=? where authorization_id=?",
                parent, child);
        assertThat(guard.mayStart(child)).isFalse();
        chain.retryWhileOn(parent);
        assertThat(jdbc.queryForObject("select status from disposal_authorization where authorization_id=?",
                String.class, child)).isEqualTo("EXECUTING");
        verifyNoInteractions(gateway);
    }

    private void revoke(String revoked) {
        switch (revoked) {
            case "USER" -> jdbc.update("update app_user set status='DISABLED' where user_id=?", userId);
            case "ROLE" -> jdbc.update("update app_role set enabled=false where role_code=?", role);
            case "SCOPE" -> jdbc.update("delete from app_user_data_scope where user_id=?", userId);
            case "ORG" -> jdbc.update("update app_org set enabled=false where org_id=?", ORG);
            case "DISTRICT" -> jdbc.update("update app_district set enabled=false where district_id=?", DISTRICT);
            case "PASSWORD" -> jdbc.update("update app_user set must_change_password=true where user_id=?", userId);
            case "DIRECT" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:direct'", role);
            case "DEVICES" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='devices'", role);
            default -> throw new IllegalArgumentException(revoked);
        }
    }

    private String authorization(String mode, String status, String channel, long untilSeconds) {
        String id = UUID.randomUUID().toString();
        String deviceId = "MANUAL".equals(channel) ? null : scopedDeviceId;
        jdbc.update("insert into disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at,authorization_mode) values (?,?,'COUNTERMEASURE','UAV_EVENT',?,?,?,'隔离后台验证',?,?,?,?,?,?,?,'demo-v1',?,?,'mock',0,?,?,?)", id, "BG-" + id.substring(0, 20), eventId, deviceId, channel, userId, Timestamp.from(at.minusSeconds(60)), "REVIEW".equals(mode) ? userId : null, "REVIEW".equals(mode) ? Timestamp.from(at.minusSeconds(60)) : null, Timestamp.from(at.minusSeconds(60)), Timestamp.from(at.plusSeconds(untilSeconds)), status, ORG, DISTRICT, Timestamp.from(at), Timestamp.from(at), mode);
        return id;
    }

    private String child(String parent) {
        List<String> ids = jdbc.queryForList("select authorization_id from disposal_authorization where chained_from_authorization_id=?", String.class, parent);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
