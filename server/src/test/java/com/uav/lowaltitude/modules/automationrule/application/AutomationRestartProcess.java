package com.uav.lowaltitude.modules.automationrule.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.worker.OutboxWorker;

/** Separate-JVM fixture. Uses production worker/outbox/MQTT beans; no mocked transport or rule decisions. */
public final class AutomationRestartProcess {
    public static void main(String[] args) throws Exception {
        if ("CRASH_RECEIPT".equals(args[0]) && args.length != 4)
            throw new IllegalArgumentException("Receipt barrier requires the exact current command ID");
        String url = System.getenv("AUTOMATION_RESTART_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+\\?currentSchema=monitor_events_[a-f0-9]{32},public"))
            throw new IllegalArgumentException("Only disposable automation schemas are permitted");
        List<String> settings = new ArrayList<>(List.of("--spring.profiles.active=test","--spring.devtools.restart.enabled=false","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url="+url,"--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username="+System.getenv("POSTGRES_TEST_USER"),
                "--spring.datasource.password="+System.getenv("POSTGRES_TEST_PASSWORD"),
                "--spring.flyway.enabled=false","--app.dev-seed.enabled=false","--app.automation-rules.enabled=true",
                "--app.automation-rules.fact-max-age-ms=300000","--app.mqtt.enabled=true","--app.outbox.enabled=true",
                "--app.lingyun-control.command-timeout-millis=120000","--app.handoff.channel=none"));
        for(String key:List.of("app.fusion.enabled","app.rule-engine.enabled","app.live-device.enabled","app.advisory.auto-sms.enabled",
                "app.advisory.auto-voice.enabled","app.disposal.receipt-sync.enabled","app.disposal.expiry.enabled","app.eo-edge.auto-track.enabled"))
            settings.add("--"+key+"=false");
        try(var context = new SpringApplicationBuilder(Application.class,DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
                .initializers(application -> application.getBeanFactory().registerSingleton("isolatedRestartTestExclusions",
                        new org.springframework.boot.context.TypeExcludeFilter() {
                            @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                    org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                                var annotations=reader.getAnnotationMetadata();
                                return annotations.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
                                        || annotations.hasAnnotation("org.springframework.boot.test.context.TestComponent")
                                        || annotations.hasMetaAnnotation("org.springframework.boot.test.context.TestComponent");
                            }
                            @Override public boolean equals(Object other) { return other!=null && getClass()==other.getClass(); }
                            @Override public int hashCode() { return getClass().hashCode(); }
                        }))
                .initializers(application -> application.addBeanFactoryPostProcessor(factory -> {
                    if (!args[0].startsWith("CRASH_")) return;
                    factory.addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                        @Override public Object postProcessAfterInitialization(Object bean,String name) {
                            boolean publish = bean instanceof MqttSessionSupervisor && List.of("CRASH_PUBLISH","CRASH_BEFORE_PUBLISH").contains(args[0]);
                            boolean complete = bean instanceof com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository && "CRASH_COMPLETE".equals(args[0]);
                            boolean receipt = bean instanceof com.uav.lowaltitude.modules.disposal.application.DisposalReceiptSync && "CRASH_RECEIPT".equals(args[0]);
                            if (!publish && !complete && !receipt) return bean;
                            var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                            proxy.setProxyTargetClass(true);
                            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                                boolean hit = publish && "publish".equals(invocation.getMethod().getName())
                                        || complete && "completeOutbox".equals(invocation.getMethod().getName())
                                        || receipt && "syncByCommand".equals(invocation.getMethod().getName())
                                                && args[3].equals(invocation.getArguments()[0]);
                                if (!hit) return invocation.proceed();
                                Object result = publish && !"CRASH_BEFORE_PUBLISH".equals(args[0]) ? invocation.proceed() : null;
                                Files.writeString(Path.of(args[1]),Long.toString(ProcessHandle.current().pid()));
                                Thread.sleep(60000); // Real wire publish occurred; parent kills JVM before transaction/outbox completion.
                                return result;
                            });
                            return proxy.getProxy();
                        }
                    });
                }))
                .run(settings.toArray(String[]::new))) {
            var worker=context.getBean(AutomationRuntimeWorker.class);
            var mqtt=context.getBean(MqttSessionSupervisor.class);
            var outbox=context.getBean(OutboxWorker.class);
            var jdbc=context.getBean(JdbcTemplate.class);
            if ("CRASH_RECEIPT".equals(args[0])) {
                // Only the existing ordinary command is dispatched. Its AFTER_COMMIT callback
                // reaches the barrier above after the real MQTT receipt is durable.
                long dispatchDeadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                do {
                    mqtt.reconcile(); outbox.poll();
                    if (jdbc.queryForObject("select issued_at from device_command where command_id=?", Long.class, args[3]) != null) break;
                    Thread.sleep(100);
                } while (System.nanoTime() < dispatchDeadline);
                Files.writeString(Path.of(args[1] + ".subscribed"), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(120000);
                throw new IllegalStateException("Parent did not terminate at receipt barrier");
            }
            mqtt.reconcile(); worker.poll();
            if (!"FAIL".equals(args[0])) {
                long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
                do {
                    mqtt.reconcile(); outbox.poll();
                    Integer sent=jdbc.queryForObject("select count(*) from device_command where device_id=? and issued_at is not null",Integer.class,args[2]);
                    if(sent != null && sent>0) break;
                    Thread.sleep(100);
                } while(System.nanoTime()<deadline);
                worker.poll(); outbox.poll();
            }
            Files.writeString(Path.of(args[1]),Long.toString(ProcessHandle.current().pid()));
            if(!"REPLAY".equals(args[0])) Thread.sleep(60000); // Parent forcibly terminates the actual JVM after durable assertions.
        }
    }
}
