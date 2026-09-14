package com.uav.lowaltitude.modules.alarm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

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

/** 告警读取以阶段 4 动作权限和冻结的 page/size 契约为准，不能复用旧页面的空占位。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlarmReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String sessionId;
    private String userId;
    private String role;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-ALARM-" + suffix;
        userId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", userId, "alarm-reader-" + suffix, "告警读取人", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", sessionId, userId, System.currentTimeMillis() + 3_600_000L);
    }

    @Test
    void listUsesStageFourPermissionAndFrozenSizeEnvelope() throws Exception {
        mvc.perform(get("/api/v1/alarms?owner_org_id=does-not-exist").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void alarmReadPermissionPrecedesInvalidAndRepeatedQueryParsing() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:read'", role);
        mvc.perform(get("/api/v1/alarms?page=bad&page=2&unknown=value").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsUnknownRepeatedAndRetiredPageSizeParametersForAuthorizedReader() throws Exception {
        mvc.perform(get("/api/v1/alarms?page_size=20").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/alarms?severity=HIGH&severity=LOW").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void targetFilterRequiresTargetReadBeforeItCanRevealTotals() throws Exception {
        mvc.perform(get("/api/v1/alarms?target_id=guessable-target").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void targetFilterPermissionPrecedesItsOwnSyntaxAndOtherQueryValidation() throws Exception {
        mvc.perform(get("/api/v1/alarms?target_id=x&page=bad").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/alarms?target_id=x&target_id=y").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'target:read','READ',false,current_timestamp)", role);
        mvc.perform(get("/api/v1/alarms?target_id=x&page=bad").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void alarmWithoutEventKeepsExplicitNullEventIdInJson() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), alarm = UUID.randomUUID().toString();
        scope(org, district, "NULL-" + suffix);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", source, "SRC-N-" + suffix, "来源");
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV','LOW',current_timestamp,'mock',?,?,current_timestamp)", alarm, source, "null-" + suffix, org, district);
        mvc.perform(get("/api/v1/alarms/" + alarm).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.event_id").hasJsonPath())
                .andExpect(jsonPath("$.data.event_id").value(nullValue()));
    }

    @Test
    void assignedGrantsDoNotExposeCartesianCrossTupleAlarmDetail() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgA = UUID.randomUUID().toString(), districtA = UUID.randomUUID().toString();
        String orgB = UUID.randomUUID().toString(), districtB = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString(), crossAlarm = UUID.randomUUID().toString();
        scope(orgA, districtA, "A-" + suffix); scope(orgB, districtB, "B-" + suffix);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", userId);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp),(?,?,?,current_timestamp)", userId, orgA, districtA, userId, orgB, districtB);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", source, "SRC-" + suffix, "来源");
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV','HIGH',current_timestamp,'mock',?,?,current_timestamp)", crossAlarm, source, "cross-" + suffix, orgA, districtB);
        // orgA/districtA 与 orgB/districtB 两条授权不能拼出 orgA/districtB。
        mvc.perform(get("/api/v1/alarms/" + crossAlarm).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ALARM_NOT_FOUND"));
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + orgA + "&district_id=" + districtB).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void allScopeStillHidesDisabledDirectoryTupleFromListTotalAndDetail() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString(), alarm = UUID.randomUUID().toString();
        scope(org, district, "DISABLED-" + suffix);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", source, "SRC-D-" + suffix, "来源");
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV','HIGH',current_timestamp,'mock',?,?,current_timestamp)", alarm, source, "disabled-" + suffix, org, district);
        jdbc.update("update app_district set enabled=false where district_id=?", district);

        // ALL 不是目录失效数据的后门，列表 total 与详情都必须一致地隐藏该对象。
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + org).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/alarms/" + alarm).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ALARM_NOT_FOUND"));
    }

    @Test
    void targetReferenceAndFilterHideTargetWhoseTupleDoesNotMatchItsAlarm() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmOrg = UUID.randomUUID().toString(), alarmDistrict = UUID.randomUUID().toString();
        String targetOrg = UUID.randomUUID().toString(), targetDistrict = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString(), alarm = UUID.randomUUID().toString(), target = UUID.randomUUID().toString();
        scope(alarmOrg, alarmDistrict, "ALARM-" + suffix); scope(targetOrg, targetDistrict, "TARGET-" + suffix);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'target:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", source, "SRC-T-" + suffix, "来源");
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", target, "T-" + suffix, targetOrg, targetDistrict);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV','HIGH',current_timestamp,'mock',?,?,current_timestamp)", alarm, target, source, "mismatch-" + suffix, alarmOrg, alarmDistrict);

        // 即便用户同时可读两个目录，错连 target 也不能借另一元组出现在告警字段或筛选 total 中。
        mvc.perform(get("/api/v1/alarms/" + alarm).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.target_id").doesNotExist());
        mvc.perform(get("/api/v1/alarms?target_id=" + target).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    private void scope(String org, String district, String suffix) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-" + suffix, "区域");
    }
}
