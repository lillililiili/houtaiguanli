package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.Measurement;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.State;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.Update;

/** α-β 单源滤波：纯 Java，米制换算在 Java 侧完成，不依赖任何 SQL 几何。 */
class AlphaBetaFilterTest {
    private static final double LON0 = 118.60, LAT0 = 37.40;
    private static final long T0 = 1_757_073_600_000L;
    private final AlphaBetaFilter filter = new AlphaBetaFilter(DemoFusionParams.demoV1());

    @Test
    void predictionErrorConvergesForConstantVelocityTarget() {
        // 匀速向东 10 m/s，1 Hz 无噪声观测：第 2 步的预测误差（速度未知）应明显大于后期，末期误差接近 0。
        double eastPerSecond = 10.0;
        State state = null;
        List<Double> errors = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            long t = T0 + k * 1000L;
            double[] truth = AlphaBetaFilter.fromEnu(LON0, LAT0, eastPerSecond * k, 0.0);
            if (state != null) {
                State predicted = filter.predict(state, t);
                errors.add(AlphaBetaFilter.distanceM(predicted.longitude(), predicted.latitude(), truth[0], truth[1]));
            }
            state = filter.update(state, new Measurement(truth[0], truth[1], 15.0, t), "RADAR").state();
        }
        assertThat(errors).hasSize(9);
        assertThat(errors.get(0)).as("首次预测速度未知，误差约为一帧位移").isGreaterThan(5.0);
        assertThat(errors.get(8)).as("收敛后误差").isLessThan(1.0);
        assertThat(errors.get(8)).isLessThan(errors.get(0));
        assertThat(state.speedMps()).isBetween(9.0, 11.0);
        assertThat(state.headingDeg()).isBetween(85.0, 95.0);
    }

    @Test
    void gapLongerThanMaxDtReinitializesWithoutVelocity() {
        State state = filter.update(null, new Measurement(LON0, LAT0, 15.0, T0), "RADAR").state();
        state = filter.update(state, new Measurement(LON0 + 0.0001, LAT0, 15.0, T0 + 1000), "RADAR").state();
        assertThat(state.velocityKnown()).isTrue();
        // 3000 ms 是 filter.max_dt_ms：超过它的间隙不能用旧速度外推，必须重新初始化。
        Update after = filter.update(state, new Measurement(LON0 + 0.001, LAT0, 15.0, T0 + 6000), "RADAR");
        assertThat(after.reinitialized()).isTrue();
        assertThat(after.state().velocityKnown()).isFalse();
        assertThat(after.state().speedMps()).isNull();
        assertThat(after.state().tMillis()).isEqualTo(T0 + 6000);
    }

    @Test
    void outOfOrderMeasurementDoesNotRewindTheState() {
        State state = filter.update(null, new Measurement(LON0, LAT0, 15.0, T0), "RADAR").state();
        state = filter.update(state, new Measurement(LON0 + 0.0001, LAT0, 15.0, T0 + 1000), "RADAR").state();
        Update late = filter.update(state, new Measurement(LON0 - 0.0005, LAT0, 15.0, T0 - 2000), "RADAR");
        // 迟到观测不能把状态时间倒退；原始层照写由管线负责，滤波状态保持最新。
        assertThat(late.outOfOrder()).isTrue();
        assertThat(late.state()).isSameAs(state);
    }

    @Test
    void missingAccuracyUsesCatalogDefaultAndFlagsIt() {
        Update update = filter.update(null, new Measurement(LON0, LAT0, null, T0), "RADAR");
        assertThat(update.accuracyDefaulted()).isTrue();
        assertThat(update.accuracyUsedM()).isEqualTo(15.0);
        assertThat(update.state().accuracyM()).isEqualTo(15.0);
        Update explicit = filter.update(null, new Measurement(LON0, LAT0, 60.0, T0), "TDOA");
        assertThat(explicit.accuracyDefaulted()).isFalse();
        assertThat(explicit.accuracyUsedM()).isEqualTo(60.0);
        // 目录里没有的来源类型是部署错误，不能悄悄用别的默认值。
        assertThatThrownBy(() -> filter.update(null, new Measurement(LON0, LAT0, null, T0), "LIDAR"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void filterStateRoundTripsThroughJsonMap() {
        State state = filter.update(null, new Measurement(LON0, LAT0, 15.0, T0), "RADAR").state();
        state = filter.update(state, new Measurement(LON0 + 0.0001, LAT0 + 0.0001, 15.0, T0 + 1000), "RADAR").state();
        State restored = State.fromMap(state.toMap());
        assertThat(restored).isEqualTo(state);
    }
}
