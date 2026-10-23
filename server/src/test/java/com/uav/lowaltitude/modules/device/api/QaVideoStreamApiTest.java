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

    @Test void deviceRawIsReadableWithoutTaskAndProtectedByDeviceScopeAndSourceIdentity() throws Exception {
        String device=binding.opsDeviceId(),base="/api/v1/devices/"+device+"/video";
        String registration="/api/v1/local-interface-simulator/device-video-streams/"+device;
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(put(registration)).andExpect(status().isUnauthorized());
        long commands=jdbc.queryForObject("SELECT COUNT(*) FROM device_command",Long.class);
        String raw=data(mvc.perform(put(registration).header("Authorization","Bearer "+token)).andExpect(status().isOk())).path("stream_id").asText();
        var lease=streams.findRaw(device);when(media.ready(lease.streamPath())).thenReturn(true);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RAW")).andExpect(jsonPath("$.data.stream_id").value(raw))
                .andExpect(jsonPath("$.data.metadata.frames").isEmpty());
        String resource=base+"/streams/"+raw+"/index.m3u8";
        when(media.resource(lease.streamPath(),"index.m3u8")).thenReturn("#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        String changedDistrict=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)",
                changedDistrict,"VIDEO-"+changedDistrict.substring(0,8),"视频范围测试");
        jdbc.update("UPDATE device_business_scope SET district_id=? WHERE ops_device_id=?",changedDistrict,device);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        jdbc.update("UPDATE device_business_scope SET district_id=? WHERE ops_device_id=?",district,device);
        jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",device);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
        mvc.perform(put(registration).header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        jdbc.update("UPDATE ops_device SET simulated=TRUE WHERE device_id=?",device);
        mvc.perform(get(resource).param("url","http://example.invalid").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        try {
            mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(put(registration).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        } finally {jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'");}
        jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",device);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
        mvc.perform(get(resource).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command",Long.class)).isEqualTo(commands);
    }

    @Test void deviceAiFramesResolveStoredLinksAndResetOnTaskEndOrMediaLoss() throws Exception {
        String target=insertVideoTarget(),task=UUID.randomUUID().toString(),command=UUID.randomUUID().toString(),device=binding.opsDeviceId();
        long now=clock.nowMillis();
        edges.insertCommand(command,"DEVICE-VIDEO-"+command.substring(0,8),device,null,"EO_BEGIN_TRACK","fixture","replay",true,now+10000,now);
        edges.insertTask(task,target,null,device,command,"fixture","{}",now);
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);edges.updateCommand(command,"SENT","SUCCEEDED",now,"200",null);
        String receipt=mapper.writeValueAsString(Map.of("event","BeginTracking","edgeId",binding.edgeId(),"timestamp",now,
                "metadata",Map.of("taskId",task,"deviceId",binding.externalDeviceId(),"codeStatus",200)));
        edges.addReceipt(command,edges.inbox(binding,EoEdgeEnvelope.decode(binding.reportingTopic(),receipt.getBytes(StandardCharsets.UTF_8)),now),"200",now,receipt);
        edges.trackingReport(task,now,now);
        String registration="/api/v1/local-interface-simulator/device-video-streams/"+device;
        mvc.perform(put(registration).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        var raw=streams.findRaw(device);when(media.ready(raw.streamPath())).thenReturn(true);
        mvc.perform(put("/api/v1/local-interface-simulator/video-streams/"+task).header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("device_id",device)))).andExpect(status().isOk());
        var ai=streams.find(task);when(media.ready(ai.streamPath())).thenReturn(true);
        String observation=UUID.randomUUID().toString(),link=UUID.randomUUID().toString();
        String quality=mapper.writeValueAsString(Map.of("eo_task_id",task,"eo_target_id",target));
        jdbc.update("INSERT INTO source_observation(observation_id,source_id,device_id,source_session_key,external_target_id,observed_at,received_at,quality,source_mode,class_code,class_source,created_at) "
                + "VALUES (?,?,?,?,?,?,?,CAST(? AS JSON),'replay','UAV','EO_TRACKING',CURRENT_TIMESTAMP)",observation,binding.sourceId(),binding.deviceId(),task,task,
                new java.sql.Timestamp(now),new java.sql.Timestamp(now),quality);
        jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",link,target,binding.sourceId(),binding.deviceId(),task,task);
        mvc.perform(get(registration+"/observation-associations").param("task_id",task).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].observation_id").value(observation));
        String body=mapper.writeValueAsString(Map.of("stream_id",ai.streamId(),"task_id",task,"frame_timestamp",now,"media_time_ms",100,
                "detections",java.util.List.of(Map.of("detection_id","primary","x",0.1,"y",0.2,"width",0.2,"height",0.3,"observation_id",observation),
                        Map.of("detection_id","unknown","x",0.6,"y",0.2,"width",0.2,"height",0.3,"observation_id",UUID.randomUUID().toString()))));
        mvc.perform(put(registration+"/frames").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        String base="/api/v1/devices/"+device+"/video";
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("AI"))
                .andExpect(jsonPath("$.data.metadata.frames[0].detections[0].target_id").value(target))
                .andExpect(jsonPath("$.data.metadata.frames[0].detections[0].association_id").value(link))
                .andExpect(jsonPath("$.data.metadata.frames[0].detections[1].association_status").value("UNLINKED"));
        mvc.perform(put(registration+"/frames").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        String invalid=body.replace("\"width\":0.2","\"width\":1.2");
        mvc.perform(put(registration+"/frames").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(put(registration+"/frames").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(ai.streamId(),UUID.randomUUID().toString()))).andExpect(status().isConflict());
        // Changing the source-device reference prevents even an existing observation id from naming a target.
        jdbc.update("UPDATE target_source_link SET device_id=NULL WHERE link_id=?",link);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.metadata.frames[0].detections[0].association_status").value("UNLINKED"));
        jdbc.update("UPDATE target_source_link SET device_id=? WHERE link_id=?",binding.deviceId(),link);
        // A changed link must never keep the formerly clickable identity cached in the frame.
        jdbc.update("DELETE FROM target_source_link WHERE link_id=?",link);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.metadata.frames[0].detections[0].association_status").value("UNLINKED"));
        when(media.ready(ai.streamPath())).thenReturn(false);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("RAW")).andExpect(jsonPath("$.data.metadata.frames").isEmpty());
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED' WHERE task_id=?",task);
        mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.status").value("RAW"));
        mvc.perform(get(base+"/streams/"+ai.streamId()+"/index.m3u8").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(put(registration+"/frames").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

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

    @Autowired com.uav.lowaltitude.modules.device.application.TargetVideoService videoService;
    @Autowired com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService evidenceService;
    @Autowired com.uav.lowaltitude.platform.audit.AuditService auditService;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    /** 告警核实属实、反制开始时系统自动截图并录像，挂到告警事件、目标、光电设备和反制授权上；没有画面如实记下；同一触发只做一次。 */
    @Test void alarmConfirmationAndCounterStartCaptureStillAndClipAutomatically() throws Exception {
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("eo-auto-test");
        java.nio.file.Path jpg=dir.resolve("frame.jpg"), mp4=dir.resolve("clip.mp4"), script=dir.resolve("fake-ffmpeg.sh");
        java.nio.file.Files.write(jpg,EvidenceTestFiles.bytes("frame.jpg"));
        java.nio.file.Files.write(mp4,EvidenceTestFiles.bytes("clip.mp4"));
        // 代替 ffmpeg：按输出文件扩展名写入有效样本，并记下收到的拉流地址。
        java.nio.file.Files.writeString(script,"#!/bin/sh\nfor last; do :; done\necho \"$@\" >> '"+dir.resolve("args.txt")+"'\n"
                +"case \"$last\" in *.jpg) cp '"+jpg+"' \"$last\";; *) cp '"+mp4+"' \"$last\";; esac\n");
        script.toFile().setExecutable(true);
        var service=new com.uav.lowaltitude.modules.device.application.EoAutoCaptureService(jdbc,videoService,evidenceService,
                auditService,tx,clock,true,script.toString(),5);

        String target=insertVideoTarget(), event=insertEvent(target), streamPath=startTracking(target);
        when(media.ready(streamPath)).thenReturn(true);
        when(media.rtspReadUrl(streamPath)).thenReturn("rtsp://127.0.0.1:8554/"+streamPath);
        String confirm=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) "
                + "VALUES (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','',(SELECT user_id FROM app_user WHERE account='admin1'),?)",confirm,event,new java.sql.Timestamp(clock.nowMillis()));
        service.poll();
        awaitFiles(event,2);
        var rows=jdbc.queryForList("SELECT f.kind_code,f.original_name AS file_name,f.status,f.capture_provenance,f.source_mode FROM evidence_file f "
                + "JOIN evidence_link l ON l.evidence_id=f.evidence_id WHERE l.subject_kind='EVENT' AND l.subject_id=? ORDER BY f.kind_code",event);
        assertThat(rows).extracting(r->r.get("kind_code")).containsExactly("EO_STILL","EO_VIDEO");
        assertThat(rows).allSatisfy(r->{
            assertThat(r.get("status")).isEqualTo("AVAILABLE");
            assertThat(r.get("capture_provenance")).isEqualTo("EO_TRACKING_CAPTURE");
            assertThat(r.get("source_mode")).isEqualTo("replay");
            assertThat((String)r.get("file_name")).startsWith("自动-告警核实-");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_link l JOIN evidence_link e ON e.evidence_id=l.evidence_id "
                + "WHERE l.subject_kind='EVENT' AND l.subject_id=? AND e.subject_kind IN ('TARGET','DEVICE')",Long.class,event)).isEqualTo(4L);
        assertThat(java.nio.file.Files.readString(dir.resolve("args.txt"))).contains("rtsp://127.0.0.1:8554/"+streamPath).contains("-t 5");
        // 同一次核实不再重复截取。
        service.poll();
        Thread.sleep(300);
        assertThat(countEventFiles(event)).isEqualTo(2L);

        // 反制开始：同样截图录像，并挂到这次反制授权上。
        String authorization=insertExecutingAuthorization(target,event);
        service.poll();
        awaitFiles(event,4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_link WHERE subject_kind='AUTHORIZATION' AND subject_id=?",Long.class,authorization)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_file f JOIN evidence_link l ON l.evidence_id=f.evidence_id "
                + "WHERE l.subject_kind='AUTHORIZATION' AND l.subject_id=? AND f.original_name LIKE '自动-反制开始-%'",Long.class,authorization)).isEqualTo(2L);

        // 当时没有画面：不截取，审计里写明原因。
        String quietTarget=insertVideoTarget(), quietEvent=insertEvent(quietTarget), quietConfirm=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) "
                + "VALUES (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','',(SELECT user_id FROM app_user WHERE account='admin1'),?)",quietConfirm,quietEvent,new java.sql.Timestamp(clock.nowMillis()));
        service.poll();
        Thread.sleep(300);
        assertThat(countEventFiles(quietEvent)).isZero();
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE action='eo_auto_capture' AND object_id=?",String.class,quietConfirm))
                .contains("当时没有光电视频画面");
        assertThat(jdbc.queryForObject("SELECT result FROM audit_log WHERE action='eo_auto_capture' AND object_id=?",String.class,quietConfirm))
                .isEqualTo("SKIPPED");
        // 两分钟以前的核实不补拍：画面要是当时的。
        String oldTarget=insertVideoTarget(), oldEvent=insertEvent(oldTarget);
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) "
                + "VALUES (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','',(SELECT user_id FROM app_user WHERE account='admin1'),?)",UUID.randomUUID().toString(),oldEvent,
                new java.sql.Timestamp(clock.nowMillis()-180_000));
        service.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log a JOIN uav_event_verification v ON v.history_id=a.object_id "
                + "WHERE a.action='eo_auto_capture' AND v.event_id=?",Long.class,oldEvent)).isZero();
    }

    private String insertEvent(String target) {
        String alarm=UUID.randomUUID().toString(), event=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                + "VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)",alarm,target,binding.sourceId(),alarm,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                + "VALUES (?,?,'CONFIRMED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)",event,alarm,org,district);
        return event;
    }

    private String startTracking(String target) throws Exception {
        String task=UUID.randomUUID().toString(), command=UUID.randomUUID().toString();
        long now=clock.nowMillis();
        edges.insertCommand(command,"VIDEO-AUTO-"+command.substring(0,8),binding.opsDeviceId(),null,"EO_BEGIN_TRACK","fixture","replay",true,now+10000,now);
        edges.insertTask(task,target,null,binding.opsDeviceId(),command,"fixture","{}",now);
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);
        edges.updateCommand(command,"SENT","SUCCEEDED",now,"200",null);
        String url="/api/v1/local-interface-simulator/video-streams/"+task;
        return mapper.readTree(mvc.perform(put(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("device_id",binding.opsDeviceId())))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("stream_path").asText();
    }

    private String insertExecutingAuthorization(String target,String event) {
        String id=UUID.randomUUID().toString(), user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        String policy=jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code FETCH FIRST 1 ROW ONLY",String.class);
        var now=new java.sql.Timestamp(clock.nowMillis());
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,channel,reason,"
                + "requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at) "
                + "VALUES (?,?,'COUNTERMEASURE','UAV_EVENT',?,?,NULL,'MANUAL','test',?,?,'EXECUTING',?,?,?,'replay',1,?,?)",
                id,"AUTH-T-"+id.substring(0,8),event,target,user,now,policy,org,district,now,now);
        jdbc.update("INSERT INTO disposal_authorization_event(event_id,authorization_id,event_kind,actor_id,note,occurred_at) VALUES (?,?,'EXECUTE',?,NULL,?)",
                UUID.randomUUID().toString(),id,user,now);
        return id;
    }

    private long countEventFiles(String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evidence_link WHERE subject_kind='EVENT' AND subject_id=?",Long.class,event);
    }

    private void awaitFiles(String event,long expected) throws InterruptedException {
        for(int i=0;i<100 && countEventFiles(event)<expected;i++) Thread.sleep(100);
        assertThat(countEventFiles(event)).isEqualTo(expected);
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
