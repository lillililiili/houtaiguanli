package com.uav.lowaltitude.integration.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;

class SimulatorAdvisoryVoiceAdapterTest {
    @Test
    void submitsTheSelectedRecordingBytesToTheDeviceSimulator() {
        var transport = mock(RealtimeNotificationTransport.class);
        var tasks = mock(AutoVoiceRepository.class);
        var directory = mock(NotificationDirectoryService.class);
        var provider = mock(ObjectProvider.class);
        var recordings = mock(AdvisoryVoiceRecording.class);
        var json = new ObjectMapper();
        var recording = new Recording("rec-1", "现场提醒", "请立即降落", "sha256");
        var audio = new byte[] { 1, 2, 3, 4 };
        var recipient = new RecipientSnapshot("pilot", "飞手", "org", "单位", "contact", "飞手", "***",
                "API", "local-data-simulator", "setting", 1L, true, null, 100L);
        var task = new AutoVoiceRepository.Task("event-1", "CALLING", "", "", null, null, null, 100L, 1,
                200L, "claim", "auto-advisory-voice:event-1", "rec-1", "现场提醒", "sha256", "请立即降落", null,
                null);
        when(transport.online()).thenReturn(true);
        when(tasks.find("event-1")).thenReturn(task);
        when(provider.getObject()).thenReturn(directory);
        when(directory.advisoryHistoryRecipient("ADVISORY_VOICE", "event-1")).thenReturn(recipient);
        when(recordings.content(recording)).thenReturn(audio);

        var adapter = new SimulatorAdvisoryVoiceAdapter(transport, tasks, provider, json, recordings);
        adapter.simulate("mock", recording, "auto-advisory-voice:event-1");

        var payload = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        org.mockito.Mockito.verify(transport).submit(eq("ADVISORY_VOICE"), eq("event-1"), eq("auto-advisory-voice:event-1:claim"), payload.capture());
        assertThat(payload.getValue().path("recording_audio_base64").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(audio));
        assertThat(payload.getValue().path("recording").path("id").asText()).isEqualTo("rec-1");
    }
}
