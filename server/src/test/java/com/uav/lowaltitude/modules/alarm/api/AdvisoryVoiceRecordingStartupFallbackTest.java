package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;

/** 部署端仍用启动参数配置录音时：后台选用的上传录音优先，停止使用后回到启动参数录音。 */
@SpringBootTest(properties = {"app.advisory.auto-voice.enabled=true", "app.advisory.auto-voice.initial-delay-millis=3600000",
        "app.advisory.auto-sms.initial-delay-millis=3600000", "app.outbox.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvisoryVoiceRecordingStartupFallbackTest {
    private static final String BASE = "/api/v1/advisory-voice-recordings";
    private static final String STARTUP_ID = "startup-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Path STARTUP_FILE = startupFile();

    @DynamicPropertySource
    static void startupRecording(DynamicPropertyRegistry p) {
        p.add("app.advisory.auto-voice.recording-id", () -> STARTUP_ID);
        p.add("app.advisory.auto-voice.recording-name", () -> "启动参数劝离录音");
        p.add("app.advisory.auto-voice.recording-path", STARTUP_FILE::toString);
        p.add("app.advisory.auto-voice.recording-transcript", () -> "部署时配置的劝离录音文稿");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdvisoryVoiceRecording recordings;

    @AfterEach
    void reset() {
        jdbc.update("UPDATE advisory_voice_recording_setting SET active_recording_id=NULL,updated_by=NULL,updated_at=NULL,version=0 WHERE setting_id='global'");
    }

    @Test
    void startupRecordingIsTheFallbackAndAnUploadedSelectionTakesOver() throws Exception {
        String token = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
        mvc.perform(get(BASE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.source").value("STARTUP_CONFIG"))
                .andExpect(jsonPath("$.data.current.available").value(true))
                .andExpect(jsonPath("$.data.current.name").value("启动参数劝离录音"))
                .andExpect(jsonPath("$.data.startup_recording_name").value("启动参数劝离录音"));
        assertThat(recordings.current().id()).isEqualTo(STARTUP_ID);

        byte[] audio = AdvisoryVoiceRecordingApiTest.wav(16000, 1, 6000 + ThreadLocalRandom.current().nextInt(4000));
        String id = json.readTree(mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "upload.wav", "audio/wav", audio))
                        .param("name", "后台上传劝离录音").param("transcript", "后台上传的文稿")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("recording_id").asText();
        assertThat(recordings.current().id()).as("只上传不选用，不影响启动参数录音").isEqualTo(STARTUP_ID);

        mvc.perform(patch(BASE + "/{id}/active", id).header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true,\"expected_version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.current.name").value("后台上传劝离录音"))
                .andExpect(jsonPath("$.data.startup_recording_name").value("启动参数劝离录音"));
        assertThat(recordings.current().id()).isEqualTo(id);
        assertThat(recordings.current().sha256()).isEqualTo(AdvisoryVoiceRecording.sha256(audio));

        mvc.perform(patch(BASE + "/{id}/active", id).header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"expected_version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.source").value("STARTUP_CONFIG"));
        assertThat(recordings.current().id()).isEqualTo(STARTUP_ID);
    }

    private static Path startupFile() {
        try {
            Path file = Files.createTempFile("advisory-startup-fallback-", ".wav");
            file.toFile().deleteOnExit();
            Files.write(file, AdvisoryVoiceRecordingApiTest.wav(8000, 1, 4000 + ThreadLocalRandom.current().nextInt(4000)));
            return file;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
