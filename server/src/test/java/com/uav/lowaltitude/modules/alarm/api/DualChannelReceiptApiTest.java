package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.platform.time.AppClock;

/** Notification matrix uses only local simulated channels; no real SMS or phone acceptance is claimed. */
class DualChannelReceiptApiTest extends AutoVoiceApiTest {
    @SpyBean AppClock clock;

    @AfterEach void resetMatrixClock() { reset(clock); }

    @Test void matrixSmsOutOfOrderAcrossEventsCannotAdvanceTheOtherEvent() throws Exception {
        String older = eventId;
        clearSms(older);
        fixture();
        String current = eventId;
        clearSms(current);
        clearInvocations(sms); // The inherited fixture sent each channel's initial SMS before reset.
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(sms).simulateAutomatic(anyString(), anyString(), anyString(), eq("auto-advisory:" + older));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> automatic.process(older));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            automatic.process(older); // Duplicate work while its original receipt is pending.
            automatic.process(current);
            long currentDelivered = jdbc.queryForObject("select updated_at from uav_auto_sms_task where event_id=?", Long.class, current);
            at(currentDelivered + NotifyFlow.SMS_WATCH_MILLIS);
            voiceService.process(current);
            var completed = snapshot(current);
            release.countDown();
            pending.get(20, TimeUnit.SECONDS);
            automatic.process(older);
            automatic.process(current);
            voiceService.process(current);
            assertThat(snapshot(current)).isEqualTo(completed);
            assertThat(records(older, "uav_event_advisory")).isEqualTo(1);
            assertThat(records(older, "uav_event_voice_advisory")).isZero();
            assertThat(records(current, "uav_event_advisory")).isEqualTo(1);
            assertThat(records(current, "uav_event_voice_advisory")).isEqualTo(1);
            verify(sms, times(1)).simulateAutomatic(anyString(), anyString(), anyString(), eq("auto-advisory:" + older));
            assertConfirmed(older, current);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void matrixVoiceOutOfOrderAcrossEventsCannotAdvanceTheOtherEvent() throws Exception {
        String older = eventId;
        fixture();
        String current = eventId;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(voice).simulate(anyString(), any(), eq("auto-advisory-voice:" + older));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> voiceService.process(older));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            voiceService.process(older);
            voiceService.process(current);
            var completed = snapshot(current);
            release.countDown();
            pending.get(20, TimeUnit.SECONDS);
            for (String id : new String[] { current, older, current, older }) {
                automatic.process(id);
                voiceService.process(id);
                assertThat(records(id, "uav_event_advisory")).isEqualTo(1);
                assertThat(records(id, "uav_event_voice_advisory")).isEqualTo(1);
                assertThat(jdbc.queryForObject("select provider_call_id from uav_auto_voice_task where event_id=?", String.class, id))
                        .isEqualTo("simulation:auto-advisory-voice:" + id);
            }
            assertThat(snapshot(current)).isEqualTo(completed);
            verify(voice, times(1)).simulate(anyString(), any(), eq("auto-advisory-voice:" + older));
            verify(voice, times(1)).simulate(anyString(), any(), eq("auto-advisory-voice:" + current));
            assertConfirmed(older, current);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void matrixExpiredSmsAndVoiceReceiptsCannotOverwriteCurrentBatchOrUnknown() throws Exception {
        String oldSms = eventId;
        clearSms(oldSms);
        fixture();
        String oldVoice = eventId;
        fixture();
        String current = eventId;
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); assertThat(release.await(20, TimeUnit.SECONDS)).isTrue(); return call.callRealMethod();
        }).when(sms).simulateAutomatic(anyString(), anyString(), anyString(), eq("auto-advisory:" + oldSms));
        doAnswer(call -> {
            entered.countDown(); assertThat(release.await(20, TimeUnit.SECONDS)).isTrue(); return call.callRealMethod();
        }).when(voice).simulate(anyString(), any(), eq("auto-advisory-voice:" + oldVoice));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var pendingSms = pool.submit(() -> automatic.process(oldSms));
            var pendingVoice = pool.submit(() -> voiceService.process(oldVoice));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("update uav_auto_sms_task set lease_until=0 where event_id=?", oldSms);
            jdbc.update("update uav_auto_voice_task set lease_until=0 where event_id=?", oldVoice);
            automatic.process(oldSms);
            voiceService.process(oldVoice);
            voiceService.process(current);
            var currentBefore = snapshot(current);
            var oldSmsBefore = snapshot(oldSms);
            var oldVoiceBefore = snapshot(oldVoice);
            release.countDown();
            pendingVoice.get(20, TimeUnit.SECONDS);
            pendingSms.get(20, TimeUnit.SECONDS);
            for (String id : new String[] { oldSms, current, oldVoice, current }) {
                automatic.process(id); voiceService.process(id);
            }
            assertThat(snapshot(current)).isEqualTo(currentBefore);
            // The old SMS's following voice may become WAITING; neither old receipt can add a contact fact.
            assertThat(snapshot(oldSms).get("event")).isEqualTo(oldSmsBefore.get("event"));
            assertThat(snapshot(oldVoice)).isEqualTo(oldVoiceBefore);
            assertThat(jdbc.queryForObject("select status from uav_auto_sms_task where event_id=?", String.class, oldSms)).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("select status from uav_auto_voice_task where event_id=?", String.class, oldVoice)).isEqualTo("UNKNOWN");
            assertThat(records(oldSms, "uav_event_advisory")).isZero();
            assertThat(records(oldVoice, "uav_event_voice_advisory")).isZero();
            assertThat(records(current, "uav_event_advisory")).isEqualTo(1);
            assertThat(records(current, "uav_event_voice_advisory")).isEqualTo(1);
            assertConfirmed(oldSms, oldVoice, current);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void matrixWatchWindowsUseDeliveryAndPlaybackTimesAndUnknownNeverMeansDeparture() throws Exception {
        long delivered = System.currentTimeMillis();
        jdbc.update("update uav_auto_sms_task set updated_at=? where event_id=?", delivered, eventId);
        assertThat(NotifyFlow.SMS_WATCH_MILLIS).isEqualTo(3_000L);
        assertThat(NotifyFlow.CALL_WATCH_MILLIS).isEqualTo(10_000L);
        at(delivered + 2_999L);
        voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.notify_phase").value("WATCHING"));
        assertThat(records(eventId, "uav_event_voice_advisory")).isZero();
        at(delivered + 3_000L);
        voiceService.process(eventId);
        long played = jdbc.queryForObject("select playback_completed_at from uav_auto_voice_task where event_id=?", Long.class, eventId);
        assertThat(played).isEqualTo(delivered + 3_000L);
        at(played + 9_999L);
        read().andExpect(jsonPath("$.data.notify_phase").value("WATCHING"))
                .andExpect(jsonPath("$.data.counter_launch_visible").value(false));
        when(departure.assess(anyString(), anyLong(), anyLong())).thenReturn(Presence.UNKNOWN);
        at(played + 10_000L);
        read().andExpect(jsonPath("$.data.notify_phase").value("AWAIT_COUNTER"))
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
        assertConfirmed(eventId);
        assertThat(records(eventId, "uav_event_advisory")).isEqualTo(1);
        assertThat(records(eventId, "uav_event_voice_advisory")).isEqualTo(1);
    }

    @Test void realTimeWatchWindowsCompleteOnlyAfterThreeAndTenSeconds() throws Exception {
        clearSms(eventId);
        automatic.process(eventId);
        long delivered = jdbc.queryForObject("select updated_at from uav_auto_sms_task where event_id=?", Long.class, eventId);
        voiceService.process(eventId);
        assertThat(records(eventId, "uav_event_voice_advisory")).isZero();
        read().andExpect(jsonPath("$.data.notify_phase").value("WATCHING"));
        while (System.currentTimeMillis() < delivered + 3_000L) Thread.sleep(25);
        voiceService.process(eventId);
        long played = jdbc.queryForObject("select playback_completed_at from uav_auto_voice_task where event_id=?", Long.class, eventId);
        assertThat(played - delivered).isGreaterThanOrEqualTo(3_000L);
        read().andExpect(jsonPath("$.data.notify_phase").value("WATCHING"))
                .andExpect(jsonPath("$.data.counter_launch_visible").value(false));
        while (System.currentTimeMillis() < played + 10_000L) Thread.sleep(25);
        when(departure.assess(anyString(), anyLong(), anyLong())).thenReturn(Presence.UNKNOWN);
        read().andExpect(jsonPath("$.data.notify_phase").value("AWAIT_COUNTER"))
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
        assertThat(System.currentTimeMillis() - played).isGreaterThanOrEqualTo(10_000L);
        assertConfirmed(eventId);
    }

    private void at(long millis) {
        doReturn(millis).when(clock).nowMillis();
        doReturn(Instant.ofEpochMilli(millis)).when(clock).now();
    }
    private void clearSms(String id) {
        jdbc.update("delete from uav_auto_sms_task where event_id=?", id);
        jdbc.update("delete from uav_event_advisory where event_id=?", id);
    }
    private int records(String id, String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where event_id=?", Integer.class, id);
    }
    private Map<String, Object> snapshot(String id) {
        return Map.of("event", jdbc.queryForMap("select state_code,version from uav_event where event_id=?", id),
                "sms", jdbc.queryForList("select * from uav_auto_sms_task where event_id=?", id),
                "voice", jdbc.queryForList("select * from uav_auto_voice_task where event_id=?", id));
    }
    private void assertConfirmed(String... ids) throws Exception {
        for (String id : ids) {
            assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, id)).isEqualTo("CONFIRMED");
            JsonNode data = json.readTree(mvc.perform(get("/api/v1/uav-events/" + id + "/advisory")
                    .header("Authorization", "Bearer " + session)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");
            assertThat(data.path("can_request_counter").asBoolean()).isFalse();
        }
    }
}
