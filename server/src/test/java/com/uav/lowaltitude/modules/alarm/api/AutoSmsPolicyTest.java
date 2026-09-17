package com.uav.lowaltitude.modules.alarm.api;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsPolicy;
class AutoSmsPolicyTest {
    @Test void demoPolicyRequiresExplicitFlagAndDevelopmentProfile() {
        for(String profile:new String[]{"production","local","test"}) {
            var env=new MockEnvironment();env.setActiveProfiles(profile);
            assertThat(new AutoSmsPolicy(env,false,120,300).enabled()).isFalse();
            assertThat(new AutoSmsPolicy(env,true,120,300).enabled()).isEqualTo(!"production".equals(profile));
        }
        for(String development:new String[]{"local","test"}) {
            var mixed=new MockEnvironment();mixed.setActiveProfiles("production",development);
            assertThat(new AutoSmsPolicy(mixed,true,120,300).enabled()).isFalse();
        }
    }
    @Test void freshnessNeverTreatsMissingOrFutureFactsAsUsable() {
        var policy=new AutoSmsPolicy(new MockEnvironment(),false,120,300);
        assertThat(policy.fresh(null,1000,100)).isFalse();assertThat(policy.fresh(1001L,1000,100)).isFalse();
        assertThat(policy.fresh(900L,1000,100)).isTrue();assertThat(policy.fresh(899L,1000,100)).isFalse();
    }
}
