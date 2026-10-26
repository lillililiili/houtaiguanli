package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class AutoSmsQueueContinuityTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AutoSmsRepository repository;
    private final AutoSmsPolicy policy=mock(AutoSmsPolicy.class);
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final AdvisorySmsPort channel=mock(AdvisorySmsPort.class);
    private final long now=1_799_999_000_000L;

    @BeforeEach void setup(){
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE uav_auto_sms_task(event_id VARCHAR,risk_scope VARCHAR DEFAULT '',status VARCHAR,policy_code VARCHAR,reason VARCHAR,updated_at BIGINT,attempt_count INT DEFAULT 0,provider_key VARCHAR,lease_until BIGINT,claim_token VARCHAR,trigger_source VARCHAR,evaluation_id VARCHAR,evaluated_at BIGINT,data_updated_at BIGINT,triggered_at BIGINT,delivery_record_id VARCHAR,PRIMARY KEY(event_id,risk_scope))");
        repository=new AutoSmsRepository(jdbc);when(policy.enabled()).thenReturn(true);
    }
    @AfterEach void close(){database.shutdown();}

    @Test void moreThanOnePageOfWaitingWorkCannotStarveAnOlderRiskAndSendingRemainsFirst() {
        List<String> waiting=IntStream.range(0,237).mapToObj(i->id()).sorted().toList();
        for(int i=0;i<waiting.size();i++)insert(waiting.get(i),"WAITING",i==waiting.size()-1?now-172_800_000L:now);
        String old=waiting.get(waiting.size()-1),sending=id(),unknown=id();
        insert(sending,"SENDING",now);insert(unknown,"UNKNOWN",now);
        // Multiple regional tasks for one event must not duplicate its position in a page.
        jdbc.update("INSERT INTO uav_auto_sms_task(event_id,risk_scope,status,updated_at) VALUES(?,?,'WAITING',?)",waiting.get(0),id(),now);
        var service=spy(service());var visited=new ArrayList<String>();
        doAnswer(call -> {visited.add(call.getArgument(0));return null;}).when(service).process(anyString());
        service.poll();
        assertThat(visited).hasSize(201);assertThat(visited.get(0)).isEqualTo(sending);assertThat(visited).doesNotContain(old,unknown);
        visited.clear();service.poll();
        assertThat(visited).hasSize(38);assertThat(visited.get(0)).isEqualTo(sending);assertThat(visited).contains(old).doesNotContain(unknown);
        visited.clear();service.poll(); // End cursor resets; only in-flight work is visited this tick.
        assertThat(visited).containsExactly(sending);
        visited.clear();service.poll();
        assertThat(visited).hasSize(201);assertThat(visited).doesNotHaveDuplicates();
        verifyNoInteractions(channel);
    }

    @Test void expiredSendingAttemptBecomesUnknownKeepsReceiptIdentityAndNeverResends() {
        String event=id(),token=id(),provider=id();insert(event,"SENDING",now-120_000);
        jdbc.update("UPDATE uav_auto_sms_task SET attempt_count=1,provider_key=?,claim_token=?,lease_until=?,triggered_at=? WHERE event_id=?",provider,token,now-60_000,now-120_000,event);
        when(events.lock(eq(event),any())).thenReturn(new UavEventRepository.EventRow(event,id(),id(),"CONFIRMED",id(),id(),null,null,1,"replay"));
        var service=service();service.process(event);
        var unknown=repository.find(event);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");assertThat(unknown.token()).isEqualTo(token);
        assertThat(unknown.leaseUntil()).isEqualTo(now-60_000);assertThat(unknown.providerKey()).isEqualTo(provider);
        assertThat(repository.sendingCandidates()).isEmpty();assertThat(repository.candidatesAfter("")).isEmpty();
        service.process(event);service.poll();
        assertThat(repository.find(event).attempts()).isEqualTo(1);verifyNoInteractions(channel);
    }

    private AutoSmsService service(){return new AutoSmsService(repository,events,mock(UavAdvisoryRepository.class),policy,channel,
            new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),new ObjectMapper(),mock(AuditService.class),
            new DataSourceTransactionManager(database),mock(NotificationDirectoryService.class));}
    private void insert(String event,String state,long created){
        jdbc.update("INSERT INTO uav_event VALUES (?,?)",event,new Timestamp(created));
        jdbc.update("INSERT INTO uav_auto_sms_task(event_id,status,updated_at) VALUES(?,?,?)",event,state,created);
    }
    private static String id(){return UUID.randomUUID().toString();}
}
