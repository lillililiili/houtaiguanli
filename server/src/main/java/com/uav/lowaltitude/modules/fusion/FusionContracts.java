package com.uav.lowaltitude.modules.fusion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 阶段 8 融合引擎的冻结接口（领导所有，执行者不得修改；需要改先向领导提出）。
 * E1（摄取/滤波/关联/ID 状态机）在管线第 ⑥ 步把每个目标的一帧结果交给 {@link FusedLayerWriter}，
 * E2（属性优选/加权融合/降级/落融合层）实现该接口并写 target_latest_state、FUSED 层 track/track_point、
 * target_attribute_selection、target_degradation、fusion_event。
 * 参数全部来自 fusion_config.params（schema_status=DEMO），代码里不得出现裸阈值。
 */
public final class FusionContracts {

    private FusionContracts() {
    }

    /** 目标轨迹状态机（契约：target_track_status.status）。 */
    public enum TrackStatus { TENTATIVE, STABLE, SHORT_LOST, SPLIT, MERGE, TERMINATED }

    /** 降级等级（契约：target_degradation.level）。 */
    public enum DegradationLevel { THREE_SOURCE, FUSION_BOX_ONLY, SINGLE_SOURCE, NONE }

    /** 轨迹点种类：实测 / 短间隙插值 / 预测（PRED 只出现在 FUSED 层）。 */
    public enum PointKind { MEAS, BRIDGE, PRED }

    /** 融合分区：跨 source_mode 或跨归属元组永不关联。 */
    public record FusionDomainKey(String sourceMode, String ownerOrgId, String districtId) {
        public String asKey() {
            return sourceMode + "|" + (ownerOrgId == null ? "" : ownerOrgId) + "|" + (districtId == null ? "" : districtId);
        }
    }

    /**
     * 单源经 α-β 滤波后的估计（一条 link 一帧一条）。
     * 位置/高度/速度等为 null 表示该源本帧没有该字段——E2 不得补默认值，只能在融合时跳过该属性并记 unknown。
     * accuracyM 为滤波后的位置精度（缺来源精度时 E1 已按 accuracy_default_m[source_type] 填入并在 quality 里标 accuracy_defaulted=true）。
     */
    public record SourceEstimate(
            String sourceId,
            String sourceCode,
            String sourceType,
            String schemaStatus,
            String linkId,
            String rawTrackId,
            String observationId,
            Instant observedAt,
            Double longitude,
            Double latitude,
            Double accuracyM,
            Double altitudeAmslM,
            Double heightAglM,
            Double speedMps,
            Double headingDeg,
            String classCode,
            Double classConfidence,
            String identityClue,
            Double identityConfidence,
            PointKind kind,
            Map<String, Object> quality,
            /* 阶段 8.5：飞手/遥控器位置（凌云协议 A 的 pilotLon/pilotLat，TDOA/AOA/DCD/RID 才有）与类别来源（EO_TRACKING / SENSE_DATA / MANUAL）。 */
            Double pilotLongitude,
            Double pilotLatitude,
            String classSource) {
        /** 阶段 8 的旧签名：无飞手位置与类别来源。 */
        public SourceEstimate(String sourceId, String sourceCode, String sourceType, String schemaStatus, String linkId, String rawTrackId,
                String observationId, Instant observedAt, Double longitude, Double latitude, Double accuracyM, Double altitudeAmslM,
                Double heightAglM, Double speedMps, Double headingDeg, String classCode, Double classConfidence, String identityClue,
                Double identityConfidence, PointKind kind, Map<String, Object> quality) {
            this(sourceId, sourceCode, sourceType, schemaStatus, linkId, rawTrackId, observationId, observedAt, longitude, latitude, accuracyM,
                    altitudeAmslM, heightAglM, speedMps, headingDeg, classCode, classConfidence, identityClue, identityConfidence, kind, quality,
                    null, null, null);
        }
    }

    /**
     * 一个目标在一帧的关联结果。estimates 为空表示本帧该目标无任何来源（SHORT_LOST/TERMINATED 时 E1 仍会调用，
     * 由 E2 决定是否写 PRED 点与累进降级）。missFrames 为连续无源帧数。
     */
    public record TargetFrameResult(
            String targetId,
            FusionDomainKey domain,
            Instant observedAt,
            List<SourceEstimate> estimates,
            TrackStatus status,
            int missFrames,
            String configVersion) {
    }

    /** E2 实现；E1 通过 ObjectProvider 获取，缺席时用无操作实现（只在 E2 落地前的 E1 单测里出现）。 */
    public interface FusedLayerWriter {
        void write(TargetFrameResult frame);
    }

    /**
     * 融合参数视图（E1 的 FusionConfigLoader 从 fusion_config.params 解析并实现；E2 只读）。
     * 缺参数抛 IllegalStateException——参数缺失是部署错误，不能用代码默认值掩盖。
     */
    public interface FusionParams {
        String configVersion();
        double number(String group, String key);
        int integer(String group, String key);
        /** filter.accuracy_default_m：source_type → 米。 */
        Map<String, Double> accuracyDefaults();
        /** weights：source_type → {position, motion, class, identity}。 */
        Map<String, Map<String, Double>> weights();
    }

    /**
     * 实测雷达 ops → 阶段 2 提升的预留入口（决策 8-1：本阶段不实现，只留接口与 app.fusion.live-promotion.enabled=false）。
     */
    public interface SourceObservationPort {
        void accept(List<Map<String, Object>> observations);
    }
}
