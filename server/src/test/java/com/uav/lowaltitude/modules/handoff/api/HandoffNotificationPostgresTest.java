package com.uav.lowaltitude.modules.handoff.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/handoff_notice_verify_[a-z0-9_]+")
class HandoffNotificationPostgresTest extends HandoffNotificationApiTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
  String url=System.getenv("POSTGRES_TEST_URL");
  if(url==null||!url.matches("jdbc:postgresql://[^/]+/handoff_notice_verify_[a-z0-9_]+"))throw new IllegalArgumentException("只允许隔离处罚通知测试库");
  registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));registry.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
 }
 @Test void concurrentSubmissionsOnlyDispatchOnce() throws Exception {
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{
   var a=pool.submit(()->{ready.countDown();start.await();return send(1,java.util.UUID.randomUUID().toString()).andReturn().getResponse().getStatus();});
   var b=pool.submit(()->{ready.countDown();start.await();return send(1,java.util.UUID.randomUUID().toString()).andReturn().getResponse().getStatus();});
   if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("并发请求未就绪");start.countDown();
   assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Long.class,id)).isEqualTo(2);verify(channel,times(1)).deliver(any());
  }finally{start.countDown();pool.shutdownNow();}
 }
}
