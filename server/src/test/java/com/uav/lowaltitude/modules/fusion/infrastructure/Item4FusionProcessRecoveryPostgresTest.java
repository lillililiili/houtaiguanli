package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

@SpringBootTest
@ActiveProfiles({"test","postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="ITEM4_RESTART_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[a-z0-9_]+")
class Item4FusionProcessRecoveryPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){DATABASE.springProperties(registry);}
    @AfterAll static void closeDatabase(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @RepeatedTest(3) void killedAfterClaimRecoversOneObservation() throws Exception {verify("AFTER_CLAIM");}
    @RepeatedTest(3) void killedBeforeCommitRollsBackWholeFrameAndRecoversOnce() throws Exception {verify("BEFORE_COMMIT");}

    private void verify(String cut) throws Exception {
        String id=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),code="ITEM4-"+source;
        long now=System.currentTimeMillis();
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version) values(?,?,?,true,'replay','RADAR',current_timestamp,current_timestamp,0)",source,code,"isolated crash input");
        String body=mapper.writeValueAsString(Map.of("dataset_id",id,"record_no",1,"frame",Map.of("source_code",code,"observed_at",now,
                "items",List.of(Map.of("external_target_id",id,"external_track_id",id,"lon",117.1+Math.random()*.5,"lat",36.8,"position_accuracy_m",5,"alt_amsl_m",100,"class_code","UAV","class_confidence",.99)))));
        jdbc.update("insert into inbox_message(inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts) values(?,?,?,?,?,?,cast(? as json),'RECEIVED',0)",
                id,"replay:"+code+":"+id,id,now,source,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))),body);
        Path output=Path.of("target","item4-fusion-process",cut+"-"+id);Files.createDirectories(output);
        run(cut,id,output);
        assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?",String.class,id)).isEqualTo("PROCESSING");
        assertThat(count(id)).as("Killed transaction leaves no partial observation").isZero();
        long lease=jdbc.queryForObject("select lease_until from inbox_message where inbox_id=?",Long.class,id);
        await().atMost(Duration.ofSeconds(40)).until(()->System.currentTimeMillis()>lease);
        run("RECOVER",id,output);
        assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?",String.class,id)).isEqualTo("DONE");
        assertThat(count(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select fusion_attempts from inbox_message where inbox_id=?",Integer.class,id)).isEqualTo(2);
        var observation=jdbc.queryForMap("select observation_id,source_id,external_target_id from source_observation where inbox_id=?",id);
        assertThat(observation).containsEntry("source_id",source).containsEntry("external_target_id",id);
        run("RECOVER",id,output);
        assertThat(count(id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select observation_id,source_id,external_target_id from source_observation where inbox_id=?",id)).isEqualTo(observation);
        mapper.writeValue(output.resolve("evidence.json").toFile(),Map.of("cut",cut,"inbox",id,"status","DONE","observations",1,"attempts",2,"actual_lease_expiry",lease));
    }
    private int count(String id){return jdbc.queryForObject("select count(*) from source_observation where inbox_id=?",Integer.class,id);}
    private void run(String mode,String id,Path output) throws Exception {
        String suffix=mode+"-"+UUID.randomUUID();Path ready=output.resolve(suffix+".ready"),log=output.resolve(suffix+".log"),classpath=output.resolve(suffix+".jar");
        var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION,"1.0");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH,java.util.Arrays.stream(cp.split(java.io.File.pathSeparator)).map(value->Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        try(var archive=new java.util.jar.JarOutputStream(Files.newOutputStream(classpath),manifest)){}
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dspring.devtools.restart.enabled=false","-Dfile.encoding=UTF-8",
                "-cp",classpath.toString(),Item4FusionRestartProcess.class.getName(),mode,ready.toString(),id).redirectErrorStream(true).redirectOutput(log.toFile());
        try(var connection=jdbc.getDataSource().getConnection()){builder.environment().put("ITEM4_RESTART_DB_URL",connection.getMetaData().getURL());}
        Process child=builder.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(()->Files.exists(ready)||!child.isAlive());
            assertThat(Files.exists(ready)).as("Child stage marker; inspect %s",log).isTrue();
            assertThat(Long.parseLong(Files.readString(ready))).isEqualTo(child.pid());
            if(mode.equals("RECOVER")){assertThat(child.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}
        } finally {if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();}}
    }
}
