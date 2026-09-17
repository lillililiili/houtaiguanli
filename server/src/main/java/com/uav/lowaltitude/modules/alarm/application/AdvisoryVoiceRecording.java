package com.uav.lowaltitude.modules.alarm.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sound.sampled.AudioSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只读取部署端明确配置的已有 WAV 文件；不生成录音，不接受客户端文件路径。 */
@Component
public class AdvisoryVoiceRecording {
    private final String id,name,path,transcript;
    public AdvisoryVoiceRecording(@Value("${app.advisory.auto-voice.recording-id:}") String id,
            @Value("${app.advisory.auto-voice.recording-name:}") String name,
            @Value("${app.advisory.auto-voice.recording-path:}") String path,
            @Value("${app.advisory.auto-voice.recording-transcript:}") String transcript) {
        this.id=id.trim();this.name=name.trim();this.path=path.trim();this.transcript=transcript.trim();
    }
    public Recording current() {
        if(id.isEmpty()||id.length()>100||name.isEmpty()||name.length()>120||path.isEmpty()||transcript.isEmpty()||transcript.length()>1000)return null;
        try {
            Path file=Path.of(path);
            if(!file.isAbsolute()||!Files.isRegularFile(file)||Files.size(file)<44||Files.size(file)>10*1024*1024)return null;
            byte[] data=Files.readAllBytes(file);
            if(data.length<44||data[0]!='R'||data[1]!='I'||data[2]!='F'||data[3]!='F'||data[8]!='W'||data[9]!='A'||data[10]!='V'||data[11]!='E')return null;
            try(var audio=AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(data))) {
                long frames=audio.getFrameLength();int frameSize=audio.getFormat().getFrameSize();float frameRate=audio.getFormat().getFrameRate();
                if(frames<=0||frameSize<=0||!Float.isFinite(frameRate)||frameRate<=0)return null;
                long expectedBytes=Math.multiplyExact(frames,frameSize);
                if(expectedBytes<=0||expectedBytes>data.length||audio.readAllBytes().length!=expectedBytes)return null;
            }
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            return new Recording(id,name,transcript,sha);
        } catch(Exception invalid) {return null;}
    }
    public record Recording(String id,String name,String transcript,String sha256) { }
}
