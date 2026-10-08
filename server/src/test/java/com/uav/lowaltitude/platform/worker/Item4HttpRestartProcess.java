package com.uav.lowaltitude.platform.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Cold-start HTTP server for disposable item-four persistence tests only. */
public final class Item4HttpRestartProcess {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("ITEM4_HTTP_DB_URL");
        if (url == null || !(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/maintenance_flow_verify_item4_[a-z0-9_]+\\?currentSchema=item4_[a-f0-9]{32},public")
                || url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[a-z0-9_]+\\?currentSchema=monitor_events_[a-f0-9]{32},public")))
            throw new IllegalArgumentException("Only isolated item4 schemas are permitted");
        List<String> settings = new ArrayList<>(List.of("--spring.profiles.active=test", "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + url, "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username=" + System.getenv("POSTGRES_TEST_USER"),
                "--spring.datasource.password=" + System.getenv("POSTGRES_TEST_PASSWORD"),
                "--spring.flyway.enabled=false", "--app.dev-seed.enabled=false", "--app.handoff.channel=none"));
        for (String key : List.of("app.mqtt.enabled", "app.outbox.enabled", "app.fusion.enabled", "app.rule-engine.enabled",
                "app.rule-engine.c04.enabled", "app.automation-rules.enabled", "app.live-device.enabled", "app.fusion.replay.run-on-start",
                "app.rule-engine.replay.run-on-start", "app.advisory.auto-sms.enabled", "app.advisory.auto-voice.enabled",
                "app.disposal.receipt-sync.enabled", "app.disposal.expiry.enabled", "app.eo-edge.auto-track.enabled"))
            settings.add("--" + key + "=false");
        try (var context = new SpringApplicationBuilder(Application.class, DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
                .initializers(application -> application.getBeanFactory().registerSingleton("item4TestExclusions",
                        new org.springframework.boot.context.TypeExcludeFilter() {
                            @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                    org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                                var annotations = reader.getAnnotationMetadata();
                                return annotations.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
                                        || annotations.hasAnnotation("org.springframework.boot.test.context.TestComponent")
                                        || annotations.hasMetaAnnotation("org.springframework.boot.test.context.TestComponent");
                            }
                            @Override public boolean equals(Object other) { return other != null && getClass() == other.getClass(); }
                            @Override public int hashCode() { return getClass().hashCode(); }
                        }))
                .run(settings.toArray(String[]::new))) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            Files.writeString(Path.of(args[0]), ProcessHandle.current().pid() + ":" + port);
            // The parent asserts the reached HTTP state before killing this exact child.
            Thread.sleep(180_000);
        }
    }
}
