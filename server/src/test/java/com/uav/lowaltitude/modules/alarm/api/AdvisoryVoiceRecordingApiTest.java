package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Source;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsService;
import com.uav.lowaltitude.modules.alarm.application.AutoVoiceService;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;

/**
 * BLOCK-04：电话通知录音在后台上传、选用后，自动电话改播选用的录音；没有选用时仍按启动参数，两者都没有时电话这一步跳过。
 * 本类不配置启动参数录音（与没有技术人员改启动设置的现场一致）；启动参数与上传录音的先后关系用同一仓储和存储另建实例验证。
 */
@SpringBootTest(properties = {"app.advisory.auto-sms.enabled=true", "app.advisory.auto-voice.enabled=true",
        "app.advisory.auto-voice.initial-delay-millis=3600000", "app.advisory.auto-sms.initial-delay-millis=3600000",
        "app.outbox.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvisoryVoiceRecordingApiTest {
    private static final String BASE = "/api/v1/advisory-voice-recordings";
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdvisoryVoiceRecording recordings;
    @Autowired AdvisoryVoiceRecordingRepository repository;
    @Autowired ObjectStoragePort storage;
    @Autowired AutoSmsService sms;
    @Autowired AutoVoiceService voice;
    @MockBean PilotDepartureWatch departure;

    private final List<Path> temporary = new ArrayList<>();

    @BeforeEach
    void clean() throws IOException {
        org.mockito.Mockito.lenient().when(departure.assess(anyString(), anyLong(), anyLong()))
                .thenReturn(PilotDepartureWatch.Presence.STILL_PRESENT);
        jdbc.update("UPDATE advisory_voice_recording_setting SET active_recording_id=NULL,updated_by=NULL,updated_at=NULL,version=0 WHERE setting_id='global'");
        jdbc.update("DELETE FROM advisory_voice_recording");
        Path root = storage.resolve("advisory-voice-recordings");
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @AfterEach
    void removeTemporaryFiles() throws IOException {
        for (Path path : temporary) Files.deleteIfExists(path);
        temporary.clear();
    }

    @Test
    void withoutAnyRecordingTheCatalogSaysTheCallStepIsSkipped() throws Exception {
        String token = login();
        mvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.active_recording_id").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.current.source").value("NONE"))
                .andExpect(jsonPath("$.data.current.available").value(false))
                .andExpect(jsonPath("$.data.current.message").value(org.hamcrest.Matchers.containsString("电话这一步直接跳过")))
                .andExpect(jsonPath("$.data.startup_recording_name").doesNotExist())
                .andExpect(jsonPath("$.data.voice_enabled").value(true))
                .andExpect(jsonPath("$.data.can_manage").value(true))
                .andExpect(jsonPath("$.data.max_size_bytes").value(AdvisoryVoiceRecording.MAX_BYTES))
                .andExpect(jsonPath("$.data.accepted_formats[0]").value("WAV"));
        assertThat(recordings.current()).isNull();
    }

    @Test
    void uploadStoresVerifiedWaveAndAuditsWithoutChangingTheActiveRecording() throws Exception {
        String token = login();
        int rate = ThreadLocalRandom.current().nextBoolean() ? 8000 : 16000;
        int channels = ThreadLocalRandom.current().nextInt(1, 3);
        int frames = rate + ThreadLocalRandom.current().nextInt(1, rate);
        byte[] audio = wav(rate, channels, frames);
        String name = "劝离录音-" + suffix();

        JsonNode row = data(upload(token, "C:\\fakepath\\劝离提醒.wav", audio, " " + name + " ", "您已进入管控区域，请立即降落。", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.original_name").value("劝离提醒.wav"))
                .andExpect(jsonPath("$.data.content_type").value("audio/wav"))
                .andExpect(jsonPath("$.data.size_bytes").value(audio.length))
                .andExpect(jsonPath("$.data.sha256").value(AdvisoryVoiceRecording.sha256(audio)))
                .andExpect(jsonPath("$.data.duration_millis").value(Math.round(frames * 1000d / rate)))
                .andExpect(jsonPath("$.data.sample_rate").value(rate))
                .andExpect(jsonPath("$.data.channels").value(channels))
                .andExpect(jsonPath("$.data.uploaded_by_name").value("超级管理员"))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.used").value(false))
                .andExpect(jsonPath("$.data.can_delete").value(true)));
        String id = row.path("recording_id").asText();

        String objectKey = jdbc.queryForObject("SELECT object_key FROM advisory_voice_recording WHERE recording_id=?", String.class, id);
        assertThat(objectKey).startsWith("advisory-voice-recordings/").endsWith("/" + id + "/recording.wav");
        assertThat(Files.readAllBytes(storage.resolve(objectKey))).isEqualTo(audio);
        // 上传不等于选用：电话仍然没有录音可播。
        assertThat(recordings.current()).isNull();
        assertThat(auditCount(id, "advisory_voice_recording_uploaded")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT module_code FROM audit_log WHERE object_id=? AND action='advisory_voice_recording_uploaded'", String.class, id))
                .isEqualTo("interfaces");

        mvc.perform(get(BASE + "/{id}/content", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline; filename*=UTF-8''")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(audio));
    }

    @Test
    void onlyCompleteWaveFilesWithinTheLimitAreAccepted() throws Exception {
        String token = login();
        byte[] audio = wav(8000, 1, 4000 + ThreadLocalRandom.current().nextInt(4000));
        byte[] truncated = java.util.Arrays.copyOf(audio, audio.length - 7);
        byte[] mp3 = new byte[2048];
        ThreadLocalRandom.current().nextBytes(mp3);
        mp3[0] = 'I'; mp3[1] = 'D'; mp3[2] = '3';
        byte[] riffOnly = java.util.Arrays.copyOf(audio, 44);

        for (byte[] invalid : List.of(truncated, mp3, riffOnly)) {
            upload(token, "recording.wav", invalid, "无效录音", "无效内容", key())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_INVALID"));
        }
        byte[] oversized = new byte[(int) AdvisoryVoiceRecording.MAX_BYTES + 1];
        System.arraycopy(audio, 0, oversized, 0, audio.length);
        upload(token, "large.wav", oversized, "超大录音", "超大", key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        upload(token, "recording.wav", audio, "  ", "有文稿", key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        upload(token, "recording.wav", audio, "有名称", "", key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        upload(token, "recording.wav", audio, "有名称", "有文稿", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advisory_voice_recording", Integer.class)).isZero();
        Path root = storage.resolve("advisory-voice-recordings");
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                assertThat(files.filter(Files::isRegularFile).toList()).as("被拒绝的上传不留文件").isEmpty();
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE module_code='interfaces' AND result='FAILURE' AND detail LIKE '%VOICE_RECORDING_INVALID%'", Integer.class))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void duplicateContentAndReplayedKeysDoNotCreateSecondRecordings() throws Exception {
        String token = login();
        byte[] audio = wav(16000, 1, 9000 + ThreadLocalRandom.current().nextInt(4000));
        String key = key();
        upload(token, "a.wav", audio, "第一段", "第一段文稿", key).andExpect(status().isCreated());
        upload(token, "a.wav", audio, "第一段", "第一段文稿", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        upload(token, "b.wav", audio, "换个名字", "同一段声音", key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_DUPLICATE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("第一段")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advisory_voice_recording", Integer.class)).isEqualTo(1);
        try (var files = Files.walk(storage.resolve("advisory-voice-recordings"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void selectingSwitchesWhatAutomaticCallsPlayAndStaleVersionsAreRejected() throws Exception {
        String token = login();
        String first = uploadId(token, "第一段-" + suffix(), wav(8000, 1, 6000 + ThreadLocalRandom.current().nextInt(2000)));
        String second = uploadId(token, "第二段-" + suffix(), wav(16000, 2, 8000 + ThreadLocalRandom.current().nextInt(2000)));

        JsonNode selected = data(active(token, first, true, 0, key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_recording_id").value(first))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.current.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.current.available").value(true))
                .andExpect(jsonPath("$.data.current.recording_id").value(first)));
        assertThat(item(selected, first).path("active").asBoolean()).isTrue();
        assertThat(item(selected, first).path("can_delete").asBoolean()).isFalse();
        assertThat(recordings.current().id()).isEqualTo(first);

        active(token, second, true, 0, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        active(token, first, true, 1, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_ALREADY_ACTIVE"));
        active(token, second, false, 1, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_NOT_ACTIVE"));

        active(token, second, true, 1, key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_recording_id").value(second))
                .andExpect(jsonPath("$.data.version").value(2));
        assertThat(recordings.current().id()).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE object_id=? AND action='advisory_voice_recording_activated'", String.class, second))
                .contains("previous_recording_id=" + first);

        active(token, second, false, 2, key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_recording_id").doesNotExist())
                .andExpect(jsonPath("$.data.current.source").value("NONE"));
        assertThat(recordings.current()).isNull();
        assertThat(auditCount(second, "advisory_voice_recording_deactivated")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM advisory_voice_recording_setting WHERE setting_id='global'", Integer.class)).isEqualTo(3);
    }

    @Test
    void concurrentSelectionsWithTheSameVersionLetOnlyOneWin() throws Exception {
        String token = login();
        String first = uploadId(token, "并发一-" + suffix(), wav(8000, 1, 5000 + ThreadLocalRandom.current().nextInt(1000)));
        String second = uploadId(token, "并发二-" + suffix(), wav(8000, 1, 7000 + ThreadLocalRandom.current().nextInt(1000)));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> { start.await(); return active(token, first, true, 0, key()).andReturn().getResponse().getStatus(); },
                    () -> { start.await(); return active(token, second, true, 0, key()).andReturn().getResponse().getStatus(); });
            var futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM advisory_voice_recording_setting WHERE setting_id='global'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='advisory_voice_recording_activated' AND object_id IN (?,?)", Integer.class, first, second))
                .isEqualTo(1);
    }

    @Test
    void uploadedSelectionWinsOverStartupRecordingAndABrokenFileNeverFallsBack() throws Exception {
        String token = login();
        Path startupFile = Files.createTempFile("advisory-startup-", ".wav");
        temporary.add(startupFile);
        Files.write(startupFile, wav(8000, 1, 3000 + ThreadLocalRandom.current().nextInt(3000)));
        String startupId = "startup-" + suffix();
        var withStartup = new AdvisoryVoiceRecording(startupId, "启动参数录音", startupFile.toString(), "启动参数文稿", repository, storage);
        assertThat(withStartup.resolve().source()).isEqualTo(Source.STARTUP_CONFIG);
        assertThat(withStartup.current().id()).isEqualTo(startupId);

        byte[] audio = wav(16000, 1, 10000 + ThreadLocalRandom.current().nextInt(3000));
        String uploaded = uploadId(token, "后台录音-" + suffix(), audio);
        assertThat(withStartup.current().id()).as("只上传未选用，仍播启动参数录音").isEqualTo(startupId);
        active(token, uploaded, true, 0, key()).andExpect(status().isOk());
        assertThat(withStartup.resolve().source()).isEqualTo(Source.UPLOADED);
        assertThat(withStartup.current().id()).isEqualTo(uploaded);
        assertThat(withStartup.current().sha256()).isEqualTo(AdvisoryVoiceRecording.sha256(audio));
        assertThat(withStartup.startup().id()).as("启动参数录音仍可作为停止使用后的后备").isEqualTo(startupId);

        // 文件被改坏：不悄悄改播启动参数录音，电话暂停并在后台说明原因。
        String objectKey = jdbc.queryForObject("SELECT object_key FROM advisory_voice_recording WHERE recording_id=?", String.class, uploaded);
        byte[] damaged = audio.clone();
        damaged[damaged.length - 1] ^= 0x5a;
        Files.write(storage.resolve(objectKey), damaged);
        assertThat(withStartup.resolve().source()).isEqualTo(Source.UPLOADED);
        assertThat(withStartup.current()).isNull();
        assertThat(recordings.current()).isNull();
        mvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.current.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.current.available").value(false))
                .andExpect(jsonPath("$.data.current.message").value(org.hamcrest.Matchers.containsString("文件缺失或已损坏")));
        mvc.perform(get(BASE + "/{id}/content", uploaded).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_FILE_UNAVAILABLE"));

        // 坏文件不能被选用；停止使用后回到启动参数录音。
        String other = uploadId(token, "另一段-" + suffix(), wav(8000, 2, 4000 + ThreadLocalRandom.current().nextInt(2000)));
        Files.delete(storage.resolve(jdbc.queryForObject("SELECT object_key FROM advisory_voice_recording WHERE recording_id=?", String.class, other)));
        active(token, other, true, 1, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_FILE_UNAVAILABLE"));
        active(token, uploaded, false, 1, key()).andExpect(status().isOk());
        assertThat(withStartup.resolve().source()).isEqualTo(Source.STARTUP_CONFIG);
        assertThat(withStartup.current().id()).isEqualTo(startupId);
    }

    @Test
    void verifiedAlarmCallsWithTheSelectedUploadAndUsedRecordingsAreKept() throws Exception {
        String token = login();
        String eventId = confirmedEventWithDeliveredSms();
        voice.process(eventId);
        assertThat(jdbc.queryForObject("SELECT status FROM uav_auto_voice_task WHERE event_id=?", String.class, eventId))
                .as("没有录音时电话这一步跳过").isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_voice_advisory WHERE event_id=?", Integer.class, eventId)).isZero();

        byte[] audio = wav(8000, 1, 8000 + ThreadLocalRandom.current().nextInt(4000));
        String name = "飞手劝离-" + suffix();
        String id = uploadId(token, name, audio);
        active(token, id, true, 0, key()).andExpect(status().isOk());

        voice.process(eventId);
        assertThat(jdbc.queryForObject("SELECT status FROM uav_auto_voice_task WHERE event_id=?", String.class, eventId)).isEqualTo("SIMULATED_PLAYED");
        var played = jdbc.queryForMap("SELECT recording_id,recording_name,recording_sha256 FROM uav_event_voice_advisory WHERE event_id=?", eventId);
        assertThat(played.get("recording_id")).isEqualTo(id);
        assertThat(played.get("recording_name")).isEqualTo(name);
        assertThat(played.get("recording_sha256")).isEqualTo(AdvisoryVoiceRecording.sha256(audio));
        mvc.perform(get("/api/v1/uav-events/{id}/advisory", eventId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"))
                .andExpect(jsonPath("$.data.auto_voice.recording_name").value(name));

        mvc.perform(delete(BASE + "/{id}", id).header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_IN_USE"));
        active(token, id, false, 1, key()).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/{id}", id).header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_USED"));
        JsonNode catalog = data(mvc.perform(get(BASE).header("Authorization", bearer(token))));
        assertThat(item(catalog, id).path("used").asBoolean()).isTrue();
        assertThat(item(catalog, id).path("can_delete").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advisory_voice_recording WHERE recording_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void unusedRecordingsCanBeDeletedWithTheirFile() throws Exception {
        String token = login();
        String id = uploadId(token, "待删除-" + suffix(), wav(8000, 1, 2000 + ThreadLocalRandom.current().nextInt(2000)));
        String objectKey = jdbc.queryForObject("SELECT object_key FROM advisory_voice_recording WHERE recording_id=?", String.class, id);
        mvc.perform(delete(BASE + "/{id}", id).header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(storage.exists(objectKey)).isFalse();
        assertThat(auditCount(id, "advisory_voice_recording_deleted")).isEqualTo(1);
        mvc.perform(delete(BASE + "/{id}", id).header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_NOT_FOUND"));
        mvc.perform(get(BASE + "/{id}/content", id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void managingNeedsInterfaceOperationNotificationAuthorityAndAllScope() throws Exception {
        String admin = login();
        String id = uploadId(admin, "权限测试-" + suffix(), wav(8000, 1, 3000 + ThreadLocalRandom.current().nextInt(3000)));
        String reader = session("ALL", "interfaces=READ");
        String operatorWithoutAuthority = session("ALL", "interfaces=OP");
        String assignedManager = session("ASSIGNED", "interfaces=OP", "notificationSettings=AUTH");
        String manager = session("ALL", "interfaces=OP", "notificationSettings=AUTH");
        String outsider = session("ALL", "devices=READ");

        mvc.perform(get(BASE).header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get(BASE + "/{id}/content", id).header("Authorization", bearer(reader))).andExpect(status().isOk());
        mvc.perform(get(BASE).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/{id}/content", id).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());

        byte[] audio = wav(8000, 1, 2000 + ThreadLocalRandom.current().nextInt(2000));
        upload(reader, "r.wav", audio, "只读用户上传", "不允许", key()).andExpect(status().isForbidden());
        upload(operatorWithoutAuthority, "o.wav", audio, "缺通知授权", "不允许", key()).andExpect(status().isForbidden());
        upload(assignedManager, "s.wav", audio, "部分范围", "不允许", key())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VOICE_RECORDING_SCOPE_REQUIRED"));
        active(reader, id, true, 0, key()).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/{id}", id).header("Authorization", bearer(assignedManager)).header("Idempotency-Key", key()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advisory_voice_recording", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_recording_id FROM advisory_voice_recording_setting WHERE setting_id='global'", String.class)).isNull();

        mvc.perform(get(BASE).header("Authorization", bearer(manager))).andExpect(jsonPath("$.data.can_manage").value(true));
        active(manager, id, true, 0, key()).andExpect(status().isOk());
        assertThat(recordings.current().id()).isEqualTo(id);
    }

    // ---------- helpers ----------

    protected ResultActions upload(String token, String filename, byte[] content, String name, String transcript, String key) throws Exception {
        var request = multipart(BASE).file(new MockMultipartFile("file", filename, "audio/wav", content))
                .param("name", name).param("transcript", transcript)
                .header("Authorization", bearer(token)).header("User-Agent", "recording-test");
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private String uploadId(String token, String name, byte[] content) throws Exception {
        return data(upload(token, "recording.wav", content, name, name + "的文稿", key()).andExpect(status().isCreated()))
                .path("recording_id").asText();
    }

    private ResultActions active(String token, String id, boolean active, int version, String key) throws Exception {
        return mvc.perform(patch(BASE + "/{id}/active", id).header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":" + active + ",\"expected_version\":" + version + "}"));
    }

    private static JsonNode item(JsonNode catalog, String id) {
        for (JsonNode item : catalog.path("items")) if (id.equals(item.path("recording_id").asText())) return item;
        throw new AssertionError("recording not listed: " + id);
    }

    private int auditCount(String id, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE object_type='advisory_voice_recording' AND object_id=? AND action=? AND result='SUCCESS' AND module_code='interfaces'",
                Integer.class, id, action);
    }

    /** 隔离角色与会话；grants 写成“模块码=READ/OP/AUTH”。 */
    private String session(String scope, String... grants) {
        String suffix = suffix(), role = "ROLE-REC-" + suffix, user = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)", role, role);
        for (String grant : grants)
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,?,false,current_timestamp)",
                    role, grant.substring(0, grant.indexOf('=')), grant.substring(grant.indexOf('=') + 1));
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",
                user, "rec-" + suffix, "录音测试员", role, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)", user, ORG, DISTRICT);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)", session, user, System.currentTimeMillis() + 3600000);
        return session;
    }

    /** 与 AutoVoiceApiTest 相同的已核实事件：飞手已关联、短信已送达且观察期已过，下一步就是电话。 */
    private String confirmedEventWithDeliveredSms() {
        String suffix = suffix(), alarm = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), event = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        String verifier = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,'mock',?,?,0)", source, "REC-" + suffix, "录音测试源", at, at);
        jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'UAV','mock',?,?,?,?,0)", target, "REC-" + suffix, ORG, DISTRICT, at, at);
        jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) values(?,?,?,?,?,CAST('[]' AS JSON))", target, at, at, at, at);
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, target, source, "REC-" + suffix, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, ORG, DISTRICT, at, at);
        jdbc.update("insert into uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values(?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','测试人工确认现场违规',?,?)",
                UUID.randomUUID().toString(), event, verifier, at);
        String plan = DirectoryAdvisoryFixture.create(jdbc, ORG, DISTRICT);
        DirectoryAdvisoryFixture.evaluation(jdbc, event, target, plan, ORG, DISTRICT, Instant.now().minusSeconds(30));
        sms.process(event);
        assertThat(jdbc.update("UPDATE uav_auto_sms_task SET updated_at=? WHERE event_id=? AND status='SIMULATED_DELIVERED'", System.currentTimeMillis() - 61_000L, event))
                .as("短信先送达").isEqualTo(1);
        return event;
    }

    protected String login() throws Exception {
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())).path("session_id").asText();
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** 每次内容不同的完整 PCM WAV；采样率、声道和帧数由调用方变换。 */
    static byte[] wav(int sampleRate, int channels, int frames) {
        var format = new AudioFormat(sampleRate, 16, channels, true, false);
        byte[] pcm = new byte[frames * format.getFrameSize()];
        ThreadLocalRandom.current().nextBytes(pcm);
        try (var input = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames); var out = new ByteArrayOutputStream()) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
