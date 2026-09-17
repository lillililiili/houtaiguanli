package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.alarm.application.*;
import com.uav.lowaltitude.integration.mock.LocalAdvisoryVoiceAdapter;
import com.uav.lowaltitude.platform.time.AppClock;

class AutoVoiceRecordingTest {
    @TempDir Path directory;
    @Test void unavailableWithoutAllRecordingMetadataOrRealWaveContent()throws Exception {
        Path file=directory.resolve("invalid.wav");Files.writeString(file,"this is not a recording");
        assertThat(new AdvisoryVoiceRecording("recording","录音",file.toString(),"实际文稿").current()).isNull();
        assertThat(new AdvisoryVoiceRecording("recording","录音",directory.resolve("missing.wav").toString(),"实际文稿").current()).isNull();
        assertThat(new AdvisoryVoiceRecording("","","","").current()).isNull();
    }
    @Test void headerOnlyAndTruncatedWaveAreRejected()throws Exception {
        byte[] complete=new byte[244];var buffer=java.nio.ByteBuffer.wrap(complete).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(236).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(200);
        Path file=directory.resolve("fixture.wav");Files.write(file,complete);
        var recording=new AdvisoryVoiceRecording("fixture","隔离测试样本",file.toString(),"用于验证格式，不含业务语音");
        assertThat(recording.current()).isNotNull();
        Files.write(file,java.util.Arrays.copyOf(complete,44));assertThat(recording.current()).isNull();
        Files.write(file,java.util.Arrays.copyOf(complete,64));assertThat(recording.current()).isNull();
    }
    @Test void defaultAndProductionCannotActivateSimulationEvenWithMixedProfiles() {
        for(String[] profiles:new String[][]{{"production"},{"production","local"},{"production","test"},{"default"},{"local"},{"test"}}) {
            var env=new MockEnvironment();env.setActiveProfiles(profiles);
            boolean allowed=profiles.length==1&&(profiles[0].equals("local")||profiles[0].equals("test"));
            assertThat(new AutoVoicePolicy(env,false).enabled()).isFalse();
            assertThat(new AutoVoicePolicy(env,true).enabled()).isEqualTo(allowed);
            var adapter=new LocalAdvisoryVoiceAdapter(env,new AdvisoryVoiceRecording("","","",""),new AppClock());
            assertThat(adapter.simulationAvailable("mock")).isEqualTo(allowed);
            assertThat(adapter.simulationAvailable("replay")).isEqualTo(allowed);
            assertThat(adapter.simulationAvailable("live")).isFalse();
        }
    }
}
