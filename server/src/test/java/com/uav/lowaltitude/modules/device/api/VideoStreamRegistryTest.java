package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.platform.time.AppClock;

class VideoStreamRegistryTest {
    @Test void rawLeaseSurvivesTaskSwitchButFramesDoNot() {
        var env=new MockEnvironment();env.setActiveProfiles("test");var clock=mock(AppClock.class);
        when(clock.nowMillis()).thenReturn(1000L);
        var registry=new VideoStreamRegistry(env,clock,true);
        var raw=registry.registerRaw("device-a","replay",()->{});
        var ai=registry.register("task-a","target-a","device-a","replay");
        assertThat(raw.streamKind()).isEqualTo("RAW");assertThat(ai.streamKind()).isEqualTo("AI");
        registry.appendFrame(ai,new VideoStreamRegistry.Frame(1000,0,java.util.List.of()));
        registry.register("task-b","target-b","device-a","replay");
        assertThat(registry.findRaw("device-a").streamId()).isEqualTo(raw.streamId());
        assertThat(registry.find("task-a")).isNull();assertThat(registry.frames(ai)).isEmpty();
        when(clock.nowMillis()).thenReturn(61001L);assertThat(registry.findRaw("device-a")).isNull();
    }
    @Test void frameTimelineRejectsReplayFutureAndStaleAndExpiresOldFrames() {
        var env=new MockEnvironment();env.setActiveProfiles("test");var clock=mock(AppClock.class);
        when(clock.nowMillis()).thenReturn(10000L);
        var registry=new VideoStreamRegistry(env,clock,true);
        var ai=registry.register("t","target","d","replay");
        registry.appendFrame(ai,new VideoStreamRegistry.Frame(10000,100,java.util.List.of()));
        assertThatThrownBy(()->registry.appendFrame(ai,new VideoStreamRegistry.Frame(10001,101,java.util.List.of()))).hasMessageContaining("时间");
        assertThatThrownBy(()->registry.appendFrame(ai,new VideoStreamRegistry.Frame(4999,101,java.util.List.of()))).hasMessageContaining("时间");
        assertThatThrownBy(()->registry.appendFrame(ai,new VideoStreamRegistry.Frame(10000,100,java.util.List.of()))).hasMessageContaining("时间");
        when(clock.nowMillis()).thenReturn(41000L);assertThat(registry.frames(ai)).isEmpty();
    }
    @Test void capacityAndAuditFailuresDoNotPublishOrEvictLeases() {
        var env=new MockEnvironment();env.setActiveProfiles("test");var clock=mock(AppClock.class);
        var registry=new VideoStreamRegistry(env,clock,true);
        for(int i=0;i<128;i++) registry.register("t"+i,"target"+i,"d"+i,"replay");
        Runnable audit=mock(Runnable.class);
        assertThatThrownBy(()->registry.register("extra","target","extra","replay",audit)).hasMessageContaining("上限");
        verifyNoInteractions(audit);
        doThrow(new IllegalStateException("audit failed")).when(audit).run();
        assertThatThrownBy(()->registry.register("replacement","target0","d0","replay",audit)).hasMessage("audit failed");
        assertThat(registry.find("t0")).isNotNull();assertThat(registry.find("replacement")).isNull();
        registry.register("replacement","target0","d0","replay");
        assertThat(registry.find("t0")).isNull();assertThat(registry.find("replacement")).isNotNull();
    }
    @Test void productionAndPartialProfilesNeverEnableQa() {
        for(String[] profiles:new String[][]{{"local"},{"qa"},{"local","qa","production"},{"test","prod"}}){
            var env=new MockEnvironment();env.setActiveProfiles(profiles);
            var registry=new VideoStreamRegistry(env,new AppClock(),true);
            assertThat(registry.enabled()).isFalse();assertThatThrownBy(()->registry.register("t","target","d","replay")).hasMessageContaining("未启用");
        }
    }
    @Test void idempotentLeaseExpiresAndDoesNotSurviveRestart() {
        var env=new MockEnvironment();env.setActiveProfiles("local","qa");var clock=mock(AppClock.class);when(clock.nowMillis()).thenReturn(1000L);
        var registry=new VideoStreamRegistry(env,clock,true);
        var first=registry.register("t","target","d","replay");
        assertThat(registry.register("t","target","d","replay").streamId()).isEqualTo(first.streamId());
        assertThat(new VideoStreamRegistry(env,clock,true).find("t")).isNull();
        when(clock.nowMillis()).thenReturn(61001L);assertThat(registry.find("t")).isNull();
        assertThat(registry.register("t","target","d","replay").streamId()).isNotEqualTo(first.streamId());
    }
}
