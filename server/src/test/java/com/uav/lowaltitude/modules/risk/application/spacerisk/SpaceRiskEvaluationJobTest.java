package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RuleVersionRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * BUG-17 / OBS-06：C04 定时评估的窗口与开关。
 * 定时窗口按服务器处理时间推进并回叠一段：融合积压或设备时钟偏差时，最新状态的观测时刻会落在上一轮已经算过的
 * 观测窗口里，按观测时刻切窗口就会"有时不出风险"。开关缺省打开，测试 profile 显式关闭。
 */
class SpaceRiskEvaluationJobTest {
    private static final Instant T0 = Instant.parse("2026-10-04T12:56:35Z");

    @Test
    void consecutiveTicksOverlapInProcessingTimeAndKeepTheLookbackAsObservationFloor() {
        SpaceRiskEvaluationService service = mock(SpaceRiskEvaluationService.class);
        AppClock clock = mock(AppClock.class);
        SpaceRiskEvaluationJob job = job(service, clock);

        when(clock.now()).thenReturn(T0);
        job.tick();
        verify(service).evaluateScheduled(at(T0.minusSeconds(1800)), at(T0), at(T0.minusSeconds(1800)));

        // 下一轮从上一轮终点前 30 秒开始：上一轮查询之后才提交、写入时刻却早于上一轮终点的最新状态仍会被看到。
        when(clock.now()).thenReturn(T0.plusSeconds(60));
        job.tick();
        verify(service).evaluateScheduled(at(T0.minusSeconds(30)), at(T0.plusSeconds(60)), at(T0.plusSeconds(60).minusSeconds(1800)));
        // 旧的观测窗口接口不再被定时任务使用。
        verify(service, never()).evaluate(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedRunDoesNotAdvanceTheWindowButNeverReachesFurtherBackThanTheLookback() {
        SpaceRiskEvaluationService service = mock(SpaceRiskEvaluationService.class);
        AppClock clock = mock(AppClock.class);
        SpaceRiskEvaluationJob job = job(service, clock);
        when(clock.now()).thenReturn(T0);
        job.tick();

        when(clock.now()).thenReturn(T0.plusSeconds(60));
        when(service.evaluateScheduled(any(), any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        job.tick();
        // 整轮失败：下一轮仍从上一次成功的终点（回叠后）算起，这段时间的写入不会被跳过。
        assertThat(job.windowFrom(at(T0.plusSeconds(120)))).isEqualTo(at(T0.minusSeconds(30)));
        // 长时间失败后窗口也不会无限变长：最多回看 window-minutes。
        assertThat(job.windowFrom(at(T0.plusSeconds(7200)))).isEqualTo(at(T0.plusSeconds(7200 - 1800)));
    }

    /** 新-27：同一轮接着跑 C05（机场区域异物），各记各的窗口；没有启用的机场就不跑，C04 失败也不拖住 C05。 */
    @Test
    void airportZoneRunsOnTheSameTickWithItsOwnWindowOnlyWhenAnAirportIsEnabled() {
        SpaceRiskEvaluationService service = mock(SpaceRiskEvaluationService.class);
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE))
                .thenReturn(new RuleVersionRow("SPACE-RISK-DEMO", "space-risk-confirmed-v2", 2, "CONFIRMED"));
        AppClock clock = mock(AppClock.class);
        SpaceRiskEvaluationJob job = new SpaceRiskEvaluationJob(service, repository, new RuleEngineProperties(), clock, 30);

        when(clock.now()).thenReturn(T0);
        job.tick();
        verify(service, never()).evaluateScheduledAirport(any(), any(), any());

        when(repository.anyEnabledAirport()).thenReturn(true);
        when(service.evaluateScheduled(any(), any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        when(clock.now()).thenReturn(T0.plusSeconds(60));
        job.tick();
        verify(service).evaluateScheduledAirport(at(T0.plusSeconds(60 - 1800)), at(T0.plusSeconds(60)), at(T0.plusSeconds(60 - 1800)));
        // C04 这一轮失败、窗口不动；C05 照样推进自己的窗口。
        assertThat(job.windowFrom(at(T0.plusSeconds(120)))).isEqualTo(at(T0.minusSeconds(30)));
        assertThat(job.airportWindowFrom(at(T0.plusSeconds(120)))).isEqualTo(at(T0.plusSeconds(30)));
    }

    @Test
    void skippedTicksDoNotEvaluate() {
        SpaceRiskEvaluationService service = mock(SpaceRiskEvaluationService.class);
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE)).thenReturn(null);
        AppClock clock = mock(AppClock.class);
        when(clock.now()).thenReturn(T0);
        new SpaceRiskEvaluationJob(service, repository, new RuleEngineProperties(), clock, 30).tick();
        verify(service, never()).evaluateScheduled(any(), any(), any());
        verify(service, never()).evaluateScheduledAirport(any(), any(), any());
    }

    @Test
    void scheduledEvaluationIsOnByDefaultInEveryDeploymentProfile() {
        for (String profiles : new String[] {"default", "local", "local,qa", "production"}) {
            context(profiles).run(ctx -> assertThat(ctx).as(profiles).hasSingleBean(SpaceRiskEvaluationJob.class));
        }
    }

    @Test
    void scheduledEvaluationCanStillBeTurnedOffAndStaysOffInTests() {
        context("local,qa").withPropertyValues("app.rule-engine.c04.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SpaceRiskEvaluationJob.class));
        context("test").run(ctx -> assertThat(ctx).doesNotHaveBean(SpaceRiskEvaluationJob.class));
    }

    private static ApplicationContextRunner context(String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles)
                .withBean(SpaceRiskEvaluationService.class, () -> mock(SpaceRiskEvaluationService.class))
                .withBean(SpaceRiskRepository.class, () -> mock(SpaceRiskRepository.class))
                .withBean(RuleEngineProperties.class, RuleEngineProperties::new)
                .withBean(AppClock.class, AppClock::new)
                .withUserConfiguration(SpaceRiskEvaluationJob.class);
    }

    private static SpaceRiskEvaluationJob job(SpaceRiskEvaluationService service, AppClock clock) {
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE))
                .thenReturn(new RuleVersionRow("SPACE-RISK-DEMO", "space-risk-confirmed-v2", 2, "CONFIRMED"));
        return new SpaceRiskEvaluationJob(service, repository, new RuleEngineProperties(), clock, 30);
    }

    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
