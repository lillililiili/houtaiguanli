package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class AutoVoicePolicy {
    public static final String CODE="LOCAL_AUTO_VOICE_DEMO_V1";
    private final Environment environment;
    private final boolean configured;
    public AutoVoicePolicy(Environment environment,@Value("${app.advisory.auto-voice.enabled:false}") boolean configured) {
        this.environment=environment;this.configured=configured;
    }
    public boolean enabled(){return configured&&environment.acceptsProfiles(Profiles.of("!production & (local | test)"));}
}
