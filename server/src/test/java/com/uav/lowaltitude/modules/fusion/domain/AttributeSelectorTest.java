package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.Selection;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuserTest.MapParams;

/** 属性优选：位置主源按 权重/σ²，类别只信 EO（其余按类别权重且无置信度），身份只取 TDOA/5G-A，运动与位置同源。 */
class AttributeSelectorTest {
    private static final double LON = WeightedFuserTest.LON, LAT = WeightedFuserTest.LAT;
    private final AttributeSelector selector = new AttributeSelector();

    @Test
    void identityComesFromTdoaEvenWhenEoAlsoReportsAClue() {
        SourceEstimate eo = WeightedFuserTest.estimate("eo", "EO", LON, LAT, 25.0, null, null, null, null, "UAV", 0.7, "EO-ID");
        SourceEstimate tdoa = WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, null, null, "RF-77");
        SourceEstimate radar = WeightedFuserTest.estimate("radar", "RADAR", LON, LAT, 15.0, null, null, 12.0, 45.0, null, null, null);
        Selection selection = selector.select(List.of(eo, tdoa, radar), MapParams.demo(), null);
        assertThat(selection.identitySourceId()).isEqualTo("tdoa");
        assertThat(selection.identityClue()).isEqualTo("RF-77");
        assertThat(selection.classSourceId()).isEqualTo("eo");
        assertThat(selection.positionSourceId()).isEqualTo("radar");
        assertThat(selection.motionSourceId()).isEqualTo("radar");
        assertThat(selection.unknownFields()).isEmpty();
    }

    @Test
    void withoutEoClassIsNullAndRecordedUnknown() {
        SourceEstimate radar = WeightedFuserTest.estimate("radar", "RADAR", LON, LAT, 15.0, null, null, null, null, null, null, null);
        SourceEstimate tdoa = WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, "UAV", null, "RF-1");
        Selection selection = selector.select(List.of(radar, tdoa), MapParams.demo(), null);
        // 雷达本帧没报类别，TDOA 的类别权重为 0 不参与：类别为空并记 NOT_REPORTED，不能拿低权重源冒充。
        assertThat(selection.classCode()).isNull();
        assertThat(selection.classSourceId()).isNull();
        assertThat(selection.unknownFields()).extracting(u -> u.field()).contains("classification_confidence");
        // 位置主源没报速度/航向：运动量为空并记未知，不从别的源借。
        assertThat(selection.motionSourceId()).isEqualTo("radar");
        assertThat(selection.unknownFields()).extracting(u -> u.field()).contains("speed_mps", "heading_deg");
    }

    @Test
    void sourceSwitchIsDetectedAgainstPreviousPositionSource() {
        SourceEstimate tdoa = WeightedFuserTest.estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, null, null, null);
        assertThat(selector.select(List.of(tdoa), MapParams.demo(), "radar").sourceSwitched()).isTrue();
        assertThat(selector.select(List.of(tdoa), MapParams.demo(), null).sourceSwitched()).isFalse();
        assertThat(selector.select(List.of(tdoa), MapParams.demo(), "tdoa").sourceSwitched()).isFalse();
    }
}
