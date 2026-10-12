package com.uav.lowaltitude.modules.directory.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceRecoveryJob;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceWorkflowService;

/** Separate JVM used only against a validated disposable PostgreSQL schema; never an acceptance service. */
public final class DeviceMaintenanceRestartWorker {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("MAINTENANCE_RESTART_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+\\?currentSchema=maintenance_case_[a-f0-9]{32},public"))
            throw new IllegalArgumentException("Only isolated maintenance_case test schemas are permitted");
        var settings=new ArrayList<>(List.of("--spring.profiles.active=test","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url="+url,"--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username="+System.getenv("POSTGRES_TEST_USER"),
                "--spring.datasource.password="+System.getenv("POSTGRES_TEST_PASSWORD"),
                "--spring.flyway.enabled=false","--app.dev-seed.enabled=false","--app.bootstrap-admin.enabled=false",
                "--app.super-admin.account="+args[3],
                "--app.handoff.channel=none","--app.device-maintenance.recovery.enabled=true"));
        for(String key:List.of("app.mqtt.enabled","app.outbox.enabled","app.fusion.enabled","app.rule-engine.enabled",
                "app.rule-engine.c04.enabled","app.automation-rules.enabled","app.live-device.enabled","app.fusion.replay.run-on-start",
                "app.rule-engine.replay.run-on-start","app.advisory.auto-sms.enabled","app.advisory.auto-voice.enabled",
                "app.disposal.receipt-sync.enabled","app.disposal.expiry.enabled","app.eo-edge.auto-track.enabled",
                "app.flight-device-check.schedule.enabled","app.flight.status-advance.enabled"))settings.add("--"+key+"=false");
        try(var context=new SpringApplicationBuilder(Application.class,DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
                .initializers(application->application.getBeanFactory().registerSingleton("maintenanceRestartTestExclusions",
                        new org.springframework.boot.context.TypeExcludeFilter(){
                            @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                    org.springframework.core.type.classreading.MetadataReaderFactory factory){
                                var annotations=reader.getAnnotationMetadata();
                                return annotations.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
                                        ||annotations.hasAnnotation("org.springframework.boot.test.context.TestComponent")
                                        ||annotations.hasMetaAnnotation("org.springframework.boot.test.context.TestComponent");
                            }
                            @Override public boolean equals(Object other){return other!=null&&getClass()==other.getClass();}
                            @Override public int hashCode(){return getClass().hashCode();}
                        }))
                .run(settings.toArray(String[]::new))) {
            if(context.getEnvironment().getProperty("app.dev-seed.enabled",Boolean.class,true))
                throw new IllegalStateException("Cold-start worker must not seed");
            // The production job is intentionally excluded by the test profile. Use its actual entry point
            // with the newly created, transaction-proxied workflow service instead of altering production profiles.
            var job=new DeviceMaintenanceRecoveryJob(context.getBean(DeviceMaintenanceWorkflowService.class));
            var db=context.getBean(JdbcTemplate.class);
            // Invoke the real scheduled entry point with its real 5s cadence; unrelated jobs stay disabled.
            for(int attempt=0;attempt<8;attempt++) {
                job.tick();
                var task=db.queryForMap("SELECT workflow_state,recovery_result,recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",args[1]);
                if(args[2].equals(task.get("workflow_state"))&&task.get("recovery_result")!=null) {
                    Files.writeString(Path.of(args[0]),ProcessHandle.current().pid()+":"+task.get("workflow_state")+":"+task.get("recovery_result"));
                    return;
                }
                Thread.sleep(5000);
            }
            throw new IllegalStateException("Persisted case did not reach "+args[2]);
        }
    }
}
