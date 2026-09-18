package com.uav.lowaltitude.modules.directory.api;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/maintenance_notice_verify_[a-z0-9_]+")
class DeviceMaintenanceNoticePostgresTest extends DeviceMaintenanceNoticeApiTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("POSTGRES_TEST_URL");
  if(url==null||!url.matches("jdbc:postgresql://[^/]+/maintenance_notice_verify_[a-z0-9_]+"))throw new IllegalArgumentException("只允许隔离运维通知测试库");
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
 }

 @Test
 @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentRequestsForSameAttemptDispatchOnlyOnce()throws Exception {
  create();advance();String target=taskId;
  var outcomes=concurrent(target,target,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString());
  assertThat(outcomes).containsExactlyInAnyOrder(200,409);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_notice_attempt WHERE task_id=?",Long.class,target)).isEqualTo(2);
  verify(channel,times(2)).deliver(any());
 }
 @Test
 @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void sameIdempotencyKeyAcrossDevicesIsClaimedBeforeEitherSecondDispatch()throws Exception {
  create();String first=taskId,firstDevice=device;
  device=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE deleted_at IS NULL AND device_id<>? ORDER BY device_id FETCH FIRST 1 ROW ONLY",String.class,firstDevice);
  observe(true,now);create();String second=taskId;advance();
  var rows=java.util.List.of(new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow(firstDevice,"测试设备一",true,java.math.BigDecimal.ONE,"OFFLINE","BAD",now,now,true,true,java.util.List.of()),
      new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow(device,"测试设备二",true,java.math.BigDecimal.ONE,"OFFLINE","BAD",now,now,true,true,java.util.List.of()));
  doReturn(new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.Check(plan,"AUTO_DEVICE_ABNORMAL","当前设备检查",now,java.math.BigDecimal.TEN,true,0,rows,false)).when(checks).read(plan);
  String key=java.util.UUID.randomUUID().toString();var outcomes=concurrent(first,second,key,key);
  assertThat(outcomes).containsExactlyInAnyOrder(200,409);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_notice_attempt WHERE task_id IN (?,?)",Long.class,first,second)).isEqualTo(3);
  verify(channel,times(3)).deliver(any());
 }
 private java.util.List<Integer> concurrent(String first,String second,String firstKey,String secondKey)throws Exception {
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
  try {
   var a=pool.submit(()->{ready.countDown();start.await();return mvc.perform(write(post("/api/v1/device-maintenance-tasks/"+first+"/notifications/resend"),java.util.Map.of("expected_attempt_no",1),firstKey)).andReturn().getResponse().getStatus();});
   var b=pool.submit(()->{ready.countDown();start.await();return mvc.perform(write(post("/api/v1/device-maintenance-tasks/"+second+"/notifications/resend"),java.util.Map.of("expected_attempt_no",1),secondKey)).andReturn().getResponse().getStatus();});
   if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("并发请求未就绪");start.countDown();
   return java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
  }finally{start.countDown();pool.shutdownNow();}
 }
 @Test
 void upgradingLegacyNoticePreservesSnapshotAndDoesNotInventDeliveryTimes()throws Exception {
  String schema="notice_upgrade_"+java.util.UUID.randomUUID().toString().replace("-","");
  try(var connection=jdbc.getDataSource().getConnection();var sql=connection.createStatement()){
   sql.execute("CREATE SCHEMA "+schema);connection.setSchema(schema);
   try {
    sql.execute("CREATE TABLE app_user(user_id VARCHAR(36) PRIMARY KEY)");sql.execute("INSERT INTO app_user VALUES('legacy-user')");
    sql.execute("CREATE TABLE notification_setting(setting_id VARCHAR(36) PRIMARY KEY)");
    sql.execute("CREATE TABLE ops_device_maintenance_task(task_id VARCHAR(36) PRIMARY KEY,reported_by VARCHAR(36),reported_by_name VARCHAR(128),reported_at BIGINT,recipient_snapshot TEXT,notification_setting_id VARCHAR(36),notification_delivery_status VARCHAR(32),notification_receipt_status VARCHAR(32),notification_blocked_reason VARCHAR(256))");
    sql.execute("INSERT INTO ops_device_maintenance_task VALUES('legacy-task','legacy-user','历史报告人',123456,'历史原始快照',NULL,'DELIVERED','PENDING',NULL)");
    sql.execute("INSERT INTO ops_device_maintenance_task VALUES('no-notice-task','legacy-user','历史报告人',789000,NULL,NULL,NULL,NULL,NULL)");
    ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V202609170001__device_maintenance_notice_attempts.sql"));
    ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V202609170002__preserve_unknown_legacy_maintenance_notice.sql"));
    try(var result=sql.executeQuery("SELECT * FROM ops_device_maintenance_notice_attempt")){
     assertThat(result.next()).isTrue();
     assertThat(result.getString("recipient_snapshot")).isEqualTo("历史原始快照");
     assertThat(result.getLong("requested_at")).isEqualTo(123456);
     assertThat(result.getString("delivery_status")).isEqualTo("DELIVERED");
     assertThat(result.getObject("delivered_at")).isNull();
     assertThat(result.getObject("acknowledged_at")).isNull();
     assertThat(result.getBoolean("historical")).isTrue();
     assertThat(result.next()).isFalse();
    }
   }finally{connection.setSchema("public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
}
