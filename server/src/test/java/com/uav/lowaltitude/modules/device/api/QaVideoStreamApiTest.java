package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import com.uav.lowaltitude.modules.device.infrastructure.VideoMediaClient;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;

/** Exercises actual auth, scoped task records and media routing without a production media server. */
@TestPropertySource(properties={"app.video.qa-enabled=true", "spring.datasource.url=jdbc:h2:mem:qa_video_stream;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class QaVideoStreamApiTest extends EoManualTrackApiTest {
    @MockitoBean VideoMediaClient media;
    @Autowired VideoStreamRegistry streams;

    @Test void registrationPlaybackAndRevocationStayBoundToTask() throws Exception {
        String target=insertVideoTarget(), task=UUID.randomUUID().toString(), command=UUID.randomUUID().toString();
        long now=clock.nowMillis();
        edges.insertCommand(command,"VIDEO-QA",binding.opsDeviceId(),null,"EO_BEGIN_TRACK","fixture","replay",true,now+10000,now);
        edges.insertTask(task,target,null,binding.opsDeviceId(),command,"fixture","{}",now);
        String url="/api/v1/local-interface-simulator/video-streams/"+task;
        String input=mapper.writeValueAsString(Map.of("device_id",binding.opsDeviceId()));
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isUnauthorized());
        var registered=mapper.readTree(mvc.perform(put(url).header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        String id=registered.path("stream_id").asText(),path=registered.path("stream_path").asText();
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(jsonPath("$.data.stream_id").value(id));
        jdbc.update("UPDATE target SET source_mode='live' WHERE target_id=?",target);
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE target SET source_mode='mock' WHERE target_id=?",target);
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(jsonPath("$.data.stream_id").value(id));
        String resource="/api/v1/targets/"+target+"/video/streams/"+id+"/index.m3u8";
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);
        edges.updateCommand(command,"SENT","SUCCEEDED",now,"200",null);
        String receipt=mapper.writeValueAsString(Map.of("event","BeginTracking","edgeId",binding.edgeId(),"timestamp",now,
                "metadata",Map.of("taskId",task,"deviceId",binding.externalDeviceId(),"codeStatus",200)));
        String inbox=edges.inbox(binding,EoEdgeEnvelope.decode(binding.reportingTopic(),receipt.getBytes(StandardCharsets.UTF_8)),now);
        edges.addReceipt(command,inbox,"200",now,receipt);
        edges.trackingReport(task,now,now);
        mvc.perform(get("/api/v1/targets/"+target+"/video").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.status").value("TRACKING")).andExpect(jsonPath("$.data.video_status").value("WAITING"));
        when(media.ready(path)).thenReturn(true);
        when(media.resource(path,"index.m3u8")).thenReturn("#EXTM3U\nsegment.mp4\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/targets/"+target+"/video").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.video_status").value("AVAILABLE")).andExpect(jsonPath("$.data.playback_type").value("HLS"))
                .andExpect(jsonPath("$.data.source_mode").value("replay"));
        mvc.perform(get(resource)).andExpect(status().isUnauthorized());
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"));
        String mediaSession=UUID.randomUUID().toString();
        when(media.resource(path,"index.m3u8",mediaSession)).thenReturn("#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(resource).param("session",mediaSession).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get(resource).param("session",mediaSession,mediaSession).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get(resource).param("url","http://example.invalid").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get(resource).param("session","invalid").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get(resource.replace(id,UUID.randomUUID().toString())).header("Authorization","Bearer "+token))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        try {
            mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(get(resource.replace("index.m3u8","segment.mp4")).header("Authorization","Bearer "+token))
                    .andExpect(status().isForbidden());
            mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'"); }
        when(media.ready(path)).thenReturn(false);
        mvc.perform(get("/api/v1/targets/"+target+"/video").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.video_status").value("INTERRUPTED"));
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED' WHERE task_id=?",task);
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict());
        mvc.perform(delete(url).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(streams.find(task)).isNull();
    }
    private String insertVideoTarget() {
        String target=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'UAV','replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",target,"VIDEO-"+target.substring(0,8),org,district);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at,version) "
                + "VALUES (?,GEOMETRY 'SRID=4326;POINT (118 37)',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",target);
        return target;
    }
}
