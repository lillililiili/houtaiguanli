package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import com.fasterxml.jackson.databind.JsonNode;

/** Explicit QA preparation uses the same HTTP, authorization and persistence boundaries. */
@TestPropertySource(properties="app.qa.notification-setup.enabled=true")
class LocalQaNotificationApiTest extends LocalPlanFilingApiTest {
    static final String NOTICE = BASE + "/notification-settings";

    @Test void preparesOnlyExpiringMockSettingsAndPreservesPlanFacts() throws Exception {
        String id = linkedPlan();
        var before = read("/api/v1/flight-plans/" + id);
        var result = prepare(id, "ADVISORY_SMS", null, 0, 200);
        assertThat(result.path("channel_type").asText()).isEqualTo("MOCK");
        assertThat(result.path("valid_until").asLong()).isBetween(System.currentTimeMillis(), System.currentTimeMillis()+1_201_000);
        assertThat(jdbc.queryForObject("select enabled from notification_setting where setting_id='advisory-sms'", Boolean.class)).isTrue();
        assertThat(read("/api/v1/flight-plans/"+id).path("status_code")).isEqualTo(before.path("status_code"));
        prepare(id, "ADVISORY_SMS", null, 0, 409);
    }

    @Test void livePlansAndWrongContactRolesCannotPrepareNotifications() throws Exception {
        String id = linkedPlan();
        String pilot = read("/api/v1/flight-plans/"+id+"/subjects").path("pilot_contact_id").asText();
        prepare(id, "UAV_PUNISHMENT", pilot, 0, 409);
        jdbc.update("update flight_plan set source_mode='live' where plan_id=?", id);
        prepare(id, "ADVISORY_SMS", null, 0, 409);
        assertThat(jdbc.queryForObject("select enabled from notification_setting where setting_id='advisory-sms'", Boolean.class)).isFalse();
    }

    @Test void existingNonQaConfigurationIsNotOverwritten() throws Exception {
        String id = linkedPlan();
        jdbc.update("update notification_setting set enabled=true,channel_type='API',endpoint_ref='existing-deployment' where setting_id='advisory-sms'");
        prepare(id, "ADVISORY_SMS", null, 0, 409);
        assertThat(jdbc.queryForObject("select endpoint_ref from notification_setting where setting_id='advisory-sms'", String.class)).isEqualTo("existing-deployment");
    }

    @Test void notificationSetupRequiresDedicatedAuthorization() throws Exception {
        String id = linkedPlan();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-QA-NOTICE-READ','QA只读','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-QA-NOTICE-READ','interfaces','OP',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-QA-NOTICE-READ' WHERE account='admin1'");
        prepare(id, "ADVISORY_SMS", null, 0, 403);
    }

    @Test void createsFeedbackPenaltyAndMaintenanceRecipientsWithRealDirectoryLinks() throws Exception {
        String id=linkedPlan();var subjects=read("/api/v1/flight-plans/"+id+"/subjects");
        String pilot=subjects.path("pilot_contact_id").asText();
        jdbc.update("update business_contact set roles=CAST(? AS JSON) where contact_id=?",json.writeValueAsString(List.of("PLAN_LIAISON","UNIT_LIAISON","MAINTENANCE","PILOT")),pilot);
        for(String purpose:List.of("PLAN_FEEDBACK","UAV_PUNISHMENT","DEVICE_MAINTENANCE")) {
            var prepared=prepare(id,purpose,pilot,0,200);
            assertThat(prepared.path("setting_id").asText()).isNotBlank();
            if("UAV_PUNISHMENT".equals(purpose))assertThat(prepared.path("recipient_id").asText()).isNotBlank();
        }
        assertThat(read("/api/v1/flight-plans/"+id+"/subjects").path("feedback_recipient").path("configured").asBoolean()).isTrue();
    }

    @Test void missingAuthenticationAndInvalidPurposeAreRejected() throws Exception {
        mvc.perform(get(NOTICE)).andExpect(status().isUnauthorized());
        prepare(linkedPlan(),"ARBITRARY",null,0,400);
    }

    String linkedPlan() throws Exception {
        var body=plan("qa-notice-"+UUID.randomUUID());body.put("filing",filing(true));
        return send("/plans",body,200).path("subject_id").asText();
    }
    JsonNode prepare(String plan, String purpose, String contact, long version, int status) throws Exception {
        var body=new HashMap<String,Object>();body.put("plan_id",plan);body.put("purpose",purpose);body.put("expected_version",version);
        if(contact!=null)body.put("contact_id",contact);
        String response=mvc.perform(post(NOTICE).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
}
