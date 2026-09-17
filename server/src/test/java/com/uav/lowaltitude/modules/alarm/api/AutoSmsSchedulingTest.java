package com.uav.lowaltitude.modules.alarm.api;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

/** 真实调度线程运行：没有页面、没有业务HTTP请求也会完成通知。 */
@SpringBootTest(properties={"app.advisory.auto-sms.enabled=true","app.advisory.auto-sms.initial-delay-millis=50","app.advisory.auto-sms.poll-millis=50","app.outbox.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:auto_sms_scheduler;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AutoSmsSchedulingTest {
    @Autowired JdbcTemplate jdbc;
    private String eventId,session,userId,role,targetId,pilotPlanId;
    private static final String ORG="seed-stage3-org", DISTRICT="seed-stage3-district";
    @BeforeEach void fixture() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        role="ROLE-ADV-"+suffix;userId=UUID.randomUUID().toString();session=UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)",role,role);
        for(String p:List.of("alarm:read","alarm:verify","handoff:create","disposal:request","disposal:read","disposal:execute","disposal:approve"))
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,?,false,current_timestamp)",role,p,p.endsWith(":read")?"READ":"OP");
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",userId,"adv-"+suffix,"核查员",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",userId,ORG,DISTRICT);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",session,userId,System.currentTimeMillis()+3600000);
        String alarm=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();eventId=UUID.randomUUID().toString();
        Timestamp at=Timestamp.from(Instant.now());
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,'mock',?,?,0)",source,"ADV-"+suffix,"劝离测试源",at,at);
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,null,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",alarm,source,"ADV-"+suffix,at,at,ORG,DISTRICT,at);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,0)",eventId,alarm,ORG,DISTRICT,at,at);
        targetId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'UAV','mock',?,?,?, ?,0)",targetId,"AUTO-"+suffix,ORG,DISTRICT,at,at);
        jdbc.update("INSERT INTO target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) VALUES(?,?,?,?,?,CAST('[]' AS JSON))",targetId,at,at,at,at);
        jdbc.update("UPDATE alarm SET target_id=? WHERE alarm_id=?",targetId,alarm);
        jdbc.update("UPDATE uav_event SET version=1 WHERE event_id=?",eventId);
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) VALUES(?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','测试人工确认现场违规',?,?)",UUID.randomUUID().toString(),eventId,userId,at);
        pilotPlanId=DirectoryAdvisoryFixture.create(jdbc,ORG,DISTRICT);
        DirectoryAdvisoryFixture.evaluation(jdbc,eventId,targetId,pilotPlanId,ORG,DISTRICT,Instant.now().minusSeconds(30));
    }


    @Test void scheduledWorkerSendsWhileNoBrowserIsOpen()throws Exception {
        jdbc.update("delete from app_session where user_id=?",userId);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            Integer count=jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=? and trigger_mode='AUTO'",Integer.class,eventId);
            if(count!=null&&count==1)break;
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=? and trigger_mode='AUTO'",Integer.class,eventId)).isEqualTo(1);
        Thread.sleep(150);
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor_id from uav_event_advisory where event_id=?",String.class,eventId)).isNull();
    }
}
