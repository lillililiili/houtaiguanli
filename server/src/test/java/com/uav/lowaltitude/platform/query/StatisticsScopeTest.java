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
        assertThat(sql).startsWith("((CASE WHEN d.simulated=TRUE AND d.source_mode='live' THEN 'mock' ELSE d.source_mode END) IN ('live','replay')");
        assertThat(scope("prod").deviceSql("d"))
                .isEqualTo("(CASE WHEN d.simulated=TRUE AND d.source_mode='live' THEN 'mock' ELSE d.source_mode END) IN ('live')");
    }

    /** 后台预置、从不上报的回放设备是系统自带的样例：模拟器的设备要上报过才算，真实设备不论。 */
    @Test
    void simulatorDevicesCountOnlyOnceTheyHaveReported() {
        String sql = scope("local", "qa").deviceSql("d");
        assertThat(sql).contains("d.source_mode<>'replay' OR EXISTS (SELECT 1 FROM ops_device_state reported WHERE reported.device_id=d.device_id"
                + " AND (reported.last_heartbeat_at IS NOT NULL OR reported.observed_at IS NOT NULL))");
        // 正式环境根本不算模拟器，也就不必查上报。
        assertThat(scope("prod").deviceSql("d")).doesNotContain("ops_device_state");
    }

    private static StatisticsScope scope(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new StatisticsScope(new SimulationPolicy(env));
    }
}
