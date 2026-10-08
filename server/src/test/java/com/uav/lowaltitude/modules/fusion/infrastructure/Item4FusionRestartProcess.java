package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.fusion.application.FusionIngestWorker;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;

/** Separate test JVM: barriers only, no production fault switches or altered lease durations. */
public final class Item4FusionRestartProcess {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("ITEM4_RESTART_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[a-z0-9_]+\\?currentSchema=monitor_events_[a-f0-9]{32},public"))
            throw new IllegalArgumentException("Only independent item4 schemas are allowed");
        List<String> settings=new ArrayList<>(List.of("--spring.profiles.active=test","--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url="+url,"--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username="+System.getenv("POSTGRES_TEST_USER"),"--spring.datasource.password="+System.getenv("POSTGRES_TEST_PASSWORD"),
                "--spring.flyway.enabled=false","--app.dev-seed.enabled=false","--app.fusion.enabled=true"));
        for(String key:List.of("app.outbox.enabled","app.live-device.enabled","app.mqtt.enabled","app.rule-engine.enabled",
                "app.rule-engine.c04.enabled","app.automation-rules.enabled","app.fusion.replay.run-on-start","app.fusion.live-promotion.enabled",
                "app.advisory.auto-sms.enabled","app.advisory.auto-voice.enabled","app.disposal.expiry.enabled","app.disposal.receipt-sync.enabled","app.eo-edge.auto-track.enabled"))
            settings.add("--"+key+"=false");
        try(var context=new SpringApplicationBuilder(Application.class,DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
                .initializers(application->application.getBeanFactory().registerSingleton("isolatedRestartTestExclusions",
                        new org.springframework.boot.context.TypeExcludeFilter(){
                            @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                    org.springframework.core.type.classreading.MetadataReaderFactory factory){
                                var annotations=reader.getAnnotationMetadata();
                                return annotations.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
                                        || annotations.hasAnnotation("org.springframework.boot.test.context.TestComponent")
                                        || annotations.hasMetaAnnotation("org.springframework.boot.test.context.TestComponent");
                            }
                            @Override public boolean equals(Object other){return other!=null && getClass()==other.getClass();}
                            @Override public int hashCode(){return getClass().hashCode();}
                        }))
                .initializers(application->application.addBeanFactoryPostProcessor(factory->factory.addBeanPostProcessor(
                        new org.springframework.beans.factory.config.BeanPostProcessor(){
                            @Override public Object postProcessAfterInitialization(Object bean,String name){
                                boolean claim=bean instanceof FusionPipeline && args[0].equals("AFTER_CLAIM");
                                boolean commit=bean instanceof FusionInboxRepository && args[0].equals("BEFORE_COMMIT");
                                if(!claim && !commit)return bean;
                                var proxy=new org.springframework.aop.framework.ProxyFactory(bean);proxy.setProxyTargetClass(true);
                                proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{
                                    boolean hit=claim && call.getMethod().getName().equals("processFrame")
                                            && ((FusionInboxRepository.InboxRow)call.getArguments()[0]).inboxId().equals(args[2]);
                                    hit|=commit && call.getMethod().getName().equals("done") && call.getArguments()[0].equals(args[2]);
                                    if(hit){Files.writeString(Path.of(args[1]),Long.toString(ProcessHandle.current().pid()));Thread.sleep(60000);}
                                    return call.proceed();
                                });return proxy.getProxy();
                            }
                        })))
                .run(settings.toArray(String[]::new))){
            context.getBean(FusionIngestWorker.class).drain();
            if(!args[0].equals("RECOVER"))throw new IllegalStateException("Required crash barrier was not reached");
            Files.writeString(Path.of(args[1]),Long.toString(ProcessHandle.current().pid()));
        }
    }
}
