package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.NoCounterDtos;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.AutoHandoff;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.CounterExecutionEvidence;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** Actual history/completion SQL and public read model; isolated schema, no dispatch or notification jobs. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class UavHandoffExecutionHistoryPostgresTest {
    private JdbcTemplate admin, jdbc;
    private String schema, event, authorization, device;
    private long now;
    private DisposalRepository disposals;
    private UavAdvisoryService service;
    private TransactionTemplate readOnly;

    @BeforeEach void fixture() {
        String url=System.getenv("POSTGRES_TEST_URL");
        String user=System.getenv("POSTGRES_TEST_USER"), password=System.getenv("POSTGRES_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        assertThat(admin.queryForObject("SELECT current_database()",String.class)).matches("^stage456_verify_[a-z0-9_]+$");
        schema="handoff_history_"+key().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var dataSource=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        tables();
        var transactions=new DataSourceTransactionManager(dataSource);
        readOnly=new TransactionTemplate(transactions); readOnly.setReadOnly(true);
        disposals=new DisposalRepository(jdbc,transactions);
        event=key(); authorization=key(); device=key();
        String alarm=key(), org=key(), district=key();
        now=Instant.now().minusSeconds(17).toEpochMilli();
        Timestamp at=new Timestamp(now);
        jdbc.update("INSERT INTO uav_event VALUES (?,?,?,?)",event,alarm,org,district);
        jdbc.update("INSERT INTO alarm VALUES (?,'replay')",alarm);
        jdbc.update("INSERT INTO disposal_authorization VALUES (?,?,'UAV_EVENT','COUNTERMEASURE',?,'COUNTERMEASURE_4CH','STOPPED',NULL,NULL,?,?,?,'replay',?)",
                authorization,event,device,org,district,key(),at);

        var events=mock(UavEventRepository.class);
        var advisory=mock(UavAdvisoryRepository.class);
        var access=mock(AccessControlService.class);
        var sms=mock(AutoSmsService.class);
        var voice=mock(AutoVoiceService.class);
        var handoffs=mock(HandoffRepository.class);
        var decisions=mock(NoCounterService.class);
        var eventRow=new EventRow(event,alarm,key(),"CONFIRMED",org,district,at.toInstant().atOffset(ZoneOffset.UTC),at.toInstant().atOffset(ZoneOffset.UTC),1,"replay");
        when(access.require(any())).thenReturn(new AccessDecision("reader",ScopeMode.ALL));
        when(events.find(eq(event),any())).thenReturn(eventRow);
        when(advisory.counterBlockReason(event)).thenReturn("");
        when(sms.currentRecipient(eventRow)).thenReturn(mock(RecipientSnapshot.class));
        when(decisions.status(eventRow)).thenReturn(mock(NoCounterDtos.Status.class));
        when(voice.departureSnapshot(event)).thenReturn(new DepartureObservation(event,"SMS","RECORDED","LEFT",now-2000,now-2000,now-1000));
        when(handoffs.enabledRecipients("UAV_PUNISHMENT")).thenReturn(List.of(new HandoffRepository.RecipientRow(key(),"recipient","UAV_PUNISHMENT")));
        service=new UavAdvisoryService(events,advisory,access,mock(AdvisorySmsPort.class),new ObjectMapper(),
                new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),mock(AuditService.class),sms,voice,
                mock(PilotDepartureWatch.class),handoffs,disposals,decisions);
    }

    @AfterEach void close() { if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE"); }

    @Test void departedBeforeAnyStartStillNeedsNoAutomaticHandoff() {
        // An enqueued start cancelled before dispatch, and a stop-only command, do not prove execution.
        String cancelled=command(15,"CANCELLED",null,null,authorization);
        jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",cancelled,authorization);
        jdbc.update("INSERT INTO disposal_authorization_event VALUES (?,?,'EXECUTE')",key(),authorization);
        command(0,"SUCCEEDED",now-100,now-50,authorization);
        jdbc.update("INSERT INTO countermeasure_start_attempt VALUES (?,?,?,?)",key(),key(),device,now);
        assertThat(disposals.counterExecutionEvidence(event)).isEqualTo(CounterExecutionEvidence.NONE);
        AutoHandoff result=unchangedRead();
        assertThat(result.status()).isEqualTo("NOT_REQUIRED");
        assertThat(result.reason()).contains("本次未执行反制");
    }

    @ParameterizedTest @CsvSource({"run,1","receipt,7"})
    void successfulStartThenEmergencyStopCannotBeDescribedAsNeverExecuted(String evidence,int daysAgo) {
        now-=daysAgo*86_400_000L;
        String on=command(15,"SUCCEEDED",now-500,now-400,authorization);
        jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",on,authorization);
        if("run".equals(evidence)) {
            jdbc.update("INSERT INTO disposal_device_run VALUES (?,?,?,?,NULL)",authorization,device,on,new Timestamp(now-400));
            // The persistent run remains useful even if the command summary is no longer conclusive.
            jdbc.update("UPDATE device_command SET status='FAILED',completed_at=NULL WHERE command_id=?",on);
        }
        // Emergency all-off uses its own authorization; it must not be promoted to normal completion.
        command(0,"SUCCEEDED",now-100,now-50,key());
        assertThat(disposals.punishmentCompletion(event)).isNull();
        assertThat(disposals.counterExecutionEvidence(event)).isEqualTo(CounterExecutionEvidence.CONFIRMED);
        AutoHandoff result=unchangedRead();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).contains("设备启动成功记录").doesNotContain("本次未执行反制");
    }

    @ParameterizedTest @ValueSource(strings={"attempt","issued","receipt","timed-out","missing-command","completion-tag"})
    void sentOrUncertainStartCannotBeErasedByDeparture(String evidence) {
        switch(evidence) {
            case "attempt" -> jdbc.update("INSERT INTO countermeasure_start_attempt VALUES (?,?,?,?)",key(),authorization,device,now-300);
            case "issued" -> command(15,"FAILED",now-300,null,authorization);
            case "receipt" -> {
                String command=command(15,"FAILED",null,null,authorization);
                jdbc.update("INSERT INTO command_receipt VALUES (?,?)",key(),command);
            }
            case "timed-out" -> command(15,"TIMED_OUT",null,null,authorization);
            case "missing-command" -> jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",key(),authorization);
            case "completion-tag" -> jdbc.update("UPDATE disposal_authorization SET status='COMPLETED' WHERE authorization_id=?",authorization);
            default -> throw new IllegalArgumentException(evidence);
        }
        assertThat(disposals.punishmentCompletion(event)).isNull();
        assertThat(disposals.counterExecutionEvidence(event)).isEqualTo(CounterExecutionEvidence.POSSIBLE);
        AutoHandoff result=unchangedRead();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).contains("执行结果待核查").doesNotContain("本次未执行反制");
    }

    @Test void normallyCompletedStartAndOffKeepTheExistingHandoffProgress() {
        String on=command(15,"SUCCEEDED",now-500,now-400,authorization);
        String off=command(0,"SUCCEEDED",now-100,now-50,authorization);
        jdbc.update("INSERT INTO disposal_device_run VALUES (?,?,?,?,?)",authorization,device,on,new Timestamp(now-400),off);
        jdbc.update("UPDATE disposal_authorization SET status='COMPLETED',execution_command_id=? WHERE authorization_id=?",off,authorization);
        assertThat(disposals.punishmentCompletion(event).triggerSource()).isEqualTo("COUNTERMEASURE_COMPLETED");
        AutoHandoff result=unchangedRead();
        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.reason()).isEqualTo("处置完成依据已具备，等待后台自动移送");
    }

    private AutoHandoff unchangedRead() {
        var before=snapshot();
        AutoHandoff result=readOnly.execute(status->service.overview(event).autoHandoff());
        AutoHandoff repeated=readOnly.execute(status->service.overview(event).autoHandoff());
        assertThat(repeated).isEqualTo(result);
        assertThat(snapshot()).isEqualTo(before);
        return result;
    }

    private Map<String,Object> snapshot() {
        var rows=new LinkedHashMap<String,Object>();
        for(String table:List.of("disposal_authorization","device_command","countermeasure_4ch_command","lingyun_control_command",
                "disposal_device_run","countermeasure_start_attempt","command_receipt","disposal_authorization_event"))
            rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return rows;
    }

    private String command(int mask,String status,Long issued,Long completed,String owner) {
        String id=key();
        jdbc.update("INSERT INTO device_command VALUES (?,?,?,'COUNTERMEASURE_4CH',?,?,?)",id,owner,device,status,issued,completed);
        jdbc.update("INSERT INTO countermeasure_4ch_command VALUES (?,?,'SET_MASK',?)",id,owner,mask);
        return id;
    }

    private void tables() {
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR(36) PRIMARY KEY,alarm_id VARCHAR(36),owner_org_id VARCHAR(36),district_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR(36) PRIMARY KEY,source_mode VARCHAR(16))");
        jdbc.execute("CREATE TABLE disposal_authorization(authorization_id VARCHAR(36) PRIMARY KEY,subject_id VARCHAR(36),subject_kind VARCHAR(32),action_type VARCHAR(32),device_id VARCHAR(36),channel VARCHAR(32),status VARCHAR(32),execution_command_id VARCHAR(36),chained_from_authorization_id VARCHAR(36),owner_org_id VARCHAR(36),district_id VARCHAR(36),requested_by VARCHAR(36),source_mode VARCHAR(16),requested_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE device_command(command_id VARCHAR(36) PRIMARY KEY,authorization_id VARCHAR(64),device_id VARCHAR(36),command_type VARCHAR(32),status VARCHAR(32),issued_at BIGINT,completed_at BIGINT)");
        jdbc.execute("CREATE TABLE countermeasure_4ch_command(command_id VARCHAR(36) PRIMARY KEY,authorization_id VARCHAR(64),action VARCHAR(32),mask INTEGER)");
        jdbc.execute("CREATE TABLE lingyun_control_command(command_id VARCHAR(36) PRIMARY KEY,authorization_id VARCHAR(64),operation_type INTEGER)");
        jdbc.execute("CREATE TABLE disposal_device_run(authorization_id VARCHAR(36) PRIMARY KEY,device_id VARCHAR(36),on_command_id VARCHAR(36),on_at TIMESTAMP WITH TIME ZONE,off_command_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE countermeasure_start_attempt(command_id VARCHAR(36) PRIMARY KEY,authorization_id VARCHAR(64),device_id VARCHAR(36),attempted_at BIGINT)");
        jdbc.execute("CREATE TABLE command_receipt(receipt_id VARCHAR(36) PRIMARY KEY,command_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE disposal_authorization_event(event_id VARCHAR(36) PRIMARY KEY,authorization_id VARCHAR(36),event_kind VARCHAR(32))");
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
