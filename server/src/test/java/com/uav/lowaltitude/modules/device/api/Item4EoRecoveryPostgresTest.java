package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.platform.worker.Item4HttpProcess;

@EnabledIfEnvironmentVariable(named="ITEM4_RESTART_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class Item4EoRecoveryPostgresTest extends EoManualTrackApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @RepeatedTest(3) void pendingBeginAndEndKeepOccupancyAndPauseAcrossCrashes() throws Exception {
        String target=insertTarget(true);
        var created=mapper.readTree(mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks",target)
                .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        String task=created.path("task_id").asText(),command=created.path("command_id").asText();
        long now=clock.nowMillis();
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);
        edges.updateCommand(command,"SENT","TIMED_OUT",now,"SIMULATED_RESULT_UNKNOWN","Independent crash fixture: device execution unknown");
        Path output=Path.of("target","item4-eo-restart",UUID.randomUUID().toString()).toAbsolutePath();
        String url;
        try(var connection=jdbc.getDataSource().getConnection()) {url=connection.getMetaData().getURL();}
        try(var first=Item4HttpProcess.start(url,output,"begin-unknown")) {
            var read=first.request(token,"GET","/api/v1/targets/"+target+"/eo-tracking-tasks",null);
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(read.body()).path("data").path("task_id").asText()).isEqualTo(task);
        }
        try(var restarted=Item4HttpProcess.start(url,output,"pause-and-end")) {
            var read=restarted.request(token,"GET","/api/v1/targets/"+target+"/eo-tracking-status",null);
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(read.body()).path("data").path("status").asText()).isEqualTo("LOST");
            var paused=restarted.request(token,"POST","/api/v1/targets/"+target+"/eo-tracking-pause","{}");
            assertThat(paused.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(paused.body()).path("data").path("auto_paused").asBoolean()).isTrue();
            assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
        }
        String end=String.valueOf(edges.task(task).get("end_command_id"));
        long commandCount=jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Long.class,binding.opsDeviceId());
        try(var restarted=Item4HttpProcess.start(url,output,"ending-recovered")) {
            var read=restarted.request(token,"GET","/api/v1/targets/"+target+"/eo-tracking-status",null);
            assertThat(read.statusCode()).isEqualTo(200);
            var data=mapper.readTree(read.body()).path("data");
            assertThat(data.path("status").asText()).isEqualTo("ENDING");
            assertThat(data.path("auto_paused").asBoolean()).isTrue();
            assertThat(edges.task(task).get("end_command_id")).isEqualTo(end);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Long.class,binding.opsDeviceId())).isEqualTo(commandCount);
            assertThat(edges.idleDeviceForMode(org,district,null,"replay",0L,clock.nowMillis())).isNull();
            Files.writeString(output.resolve("evidence.json"),mapper.writeValueAsString(Map.of("task_id",task,"begin_command",command,
                    "end_command",end,"status",data.path("status").asText(),"auto_paused",true,"command_count",commandCount)));
        }
    }
}
