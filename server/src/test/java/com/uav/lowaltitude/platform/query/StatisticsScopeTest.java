package com.uav.lowaltitude.platform.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.uav.lowaltitude.platform.config.SimulationPolicy;

class StatisticsScopeTest {

    @Test
    void qaAndTestCountTheDeviceSimulatorButNeverTheBuiltInDemoSamples() {
        for (String[] profiles : List.of(new String[]{"local", "qa"}, new String[]{"test"})) {
            StatisticsScope scope = scope(profiles);
            assertThat(scope.sourceModes()).containsExactly("live", "replay");
            assertThat(scope.counted("replay")).isTrue();
            assertThat(scope.counted("mock")).isFalse();
            assertThat(scope.sqlIn()).isEqualTo("('live','replay')");
        }
    }

    @Test
    void productionCountsFormalDataOnlyEvenWithHistoricalSimulatorRows() {
        for (String[] profiles : List.of(new String[]{}, new String[]{"prod"}, new String[]{"local", "qa", "production"},
                new String[]{"test", "prod"})) {
            StatisticsScope scope = scope(profiles);
            assertThat(scope.sourceModes()).containsExactly("live");
            assertThat(scope.counted("replay")).isFalse();
            assertThat(scope.sqlIn()).isEqualTo("('live')");
        }
    }

    @Test
    void localSimulatedLiveDevicesAreTreatedAsDemoSamplesLikeTheDeviceList() {
        String sql = scope("test").deviceSql("d");
        assertThat(sql).isEqualTo("(CASE WHEN d.simulated=TRUE AND d.source_mode='live' THEN 'mock' ELSE d.source_mode END) IN ('live','replay')");
        assertThat(scope("prod").deviceSql("d")).endsWith(" IN ('live')");
    }

    private static StatisticsScope scope(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new StatisticsScope(new SimulationPolicy(env));
    }
}
