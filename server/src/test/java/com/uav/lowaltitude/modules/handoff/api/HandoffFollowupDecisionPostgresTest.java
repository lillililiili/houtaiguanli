package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class HandoffFollowupDecisionPostgresTest extends HandoffFollowupDecisionApiTest {
 private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){DATABASE.springProperties(registry);}
 @AfterAll static void close(){DATABASE.close();}
 @Test void committedDecisionEmitsBothRealtimeTopics() throws Exception {
  try(var listener=jdbc.getDataSource().getConnection()){
   listener.setAutoCommit(true);try(var command=listener.createStatement()){command.execute("LISTEN app_data_change");}
   decline("实时刷新核对",0).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
   var messages=listener.unwrap(org.postgresql.PGConnection.class).getNotifications(2000);
   assertThat(java.util.Arrays.stream(messages).map(org.postgresql.PGNotification::getParameter).toList()).contains("punishment","alarm");
  }
 }
 @Test void notifyAndDeclineRaceHasOneDecisionAndAtMostOneExternalCall() throws Exception {
  configureRecipient();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{
   var a=pool.submit(()->{start.await();return notifyRequest().andReturn().getResponse().getStatus();});
   var b=pool.submit(()->{start.await();return decline("并发决定",0).andReturn().getResponse().getStatus();});
   start.countDown();assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
   String state=jdbc.queryForObject("SELECT state FROM handoff_notification_decision WHERE handoff_id=?",String.class,id);
   assertThat(jdbc.queryForObject("SELECT version FROM handoff_notification_decision WHERE handoff_id=?",Long.class,id)).isEqualTo(1);
   verify(channel,times("NOTIFY".equals(state)?1:0)).deliver(any());
  }finally{start.countDown();pool.shutdownNow();}
 }
 @Test void concurrentAutomaticScansCreateOneTask()throws Exception{
  clearHandoff();recordLeft(System.currentTimeMillis());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{var a=pool.submit(()->{start.await();followups.create(event);return true;});var b=pool.submit(()->{start.await();followups.create(event);return true;});start.countDown();a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);findCreated();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Long.class,event)).isEqualTo(1);verify(channel,never()).deliver(any());}finally{start.countDown();pool.shutdownNow();}
 }
}
