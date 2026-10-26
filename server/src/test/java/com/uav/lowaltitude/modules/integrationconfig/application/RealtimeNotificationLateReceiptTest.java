package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.directory.application.SimulatorNotificationConfiguration;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.ReceiptInput;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.env.MockEnvironment;

/** Real durable transport rows, no network delivery and no device or authorization mutation. */
class RealtimeNotificationLateReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private final RealtimeSimulatorReceiptProjector projector=mock(RealtimeSimulatorReceiptProjector.class);
    private final String actor=id(),subject=id(),token=id(),providerKey=id();
    private final long now=1_799_999_000_000L;
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private RealtimeNotificationTransport transport;

    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE simulator_notification_message(message_id VARCHAR PRIMARY KEY,attempt_key VARCHAR UNIQUE,actor_id VARCHAR,kind VARCHAR,subject_id VARCHAR,payload VARCHAR,state VARCHAR,created_at BIGINT,version BIGINT,delivered_at BIGINT,answered_at BIGINT,completed_at BIGINT,acknowledged_at BIGINT,projection_status VARCHAR)");
        var env=new MockEnvironment().withProperty("app.notifications.transport","simulator");env.setActiveProfiles("local","qa");
        ObjectProvider<RealtimeSimulatorReceiptProjector> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(projector);
        when(projector.apply(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),any(),any(),any())).thenReturn(true);
        transport=new RealtimeNotificationTransport(jdbc,json,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),env,
                mock(AccessService.class),mock(AuditService.class),mock(SimulatorNotificationConfiguration.class),provider);
        AuthContext.set(new AuthUser(actor,"isolated-receiver","Receiver",id(),0,false,"ALL"));
    }
    @AfterEach void close(){AuthContext.clear();database.shutdown();}

    @Test void smsLateDeliveryUsesTheFrozenAttemptAndDuplicateReceiptRemainsIdempotent() throws Exception {
        String message=message("ADVISORY_SMS","TIMEOUT",null);
        String payload=jdbc.queryForObject("SELECT payload FROM simulator_notification_message WHERE message_id=?",String.class,message);
        var result=transport.receipt(message,input(0,"DELIVERED"));
        assertThat(result.state()).isEqualTo("DELIVERED");
        assertThat(result.payload().has("claim_token")).isFalse();
        verify(projector).apply(eq(message),eq("ADVISORY_SMS"),eq(subject),eq(json.readTree(payload)),eq("DELIVERED"),eq(now),isNull(),isNull(),isNull(),isNull());
        transport.receipt(message,input(0,"DELIVERED"));
        verifyNoMoreInteractions(projector);
        assertThat(jdbc.queryForObject("SELECT payload FROM simulator_notification_message WHERE message_id=?",String.class,message)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT version FROM simulator_notification_message WHERE message_id=?",Long.class,message)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulator_notification_message",Long.class)).isEqualTo(1);
    }

    @Test void voiceTimeoutAfterAnswerPreservesTheOriginalAnswerAndAcceptsOnlyPlaybackCompletion() {
        long answered=now-90_000;
        String message=message("ADVISORY_VOICE","TIMEOUT",answered);
        assertThatThrownBy(() -> transport.receipt(message,input(0,"ANSWERED"))).isInstanceOf(ApiException.class);
        transport.receipt(message,input(0,"PLAYED"));
        verify(projector).apply(eq(message),eq("ADVISORY_VOICE"),eq(subject),any(),eq("PLAYED"),isNull(),eq(answered),eq(now),isNull(),isNull());
        assertThat(jdbc.queryForObject("SELECT answered_at FROM simulator_notification_message WHERE message_id=?",Long.class,message)).isEqualTo(answered);
    }

    @Test void voiceTimeoutBeforeAnswerStillRequiresSeparateAnswerAndPlaybackFacts() {
        String message=message("ADVISORY_VOICE","TIMEOUT",null);
        assertThatThrownBy(() -> transport.receipt(message,input(0,"PLAYED"))).isInstanceOf(ApiException.class);
        transport.receipt(message,input(0,"ANSWERED"));
        transport.receipt(message,input(1,"PLAYED"));
        assertThat(jdbc.queryForObject("SELECT state FROM simulator_notification_message WHERE message_id=?",String.class,message)).isEqualTo("PLAYED");
        verify(projector).apply(eq(message),eq("ADVISORY_VOICE"),eq(subject),any(),eq("ANSWERED"),isNull(),eq(now),isNull(),isNull(),isNull());
        verify(projector).apply(eq(message),eq("ADVISORY_VOICE"),eq(subject),any(),eq("PLAYED"),isNull(),eq(now),eq(now),isNull(),isNull());
    }

    @ParameterizedTest @ValueSource(strings={"RISK_NOTICE","UAV_PUNISHMENT","PLAN_FEEDBACK","DEVICE_MAINTENANCE"})
    void otherNotificationKindsDoNotReopenTimeout(String kind) {
        String message=message(kind,"TIMEOUT",null);
        assertThatThrownBy(() -> transport.receipt(message,input(0,"DELIVERED"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(projector);
    }

    @ParameterizedTest @ValueSource(strings={"ADVISORY_SMS","ADVISORY_VOICE"})
    void explicitFailureStaysTerminalAndReceiverIdentityAndVersionRemainRequired(String kind) {
        String failed=message(kind,"FAILED",null);
        String outcome="ADVISORY_SMS".equals(kind)?"DELIVERED":"ANSWERED";
        assertThatThrownBy(() -> transport.receipt(failed,input(0,outcome))).isInstanceOf(ApiException.class);
        String timedOut=message(kind,"TIMEOUT",null);
        assertThatThrownBy(() -> transport.receipt(timedOut,input(1,outcome))).isInstanceOf(ApiException.class);
        AuthContext.set(new AuthUser(id(),"other-receiver","Other",id(),0,false,"ALL"));
        assertThatThrownBy(() -> transport.receipt(timedOut,input(0,outcome))).isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(projector);
    }

    @Test void staleAttemptProjectionKeepsTheRealReceiptWithoutInventingBusinessSuccess() {
        String message=message("ADVISORY_SMS","TIMEOUT",null);
        when(projector.apply(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),any(),any(),any())).thenReturn(false);
        transport.receipt(message,input(0,"DELIVERED"));
        assertThat(jdbc.queryForObject("SELECT state FROM simulator_notification_message WHERE message_id=?",String.class,message)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT projection_status FROM simulator_notification_message WHERE message_id=?",String.class,message)).isEqualTo("IGNORED_STALE_ATTEMPT");
    }

    private String message(String kind,String state,Long answered) {
        String message="simn-"+id();
        var payload=json.createObjectNode().put("provider_key",providerKey).put("claim_token",token).put("requested_at",now-120_000).put("content","预计进入禁飞区");
        jdbc.update("INSERT INTO simulator_notification_message(message_id,attempt_key,actor_id,kind,subject_id,payload,state,created_at,version,answered_at,projection_status) VALUES(?,?,?,?,?,?,?,?,0,?,'APPLIED')",
                message,id(),actor,kind,subject,payload.toString(),state,now-120_000,answered);
        return message;
    }
    private static ReceiptInput input(long version,String outcome){return new ReceiptInput(version,outcome,null);}
    private static String id(){return UUID.randomUUID().toString();}
}
