package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Identity and notification ownership do not need current geometric risk evaluation. */
class UavRiskCaseIdentityLookupTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private UavRiskCaseIdentity identities;

    @BeforeEach void initialize(){
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,state_code VARCHAR,version BIGINT,alarm_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case(risk_case_id VARCHAR PRIMARY KEY,root_event_id VARCHAR,superseded_by VARCHAR,source_mode VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case_member(risk_case_id VARCHAR,event_id VARCHAR,role VARCHAR,created_at BIGINT,PRIMARY KEY(risk_case_id,event_id))");
        jdbc.execute("CREATE TABLE uav_risk_case_verification(risk_case_id VARCHAR,case_version BIGINT,conclusion VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_channel(risk_case_id VARCHAR,channel VARCHAR,event_id VARCHAR,task_scope VARCHAR,PRIMARY KEY(risk_case_id,channel))");
        jdbc.execute("CREATE TABLE uav_auto_sms_task(event_id VARCHAR,risk_scope VARCHAR,attempt_count INT,provider_key VARCHAR)");
        jdbc.execute("CREATE TABLE uav_auto_voice_task(event_id VARCHAR,risk_scope VARCHAR,attempt_count INT,provider_key VARCHAR)");
        identities=spy(new UavRiskCaseIdentity(jdbc,new AppClock(Clock.systemUTC())));
    }
    @AfterEach void close(){RequestContextHolder.resetRequestAttributes();database.shutdown();}

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void sameCycleKeepsItsChannelOwnersAcrossObservedHandoff(String mode){
        String predicted=event("PENDING_VERIFICATION"),observed=event("PENDING_VERIFICATION"),risk=risk(predicted,mode);
        member(risk,observed,"OBSERVED");
        confirm(risk,1,"CONFIRMED");
        assertThat(identities.hasCases(observed)).isTrue();
        assertThat(identities.caseId(observed)).isEqualTo(risk);
        assertThat(identities.confirmedForDisposal(observed)).isTrue();
        assertThat(identities.notificationTaskOwner(observed,"SMS")).isEqualTo(predicted);
        assertThat(identities.notificationTaskOwner(observed,"VOICE")).isEqualTo(predicted);
        jdbc.update("INSERT INTO uav_risk_channel VALUES (?,'SMS',?,?)",risk,observed,risk);
        assertThat(identities.notificationTaskOwner(predicted,"SMS")).isEqualTo(observed);
        assertThat(identities.notificationTaskOwner(observed,"VOICE")).isEqualTo(predicted);
        assertThat(identities.notificationOwner(predicted)).isEqualTo(observed);
        assertThat(identities.taskScope(observed,"SMS")).isEqualTo(risk);
        verify(identities,never()).details(anyString());
        verify(identities,never()).cases(anyString());
    }

    @Test void sharedEventCannotInheritOneAreasVerificationOrNotificationOwnership(){
        String shared=event("CONFIRMED"),first=risk(shared,"live"),second=risk(shared,"live");
        // The event's old confirmation is ambiguous once it belongs to two areas.
        assertThat(identities.caseId(shared)).isNull();
        assertThat(identities.confirmedForDisposal(shared)).isFalse();
        confirm(first,1,"CONFIRMED");
        assertThat(identities.confirmedForDisposal(shared)).isFalse();
        assertThat(identities.withCase(first,()->identities.confirmedForDisposal(shared))).isTrue();
        assertThat(identities.withCase(second,()->identities.confirmedForDisposal(shared))).isFalse();
        assertThat(identities.withCase(first,()->identities.caseId(shared))).isEqualTo(first);
        assertThat(identities.withCase(second,()->identities.caseId(shared))).isEqualTo(second);
        assertThat(identities.caseId(shared)).isNull();
        assertThat(identities.notificationTaskOwner(shared,"SMS")).isEqualTo(shared);
        String unrelated=id();
        assertThat(identities.withCase(unrelated,()->identities.hasCases(shared))).isFalse();
        assertThat(identities.withCase(unrelated,()->identities.confirmedForDisposal(shared))).isFalse();
    }

    @Test void requestScopeIsHonoredAndBackgroundScopeIsRestored(){
        String shared=event("PENDING_VERIFICATION"),first=risk(shared,"replay"),second=risk(shared,"replay");
        var request=new MockHttpServletRequest();request.setParameter("risk_case_id",first);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(identities.caseId(shared)).isEqualTo(first);
        assertThat(identities.withCase(second,()->identities.caseId(shared))).isEqualTo(second);
        assertThat(identities.caseId(shared)).isEqualTo(first);
        request.setAttribute(UavRiskCaseIdentity.REQUEST_CASE,second);
        assertThat(identities.caseId(shared)).isEqualTo(second);
    }

    @Test void supersessionAndVerificationUpdatesAreVisibleWithoutAnIdentityCache(){
        String shared=event("CONFIRMED"),first=risk(shared,"live"),replacement=risk(shared,"live");
        assertThat(identities.caseId(shared)).isNull();
        jdbc.update("UPDATE uav_risk_case SET superseded_by=? WHERE risk_case_id=?",replacement,first);
        assertThat(identities.caseId(shared)).isEqualTo(replacement);
        assertThat(identities.confirmedForDisposal(shared)).isTrue();
        confirm(replacement,1,"FALSE_POSITIVE");
        assertThat(identities.confirmedForDisposal(shared)).isFalse();
        confirm(replacement,2,"CONFIRMED");
        assertThat(identities.confirmedForDisposal(shared)).isTrue();
        assertThat(identities.withCase(first,()->identities.hasCases(shared))).isFalse();
    }

    @Test void legacyChannelAmbiguityStillBlocksSharedAreas(){
        String shared=event("PENDING_VERIFICATION"),first=risk(shared,"live"),second=risk(shared,"live");
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,'',1,?)",shared,id());
        assertThat(identities.legacyNotificationAmbiguous(shared,"SMS")).isTrue();
        assertThat(identities.withCase(first,()->identities.legacyNotificationAmbiguous(shared,"SMS"))).isTrue();
        assertThat(identities.withCase(second,()->identities.legacyNotificationAmbiguous(shared,"VOICE"))).isFalse();
        // One explicit persisted channel association resolves the legacy owner.
        jdbc.update("INSERT INTO uav_risk_channel VALUES (?,'SMS',?,'')",first,shared);
        assertThat(identities.legacyNotificationAmbiguous(shared,"SMS")).isFalse();
        assertThat(identities.withCase(first,()->identities.taskScope(shared,"SMS"))).isEmpty();
        assertThat(identities.withCase(second,()->identities.taskScope(shared,"SMS"))).isEqualTo(second);
        verify(identities,never()).details(anyString());
    }

    @Test void eventsWithoutAnActiveRiskKeepTheirLegacyOwnership(){
        String event=event("CONFIRMED");
        assertThat(identities.hasCases(event)).isFalse();
        assertThat(identities.hasCases(null)).isFalse();
        assertThat(identities.caseId(event)).isNull();
        assertThat(identities.confirmedForDisposal(event)).isFalse();
        assertThat(identities.notificationTaskOwner(event,"VOICE")).isEqualTo(event);
        assertThat(identities.notificationOwner(event)).isEqualTo(event);
        assertThat(identities.taskScope(event,"SMS")).isEmpty();
    }

    private String event(String state){String event=id();jdbc.update("INSERT INTO uav_event VALUES (?,?,0,?)",event,state,id());return event;}
    private String risk(String event,String mode){String risk=id();jdbc.update("INSERT INTO uav_risk_case VALUES (?,?,NULL,?)",risk,event,mode);member(risk,event,"PREDICTED");return risk;}
    private void member(String risk,String event,String role){jdbc.update("INSERT INTO uav_risk_case_member VALUES (?,?,?,0)",risk,event,role);}
    private void confirm(String risk,long version,String result){jdbc.update("INSERT INTO uav_risk_case_verification VALUES (?,?,?)",risk,version,result);}
    private static String id(){return UUID.randomUUID().toString();}
}
