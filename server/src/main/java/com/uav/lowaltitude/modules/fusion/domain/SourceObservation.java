package com.uav.lowaltitude.modules.fusion.domain;

import java.time.Instant;
import java.util.Map;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;

/**
 * 一条来源观测（source_observation 的领域形态）。字段缺失即 null，不补默认值；缺失原因由管线写入 quality。
 * 位置只有经纬度（WGS-84）：米制换算在 {@link AlphaBetaFilter} 里用 Java 完成，域层不依赖任何 SQL 几何函数，
 * 这样 H2 单测与 PostGIS 生产库得到同一套数值。
 */
public record SourceObservation(
        String observationId,
        String inboxId,
        String sourceId,
        String sourceCode,
        String sourceType,
        String schemaStatus,
        String deviceId,
        String sourceSessionKey,
        String externalTargetId,
        String externalTrackId,
        Instant observedAt,
        Instant receivedAt,
        Double longitude,
        Double latitude,
        Double positionAccuracyM,
        Double altitudeAmslM,
        Double heightAglM,
        Double speedMps,
        Double headingDeg,
        String classCode,
        Double classConfidence,
        String identityClue,
        Double identityConfidence,
        Long latencyMs,
        Map<String, Object> quality,
        String sourceMode,
        String ownerOrgId,
        String districtId,
        long pointSeq,
        /* 阶段 8.5：飞手/遥控器位置（凌云协议 A 的 pilotLon/pilotLat）与类别来源。飞手位置是**另一个点**，
           不是目标位置——C02-6 超视距要拿它和目标位置算距离，混进 location 就再也分不开了。 */
        Double pilotLongitude,
        Double pilotLatitude,
        String classSource) {

    /** 阶段 8 的旧签名：没有飞手位置与类别来源的来源保持原样构造。 */
    public SourceObservation(String observationId, String inboxId, String sourceId, String sourceCode, String sourceType, String schemaStatus,
            String deviceId, String sourceSessionKey, String externalTargetId, String externalTrackId, Instant observedAt, Instant receivedAt,
            Double longitude, Double latitude, Double positionAccuracyM, Double altitudeAmslM, Double heightAglM, Double speedMps,
            Double headingDeg, String classCode, Double classConfidence, String identityClue, Double identityConfidence, Long latencyMs,
            Map<String, Object> quality, String sourceMode, String ownerOrgId, String districtId, long pointSeq) {
        this(observationId, inboxId, sourceId, sourceCode, sourceType, schemaStatus, deviceId, sourceSessionKey, externalTargetId,
                externalTrackId, observedAt, receivedAt, longitude, latitude, positionAccuracyM, altitudeAmslM, heightAglM, speedMps,
                headingDeg, classCode, classConfidence, identityClue, identityConfidence, latencyMs, quality, sourceMode, ownerOrgId,
                districtId, pointSeq, null, null, null);
    }

    /** 融合分区：跨 source_mode 或跨归属元组的观测永不关联（回放与实测、不同单位辖区的目标不是同一个物理对象的证据）。 */
    public FusionDomainKey domain() {
        return new FusionDomainKey(sourceMode, ownerOrgId, districtId);
    }

    public boolean hasPosition() {
        return longitude != null && latitude != null;
    }

    public boolean hasPilotPosition() {
        return pilotLongitude != null && pilotLatitude != null;
    }

    public long observedMillis() {
        return observedAt.toEpochMilli();
    }
}
