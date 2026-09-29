package com.uav.lowaltitude.integration.mock;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** 模拟状态回执，不拨号、不实际播放音频，也不代表真实接收人听到录音。 */
@Component
public class LocalAdvisoryVoiceAdapter implements AdvisoryVoicePort {
    private final Environment environment;
    private final AdvisoryVoiceRecording configuredRecording;
    private final AppClock clock;
    public LocalAdvisoryVoiceAdapter(Environment environment,AdvisoryVoiceRecording recording,AppClock clock) {
        this.environment=environment;this.configuredRecording=recording;this.clock=clock;
    }
    public boolean simulationAvailable(String mode) {
        return environment.acceptsProfiles(Profiles.of("!production & (local | test)"))&&mode!=null&&!mode.isBlank();
    }
    public Delivery simulate(String mode,AdvisoryVoiceRecording.Recording recording,String key) {
        if(!simulationAvailable(mode)||recording==null||!recording.equals(configuredRecording.current()))
            throw new ApiException(HttpStatus.CONFLICT,"VOICE_CHANNEL_UNAVAILABLE","电话通道或已配置录音不可用，不能模拟播放");
        var scenario=LocalAdvisoryScenario.read(environment,mode,key,true);
        if(scenario!=null) {
            scenario.awaitResponse();
            long respondedAt=clock.nowMillis();
            boolean answered="ANSWERED".equals(scenario.status())||"SIMULATED_PLAYED".equals(scenario.status());
            return new Delivery(true,scenario.status(),"simulation:"+key,answered?respondedAt:null,
                    "SIMULATED_PLAYED".equals(scenario.status())?respondedAt:null);
        }
        long now=clock.nowMillis();
        return new Delivery(true,"SIMULATED_PLAYED","simulation:"+key,now,now);
    }
}
