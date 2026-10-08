package com.uav.lowaltitude.modules.alarm.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.sound.sampled.AudioSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository.Row;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;

/**
 * 电话通知播放的录音。后台“接口配置 → 电话通知录音”选用的上传录音优先；没有选用时才读部署端启动参数
 * （app.advisory.auto-voice.recording-*）配置的已有 WAV 文件。已选用的上传录音文件缺失或损坏时视为不可用，
 * 不悄悄改播启动参数里的另一段录音。不生成录音，不接受客户端文件路径；每次使用都按真实文件内容校验。
 */
@Component
public class AdvisoryVoiceRecording {
    /** 电话通道只支持完整可读的 WAV（RIFF/WAVE）音频，单个文件上限 10 MiB。 */
    public static final long MAX_BYTES=10L*1024*1024;
    public enum Source { UPLOADED, STARTUP_CONFIG, NONE }
    private final String id,name,path,transcript;
    private final AdvisoryVoiceRecordingRepository uploaded;
    private final ObjectStoragePort storage;
    /** 只用启动参数（隔离测试与旧调用方），没有后台上传的录音。 */
    public AdvisoryVoiceRecording(String id,String name,String path,String transcript) {this(id,name,path,transcript,null,null);}
    @Autowired
    public AdvisoryVoiceRecording(@Value("${app.advisory.auto-voice.recording-id:}") String id,
            @Value("${app.advisory.auto-voice.recording-name:}") String name,
            @Value("${app.advisory.auto-voice.recording-path:}") String path,
            @Value("${app.advisory.auto-voice.recording-transcript:}") String transcript,
            AdvisoryVoiceRecordingRepository uploaded,ObjectStoragePort storage) {
        this.id=id.trim();this.name=name.trim();this.path=path.trim();this.transcript=transcript.trim();
        this.uploaded=uploaded;this.storage=storage;
    }
    /** 当前可以播放的录音；没有或文件不可用时返回 null。 */
    public Recording current() {return resolve().recording();}
    /** 当前生效来源及录音；只读。uploadedId/uploadedName 只在后台选用了上传录音时出现。 */
    public Resolution resolve() {
        Row active=uploaded==null?null:uploaded.active();
        if(active!=null)return new Resolution(Source.UPLOADED,verified(active),active.recordingId(),active.name());
        Recording configured=configured();
        return new Resolution(configured==null?Source.NONE:Source.STARTUP_CONFIG,configured,null,null);
    }
    /** 启动参数配置的录音（不论后台是否选用了上传录音）；没有或文件无效时返回 null。 */
    public Recording startup() {return configured();}
    /** 读取已上传录音的真实内容并核对入库时的哈希与 WAV 格式；不可用时返回 null。 */
    public Recording verified(Row row) {
        byte[] data=read(row);
        return data!=null&&wave(data)!=null&&row.sha256().equals(sha256(data))?new Recording(row.recordingId(),row.name(),row.transcript(),row.sha256()):null;
    }
    /** 已上传录音的原始字节；文件缺失、超限或与入库哈希不一致时返回 null。 */
    public byte[] content(Row row) {
        byte[] data=read(row);
        return data!=null&&row.sha256().equals(sha256(data))?data:null;
    }
    /** 读取当前通知任务绑定的真实录音字节；仅接受当前生效录音的完整元数据匹配。 */
    public byte[] content(Recording recording) {
        if (recording == null) return null;
        Row active = uploaded == null ? null : uploaded.active();
        if (active != null) {
            return same(active.recordingId(), active.name(), active.transcript(), active.sha256(), recording)
                    ? content(active) : null;
        }
        Recording configured = configured();
        if (configured == null || !configured.equals(recording)) return null;
        try {
            byte[] data = Files.readAllBytes(Path.of(path));
            return wave(data) != null && configured.sha256().equals(sha256(data)) ? data : null;
        } catch (Exception unavailable) {
            return null;
        }
    }
    private static boolean same(String id, String name, String transcript, String sha256, Recording recording) {
        return Objects.equals(id, recording.id()) && Objects.equals(name, recording.name())
                && Objects.equals(transcript, recording.transcript()) && Objects.equals(sha256, recording.sha256());
    }
    private byte[] read(Row row) {
        if(storage==null)return null;
        try {
            var opened=storage.open(row.objectKey());
            if(opened.isEmpty())return null;
            try(InputStream in=opened.get()) {
                byte[] data=in.readNBytes((int)MAX_BYTES+1);
                return data.length>MAX_BYTES?null:data;
            }
        } catch(IOException|RuntimeException unavailable) {return null;}
    }
    private Recording configured() {
        if(id.isEmpty()||id.length()>100||name.isEmpty()||name.length()>120||path.isEmpty()||transcript.isEmpty()||transcript.length()>1000)return null;
        try {
            Path file=Path.of(path);
            if(!file.isAbsolute()||!Files.isRegularFile(file)||Files.size(file)<44||Files.size(file)>MAX_BYTES)return null;
            byte[] data=Files.readAllBytes(file);
            return wave(data)==null?null:new Recording(id,name,transcript,sha256(data));
        } catch(Exception invalid) {return null;}
    }
    /** 校验 RIFF/WAVE 头、可读的音频格式和完整音频帧；截断、空壳或其他格式返回 null。 */
    public static Wave wave(byte[] data) {
        if(data==null||data.length<44||data.length>MAX_BYTES||data[0]!='R'||data[1]!='I'||data[2]!='F'||data[3]!='F'||data[8]!='W'||data[9]!='A'||data[10]!='V'||data[11]!='E')return null;
        try(var audio=AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
            var format=audio.getFormat();
            long frames=audio.getFrameLength();int frameSize=format.getFrameSize();float frameRate=format.getFrameRate();
            if(frames<=0||frameSize<=0||!Float.isFinite(frameRate)||frameRate<=0||!(format.getSampleRate()>0)||format.getChannels()<=0)return null;
            long expectedBytes=Math.multiplyExact(frames,frameSize);
            if(expectedBytes<=0||expectedBytes>data.length||audio.readAllBytes().length!=expectedBytes)return null;
            long millis=Math.max(1,Math.round(frames*1000d/frameRate));
            return new Wave(millis,Math.round(format.getSampleRate()),format.getChannels());
        } catch(Exception invalid) {return null;}
    }
    public static String sha256(byte[] data) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(NoSuchAlgorithmException unavailable) {throw new IllegalStateException(unavailable);}
    }
    public record Recording(String id,String name,String transcript,String sha256) { }
    public record Resolution(Source source,Recording recording,String uploadedId,String uploadedName) { }
    public record Wave(long durationMillis,int sampleRate,int channels) { }
}
