package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.DegradationLevel;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.DegradationEvaluator.Degradation;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuserTest.MapParams;

/** 降级只由可用来源数决定（不看来源类型），阈值全部来自参数；无源时逐帧累进，越过阈值即“不可判定”，置信度必须为 null 而不是 0。 */
class DegradationEvaluatorTest {
    private final DegradationEvaluator evaluator = new DegradationEvaluator();

    @Test
    void fourLevelsMapToParameterisedDeficits() {
        Degradation three = evaluator.evaluate(List.of(src("radar", "RADAR"), src("eo", "EO"), src("tdoa", "TDOA")), 0, null, MapParams.demo());
        assertThat(three.level()).isEqualTo(DegradationLevel.THREE_SOURCE);
        assertThat(three.deficit()).isEqualTo(0.0);
        assertThat(three.determined()).isTrue();
        assertThat(three.fusionConfidence()).isEqualTo(1.0);
        assertThat(three.availableSourceIds()).containsExactly("eo", "radar", "tdoa");

        // 不足三源但多于一源：两台不同设备（雷达加光电、两台雷达）都按部分降级记账。
        Degradation two = evaluator.evaluate(List.of(src("box-radar", "RADAR"), src("box-eo", "EO")), 0, null, MapParams.demo());
        assertThat(two.level()).isEqualTo(DegradationLevel.FUSION_BOX_ONLY);
        assertThat(two.deficit()).isEqualTo(0.2);
        assertThat(two.fusionConfidence()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evaluator.evaluate(List.of(src("radar-a", "RADAR"), src("radar-b", "RADAR")), 0, null, MapParams.demo()).level())
                .isEqualTo(DegradationLevel.FUSION_BOX_ONLY);

        Degradation single = evaluator.evaluate(List.of(src("tdoa", "TDOA")), 0, null, MapParams.demo());
        assertThat(single.level()).isEqualTo(DegradationLevel.SINGLE_SOURCE);
        assertThat(single.deficit()).isEqualTo(0.35);
        assertThat(single.determined()).isTrue();
        assertThat(single.fusionConfidence()).isCloseTo(0.65, org.assertj.core.data.Offset.offset(1e-9));

        Degradation none = evaluator.evaluate(List.of(), 1, 0.0, MapParams.demo());
        assertThat(none.level()).isEqualTo(DegradationLevel.NONE);
        assertThat(none.deficit()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(none.availableSourceIds()).isEmpty();
    }

    /** CDX-P04：只有一台雷达或一台融合感知箱看到也只算一路，和一台 TDOA 一样是 65%，不再按两路（80%）记账。 */
    @Test
    void radarOrFusionBoxAloneIsOneSource() {
        for (String type : List.of("RADAR", "FUSION_BOX")) {
            Degradation alone = evaluator.evaluate(List.of(src("only", type)), 0, null, MapParams.demo());
            assertThat(alone.level()).as(type).isEqualTo(DegradationLevel.SINGLE_SOURCE);
            assertThat(alone.deficit()).as(type).isEqualTo(0.35);
            assertThat(alone.fusionConfidence()).as(type).isCloseTo(0.65, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(alone.availableSourceIds()).as(type).containsExactly("only");
        }
        // 同一台设备一帧报了两条（同一来源）仍是一路。
        Degradation sameSource = evaluator.evaluate(List.of(src("radar", "RADAR"), src("radar", "RADAR")), 0, null, MapParams.demo());
        assertThat(sameSource.level()).isEqualTo(DegradationLevel.SINGLE_SOURCE);
    }

    @Test
    void lostFramesAccumulateUntilUndetermined() {
        Degradation single = evaluator.evaluate(List.of(src("tdoa", "TDOA")), 0, null, MapParams.demo());
        Degradation lost1 = evaluator.evaluate(List.of(), 1, single.deficit(), MapParams.demo());
        assertThat(lost1.deficit()).isCloseTo(0.45, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(lost1.determined()).isTrue();
        assertThat(lost1.fusionConfidence()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-9));
        Degradation lost2 = evaluator.evaluate(List.of(), 2, lost1.deficit(), MapParams.demo());
        assertThat(lost2.deficit()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(lost2.determined()).isFalse();
        // 不可判定时置信度不能是 0（0 是“确定不可信”），只能缺省并记 UNSUPPORTED。
        assertThat(lost2.fusionConfidence()).isNull();
        assertThat(lost2.unknownFields()).singleElement().satisfies(u -> {
            assertThat(u.field()).isEqualTo("fusion_confidence");
            assertThat(u.reasonCode()).isEqualTo("UNSUPPORTED");
        });
        // 累进封顶为 1，不越界。
        assertThat(evaluator.evaluate(List.of(), 9, 0.95, MapParams.demo()).deficit()).isEqualTo(1.0);
        // 来源恢复后重新按等级计算，不继承累进值。
        assertThat(evaluator.evaluate(List.of(src("radar", "RADAR"), src("eo", "EO"), src("tdoa", "TDOA")), 0, lost2.deficit(), MapParams.demo()).deficit()).isEqualTo(0.0);
    }

    private static SourceEstimate src(String id, String type) {
        return WeightedFuserTest.estimate(id, type, WeightedFuserTest.LON, WeightedFuserTest.LAT, 20.0, null, null, null, null, null, null, null);
    }
}
