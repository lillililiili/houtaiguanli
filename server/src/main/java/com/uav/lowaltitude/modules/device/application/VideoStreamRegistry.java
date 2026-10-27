package com.uav.lowaltitude.modules.device.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Process-local QA leases. Restart deliberately invalidates all stream associations. */
@Component
public class VideoStreamRegistry {
    private static final long LEASE_MS = 60_000;
    private final Map<String, Stream> streams = new HashMap<>();
    private final Map<String, Stream> rawStreams = new HashMap<>();
    private final Map<String, java.util.ArrayDeque<Frame>> frames = new HashMap<>();
    private final Environment environment;
    private final AppClock clock;
    private final boolean enabled;

    public VideoStreamRegistry(Environment environment, AppClock clock,
                               @Value("${app.video.qa-enabled:false}") boolean enabled) {
        this.environment = environment; this.clock = clock; this.enabled = enabled;
    }
    public boolean enabled() {
        return enabled && environment.acceptsProfiles(Profiles.of("((local & qa) | test) & !prod & !production"));
    }
    public void requireEnabled() {
        if (!enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "VIDEO_QA_DISABLED", "测试视频接入未启用");
    }
    public synchronized Stream register(String taskId, String targetId, String deviceId, String sourceMode) {
        return register(taskId, targetId, deviceId, sourceMode, () -> { });
    }
    public synchronized Stream register(String taskId, String targetId, String deviceId, String sourceMode, Runnable beforeNewRegistration) {
        requireEnabled();
        long now = clock.nowMillis();
        expire(now);
        Stream previous = streams.get(taskId);
        if(previous!=null && (!java.util.Objects.equals(previous.targetId(),targetId)
                || !java.util.Objects.equals(previous.deviceId(),deviceId) || !java.util.Objects.equals(previous.sourceMode(),sourceMode)))
            throw new ApiException(HttpStatus.CONFLICT,"VIDEO_TASK_MISMATCH","视频租约的设备、目标或来源已变化");
        long otherDevices = streams.values().stream().filter(s -> !s.deviceId().equals(deviceId)).count();
        if (previous == null && otherDevices >= 128)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VIDEO_STREAM_LIMIT", "测试视频数量已达上限");
        // Audit after validation, before publishing the new lease. Failure leaves the prior lease intact.
        if (previous == null) beforeNewRegistration.run();
        streams.values().removeIf(s -> { boolean remove=s.deviceId().equals(deviceId) && !s.taskId().equals(taskId);
            if(remove)frames.remove(s.streamId());return remove; });
        String id = previous == null ? UUID.randomUUID().toString() : previous.streamId();
        Stream stream = new Stream(id, "qa/" + id, taskId, targetId, deviceId, sourceMode, now + LEASE_MS,
                previous != null && previous.observedReady(), "AI", null);
        streams.put(taskId, stream);
        return stream;
    }
    public synchronized Stream registerRaw(String deviceId,String sourceMode,Runnable beforeNewRegistration) {
        return registerRaw(deviceId,sourceMode,null,beforeNewRegistration);
    }
    public synchronized Stream registerRaw(String deviceId,String sourceMode,String sourceIdentity,Runnable beforeNewRegistration) {
        requireEnabled();long now=clock.nowMillis();expire(now);
        Stream previous=rawStreams.get(deviceId);
        if(previous!=null && (!java.util.Objects.equals(previous.sourceMode(),sourceMode)
                || !java.util.Objects.equals(previous.sourceIdentity(),sourceIdentity)))previous=null;
        if(previous==null && !rawStreams.containsKey(deviceId) && rawStreams.size()>=128)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"VIDEO_STREAM_LIMIT","测试视频数量已达上限");
        if(previous==null)beforeNewRegistration.run();
        String id=previous==null?UUID.randomUUID().toString():previous.streamId();
        Stream stream=new Stream(id,"qa/"+id,null,null,deviceId,sourceMode,now+LEASE_MS,
                previous!=null && previous.observedReady(),"RAW",sourceIdentity);
        rawStreams.put(deviceId,stream);return stream;
    }
    public synchronized Stream findRaw(String deviceId) {
        if(!enabled())return null;expire(clock.nowMillis());return rawStreams.get(deviceId);
    }
    public synchronized void removeRaw(String deviceId) { rawStreams.remove(deviceId); }
    private void expire(long now) {
        streams.values().removeIf(s->{boolean expired=s.expiresAt()<=now;if(expired)frames.remove(s.streamId());return expired;});
        rawStreams.values().removeIf(s->s.expiresAt()<=now);
    }
    public synchronized Stream find(String taskId) {
        if (!enabled()) return null;
        Stream stream = streams.get(taskId);
        if (stream != null && stream.expiresAt() <= clock.nowMillis()) { streams.remove(taskId);frames.remove(stream.streamId());return null; }
        return stream;
    }
    public synchronized void remove(String taskId) { Stream s=streams.remove(taskId);if(s!=null)frames.remove(s.streamId()); }
    public synchronized void observedReady(String taskId) {
        Stream s = find(taskId);
        if(s != null && !s.observedReady()) streams.put(taskId,new Stream(s.streamId(),s.streamPath(),s.taskId(),
                s.targetId(),s.deviceId(),s.sourceMode(),s.expiresAt(),true,s.streamKind(),s.sourceIdentity()));
    }
    public synchronized void observedRawReady(String deviceId) {
        Stream s=findRaw(deviceId);
        if(s!=null && !s.observedReady())rawStreams.put(deviceId,new Stream(s.streamId(),s.streamPath(),s.taskId(),
                s.targetId(),s.deviceId(),s.sourceMode(),s.expiresAt(),true,s.streamKind(),s.sourceIdentity()));
    }
    public synchronized void appendFrame(Stream stream,Frame frame) {
        appendFrame(stream,frame,false);
    }
    public synchronized void appendBatchFrame(Stream stream,Frame frame) {
        appendFrame(stream,frame,true);
    }
    private void appendFrame(Stream stream,Frame frame,boolean detailed) {
        Stream current=find(stream.taskId());long now=clock.nowMillis();
        if(current==null || !current.streamId().equals(stream.streamId()))throw invalidFrame();
        var buffer=frames.computeIfAbsent(stream.streamId(),key->new java.util.ArrayDeque<>());
        Frame last=buffer.peekLast();
        if(frame.frameTimestamp()>now)throw frameRejected(detailed,"VIDEO_FRAME_FUTURE","视频帧时间晚于当前时间");
        if(frame.frameTimestamp()<now-5000)throw frameRejected(detailed,"VIDEO_FRAME_STALE","视频帧已超过5秒接收时效");
        if(frame.mediaTimeMs()<0)throw invalidFrame();
        if(last!=null && (frame.frameTimestamp()<=last.frameTimestamp() || frame.mediaTimeMs()<=last.mediaTimeMs()))
            throw frameRejected(detailed,"VIDEO_FRAME_NON_MONOTONIC","视频帧重复或帧时间、媒体时间未递增");
        buffer.addLast(frame);while(buffer.size()>300 || !buffer.isEmpty() && buffer.peekFirst().frameTimestamp()<now-30000)buffer.removeFirst();
    }
    private static ApiException frameRejected(boolean detailed,String code,String reason) {
        return detailed?new ApiException(HttpStatus.CONFLICT,code,reason):invalidFrame();
    }
    public synchronized java.util.List<Frame> frames(Stream stream) {
        if(stream==null)return java.util.List.of();Stream current=find(stream.taskId());
        if(current==null || !current.streamId().equals(stream.streamId()))return java.util.List.of();
        var buffer=frames.get(stream.streamId());if(buffer==null)return java.util.List.of();
        long cutoff=clock.nowMillis()-30000;while(!buffer.isEmpty() && buffer.peekFirst().frameTimestamp()<cutoff)buffer.removeFirst();
        return java.util.List.copyOf(buffer);
    }
    private static ApiException invalidFrame(){return new ApiException(HttpStatus.CONFLICT,"VIDEO_FRAME_INVALID","视频帧关联或时间无效，请使用当前视频流的递增时间");}
    public record Box(double x,double y,double width,double height) { }
    public record Detection(String detectionId,Box bbox,String classCode,Double confidence,
            String associationStatus,String targetId,String associationId,String observationId) { }
    public record Frame(long frameTimestamp,long mediaTimeMs,java.util.List<Detection> detections) { }
    public record Stream(String streamId, String streamPath, String taskId, String targetId,
                         String deviceId, String sourceMode, long expiresAt, boolean observedReady,String streamKind,
                         @com.fasterxml.jackson.annotation.JsonIgnore String sourceIdentity) { }
}
