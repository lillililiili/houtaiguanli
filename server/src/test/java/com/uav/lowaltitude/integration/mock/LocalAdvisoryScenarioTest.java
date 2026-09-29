package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.platform.time.AppClock;

class LocalAdvisoryScenarioTest {
    private static final String PREFIX="app.qa.advisory-scenario.";
    private MockEnvironment environment(String... profiles) {
        var env=new MockEnvironment();env.setActiveProfiles(profiles);
        return env.withProperty(PREFIX+"enabled","true").withProperty(PREFIX+"event-id","qa-event")
                .withProperty(PREFIX+"sms-status","SENT").withProperty(PREFIX+"voice-status","ANSWERED");
    }
    @Test void smsCanReturnSubmittedWithoutClaimingDelivery() {
        var adapter=new LocalAdvisorySmsAdapter(environment("local","qa"));
        assertThat(adapter.simulateAutomatic("replay","测试飞手","测试","auto-advisory:qa-event").status()).isEqualTo("SENT");
    }
    @Test void voiceCanReturnAnsweredWithoutPlaybackCompletion() {
        var recording=new AdvisoryVoiceRecording.Recording("qa","测试","测试","sha");
        var configured=mock(AdvisoryVoiceRecording.class);when(configured.current()).thenReturn(recording);
        var adapter=new LocalAdvisoryVoiceAdapter(environment("test"),configured,new AppClock());
        var result=adapter.simulate("mock",recording,"auto-advisory-voice:qa-event");
        assertThat(result.status()).isEqualTo("ANSWERED");assertThat(result.answeredAt()).isNotNull();
        assertThat(result.playbackCompletedAt()).isNull();assertThat(result.simulated()).isTrue();
    }
    @Test void scenariosDoNotAffectOtherEventsSourcesOrUnqualifiedEnvironments() {
        for(String[] profiles:new String[][]{{"local"},{"local","qa","prod"},{"test","prod"}}) {
            var adapter=new LocalAdvisorySmsAdapter(environment(profiles));
            assertThat(adapter.simulateAutomatic("replay","测试","测试","auto-advisory:qa-event").status()).isEqualTo("SIMULATED_DELIVERED");
        }
        var env=environment("local","qa");var adapter=new LocalAdvisorySmsAdapter(env);
        assertThat(adapter.simulateAutomatic("replay","测试","测试","auto-advisory:another").status()).isEqualTo("SIMULATED_DELIVERED");
        assertThat(adapter.simulateAutomatic("live","测试","测试","auto-advisory:qa-event").status()).isEqualTo("SIMULATED_DELIVERED");
        env.withProperty(PREFIX+"enabled","false");
        assertThat(adapter.simulateAutomatic("replay","测试","测试","auto-advisory:qa-event").status()).isEqualTo("SIMULATED_DELIVERED");
    }
    @Test void invalidScenarioNeverFallsBackToSuccess() {
        for(String[] field:new String[][]{{"sms-status","DELIVERED"},{"delay-ms","-1"},{"delay-ms","60001"},{"delay-ms","invalid"},{"event-id",""}}) {
            var env=environment("test").withProperty(PREFIX+field[0],field[1]);
            var adapter=new LocalAdvisorySmsAdapter(env);
            assertThatThrownBy(()->adapter.simulateAutomatic("replay","测试","测试","auto-advisory:qa-event")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
