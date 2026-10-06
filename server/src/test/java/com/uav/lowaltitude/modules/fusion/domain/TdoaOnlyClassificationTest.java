package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.DegradationLevel;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.Selection;
import com.uav.lowaltitude.modules.fusion.domain.DegradationEvaluator.Degradation;

/**
 * 只有 TDOA 看到的无人机（验收问题 BUG-14）：PostgreSQL 迁移 V202610069002 把 demo-v1 的 weights.TDOA.class 从 0 调到与 5G-A 相同的 0.2。
 * 类别权重为 0 时 TDOA 报的"无人机"不进融合类别，目标一直是未知类别、进不了合法性研判队列；调到 0.2 后按无人机研判，
 * 单源置信度与单源 5G-A 一样是 0.65，低于 C03.conf_min（0.75），研判给"不可判定"交人工复核，不会只凭 TDOA 判违规。
 */
class TdoaOnlyClassificationTest {
    private static final double LON = 118.5, LAT = 37.4;
    private static final String TDOA_CLASS_ZERO = "\"TDOA\":{\"position\":0.25,\"motion\":0.2,\"class\":0.0,\"identity\":0.5}";
    private final AttributeSelector selector = new AttributeSelector();
    private final DegradationEvaluator degradation = new DegradationEvaluator();

    @Test
    void tdoaOnlyUavReportBecomesTheFusedClassOnceTdoaHasAClassWeight() {
        SourceEstimate tdoa = WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, "UAV", null, "SN-T1");
        // 迁移前：类别权重 0，TDOA 的无人机类别被丢掉。
        Selection before = selector.select(List.of(tdoa), DemoFusionParams.demoV1(), null);
        assertThat(before.classCode()).isNull();
        // 迁移后：与 5G-A 同权重，TDOA 单源也能给出无人机类别；身份线索照旧来自 TDOA，类别置信度不由权重冒充。
        Selection after = selector.select(List.of(tdoa), afterMigration(), null);
        assertThat(after.classCode()).isEqualTo("UAV");
        assertThat(after.classSourceId()).isEqualTo("tdoa");
        assertThat(after.identityClue()).isEqualTo("SN-T1");
        assertThat(after.classConfidence()).isNull();
        // 单源 5G-A 在同一参数下得到同样的类别。
        SourceEstimate fiveGa = WeightedFuserTest.estimate("5ga", "FIVE_G_A", LON, LAT, 80.0, null, null, null, null, "UAV", null, "SN-T1");
        assertThat(selector.select(List.of(fiveGa), afterMigration(), null).classCode()).isEqualTo("UAV");
    }

    @Test
    void tdoaOnlyConfidenceMatchesFiveGaOnlyAndStaysBelowTheLegalityQualityGate() {
        Degradation tdoaOnly = degradation.evaluate(List.of(WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, "UAV", null, null)),
                0, null, afterMigration());
        Degradation fiveGaOnly = degradation.evaluate(List.of(WeightedFuserTest.estimate("5ga", "FIVE_G_A", LON, LAT, 80.0, null, null, null, null, "UAV", null, null)),
                0, null, afterMigration());
        assertThat(tdoaOnly.level()).isEqualTo(DegradationLevel.SINGLE_SOURCE).isEqualTo(fiveGaOnly.level());
        assertThat(tdoaOnly.fusionConfidence()).isCloseTo(0.65, org.assertj.core.data.Offset.offset(1e-9)).isEqualTo(fiveGaOnly.fusionConfidence());
        assertThat(tdoaOnly.fusionConfidence()).isLessThan(0.75);
    }

    @Test
    void conflictingEoClassStillMakesTheClassUnknownInsteadOfPickingASide() {
        SourceEstimate eo = WeightedFuserTest.estimate("eo", "EO", LON, LAT, 25.0, null, null, null, null, "BIRD", 0.9, null);
        SourceEstimate tdoa = WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, "UAV", null, "SN-T1");
        Selection selection = selector.select(List.of(eo, tdoa), afterMigration(), null);
        assertThat(selection.classCode()).isNull();
        assertThat(selection.unknownFields()).extracting(AttributeSelector.UnknownField::reasonCode).contains("CLASS_CONFLICT");
    }

    /** 与迁移 V202610069002 的结果一致：demo-v1 只改 weights.TDOA.class。 */
    private static FusionParams afterMigration() {
        assertThat(DemoFusionParams.DEMO_V1_JSON).contains(TDOA_CLASS_ZERO);
        return FusionParamsImpl.fromJson("demo-v1", DemoFusionParams.DEMO_V1_JSON.replace(TDOA_CLASS_ZERO,
                "\"TDOA\":{\"position\":0.25,\"motion\":0.2,\"class\":0.2,\"identity\":0.5}"));
    }
}
