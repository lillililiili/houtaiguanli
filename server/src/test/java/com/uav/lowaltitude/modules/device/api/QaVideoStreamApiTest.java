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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import com.uav.lowaltitude.modules.device.infrastructure.VideoMediaClient;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.evidence.api.EvidenceTestFiles;

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
    @Test void trackingFrameAndClipAreStoredAsEvidenceOfTargetEventAndDevice() throws Exception {
        String target=insertVideoTarget(), task=UUID.randomUUID().toString(), command=UUID.randomUUID().toString();
        String alarm=UUID.randomUUID().toString(), event=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                + "VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)",alarm,target,binding.sourceId(),alarm,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                + "VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",event,alarm,org,district);
        byte[] jpeg=EvidenceTestFiles.bytes("frame.jpg");
        capture(target,"EO_STILL","no-stream",event,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_CAPTURE_NOT_TRACKING"));
        long now=clock.nowMillis();
        edges.insertCommand(command,"VIDEO-CAPTURE-"+command.substring(0,8),binding.opsDeviceId(),null,"EO_BEGIN_TRACK","fixture","replay",true,now+10000,now);
        edges.insertTask(task,target,null,binding.opsDeviceId(),command,"fixture","{}",now);
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);
        edges.updateCommand(command,"SENT","SUCCEEDED",now,"200",null);
        String receipt=mapper.writeValueAsString(Map.of("event","BeginTracking","edgeId",binding.edgeId(),"timestamp",now,
                "metadata",Map.of("taskId",task,"deviceId",binding.externalDeviceId(),"codeStatus",200)));
        edges.addReceipt(command,edges.inbox(binding,EoEdgeEnvelope.decode(binding.reportingTopic(),receipt.getBytes(StandardCharsets.UTF_8)),now),"200",now,receipt);
        edges.trackingReport(task,now,now);
        capture(target,"EO_STILL","no-stream",event,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_VIDEO_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("测试视频尚未推流或已中断，暂不能截图或录像。"));
        String url="/api/v1/local-interface-simulator/video-streams/"+task;
        String stream=mapper.readTree(mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("device_id",binding.opsDeviceId())))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("stream_id").asText();
        capture(target,"EO_STILL",UUID.randomUUID().toString(),event,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VIDEO_STREAM_NOT_CURRENT"));

        var still=data(capture(target,"EO_STILL",stream,event,"frame.jpg","image/jpeg",jpeg).andExpect(status().isCreated()));
        assertThat(still.path("kind_code").asText()).isEqualTo("EO_STILL");
        assertThat(still.path("content_type").asText()).isEqualTo("image/jpeg");
        assertThat(still.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(still.path("source_mode").asText()).isEqualTo("replay");
        assertThat(still.path("capture_provenance").asText()).isEqualTo("EO_TRACKING_CAPTURE");
        assertThat(still.path("source_device_id").asText()).isEqualTo(binding.opsDeviceId());
        assertThat(still.path("owner_org_id").asText()).isEqualTo(org);
        assertThat(still.path("links").findValuesAsText("subject_kind")).containsExactlyInAnyOrder("EVENT","TARGET","DEVICE");
        long before=clock.nowMillis();
        var clip=data(mvc.perform(multipart("/api/v1/targets/"+target+"/video/captures")
                .file(new MockMultipartFile("file","clip.webm","video/webm;codecs=vp8",EvidenceTestFiles.bytes("clip.webm")))
                .param("kind_code","EO_VIDEO").param("stream_id",stream).param("capture_age_ms","45000")
                .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
        assertThat(clip.path("content_type").asText()).isEqualTo("video/webm");
        // 起录时刻由服务端按“多久以前开始录”换算，不取客户端钟点。
        assertThat(clip.path("captured_at").asLong()).isBetween(before-45_000,clock.nowMillis()-45_000);
        assertThat(clip.path("links").findValuesAsText("subject_kind")).containsExactlyInAnyOrder("TARGET","DEVICE");
        // 证据台账按事件能查到截图；文件格式与普通入库一样核对。
        mvc.perform(get("/api/v1/evidence-files").param("subject_kind","EVENT").param("subject_id",event).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].evidence_id").value(still.path("evidence_id").asText()));
        capture(target,"EO_STILL",stream,null,"frame.png","image/png",jpeg)
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("EVIDENCE_TYPE_MISMATCH"));
        capture(target,"EO_VIDEO",stream,null,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("EVIDENCE_TYPE_NOT_ALLOWED"));
        capture(target,"SCENE_PHOTO",stream,null,"frame.jpg","image/jpeg",jpeg).andExpect(status().isBadRequest());
        for(String age:new String[]{"-1","600001"}) {
            mvc.perform(multipart("/api/v1/targets/"+target+"/video/captures").file(new MockMultipartFile("file","frame.jpg","image/jpeg",jpeg))
                    .param("kind_code","EO_STILL").param("stream_id",stream).param("capture_age_ms",age)
                    .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message").value("只能保存最近 10 分钟内截取的画面"));
        }
        // 事件必须是这个目标的告警，不能把截图挂到别的事件上。
        String otherTarget=insertVideoTarget(), otherAlarm=UUID.randomUUID().toString(), otherEvent=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                + "VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)",otherAlarm,otherTarget,binding.sourceId(),otherAlarm,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                + "VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",otherEvent,otherAlarm,org,district);
        capture(target,"EO_STILL",stream,otherEvent,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message").value("事件与当前跟踪目标不对应，请从该目标的告警重新打开"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_link l JOIN evidence_file f ON f.evidence_id=l.evidence_id "
                + "WHERE l.subject_kind='TARGET' AND l.subject_id=? AND f.capture_provenance='EO_TRACKING_CAPTURE'",Long.class,target)).isEqualTo(2L);
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED' WHERE task_id=?",task);
        capture(target,"EO_STILL",stream,event,"frame.jpg","image/jpeg",jpeg)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_CAPTURE_NOT_TRACKING"));
        mvc.perform(delete(url).header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }

    private ResultActions capture(String target,String kind,String stream,String event,String name,String type,byte[] bytes) throws Exception {
        var request=multipart("/api/v1/targets/"+target+"/video/captures").file(new MockMultipartFile("file",name,type,bytes))
                .param("kind_code",kind).param("stream_id",stream)
                .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString());
        if(event!=null)request.param("event_id",event);
        return mvc.perform(request);
    }

    private com.fasterxml.jackson.databind.JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private String insertVideoTarget() {
        String target=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'UAV','replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",target,"VIDEO-"+target.substring(0,8),org,district);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at,version) "
                + "VALUES (?,GEOMETRY 'SRID=4326;POINT (118 37)',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",target);
        return target;
    }
}
