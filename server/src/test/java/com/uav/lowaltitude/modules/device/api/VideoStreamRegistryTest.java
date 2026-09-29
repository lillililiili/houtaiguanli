package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.platform.time.AppClock;

class VideoStreamRegistryTest {
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
