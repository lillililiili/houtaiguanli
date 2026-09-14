package com.uav.lowaltitude.modules.fusion.domain;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;

/** 纯 Java 测试用固定参数：与迁移 050 的 demo-v1 JSON 同源，经 FusionParamsImpl 解析，缺键同样抛 IllegalStateException。 */
final class DemoFusionParams {
    static final String DEMO_V1_JSON = "{\"filter\":{\"alpha\":0.6,\"beta\":0.25,\"max_dt_ms\":3000,\"pred_max_frames\":3,\"bridge_max_gap_ms\":6000,"
            + "\"accuracy_default_m\":{\"RADAR\":15,\"TDOA\":60,\"EO\":25,\"FIVE_G_A\":80,\"FUSION_BOX\":20}},"
            + "\"association\":{\"gate_sigma\":3.0,\"w_pos\":1.0,\"w_alt\":0.5,\"w_time\":0.3,\"w_motion\":0.6,\"w_class\":0.4,\"w_hist\":0.3,"
            + "\"alt_scale_m\":30,\"time_scale_ms\":1500,\"speed_scale_mps\":6,\"heading_scale_deg\":45,\"cost_max\":6.0,\"pending_confirm_frames\":3,\"pending_expire_frames\":6},"
            + "\"identity\":{\"tentative_to_stable_hits\":3,\"short_lost_after_ms\":3000,\"terminate_after_ms\":15000,\"merge_min_frames\":4,\"merge_max_dist_sigma\":2.0,"
            + "\"split_min_frames\":4,\"split_min_separation_m\":100},"
            + "\"weights\":{\"RADAR\":{\"position\":0.45,\"motion\":0.5,\"class\":0.2,\"identity\":0.0},\"EO\":{\"position\":0.2,\"motion\":0.1,\"class\":0.6,\"identity\":0.1},"
            + "\"TDOA\":{\"position\":0.25,\"motion\":0.2,\"class\":0.0,\"identity\":0.5},\"FIVE_G_A\":{\"position\":0.1,\"motion\":0.2,\"class\":0.2,\"identity\":0.4},"
            + "\"FUSION_BOX\":{\"position\":0.4,\"motion\":0.4,\"class\":0.3,\"identity\":0.0}},"
            + "\"quality\":{\"latency_penalty_ms\":2000,\"loss_window_frames\":10,\"loss_penalty_per_miss\":0.08,\"anomaly_zscore\":4.0,\"anomaly_downweight\":0.25},"
            + "\"degradation\":{\"three_source_min\":3,\"undetermined_deficit\":0.5,\"single_source_deficit\":0.35,\"fusion_box_only_deficit\":0.2,\"lost_step_deficit\":0.1}}";

    private DemoFusionParams() { }

    static FusionParams demoV1() { return FusionParamsImpl.fromJson("demo-v1", DEMO_V1_JSON); }
}
