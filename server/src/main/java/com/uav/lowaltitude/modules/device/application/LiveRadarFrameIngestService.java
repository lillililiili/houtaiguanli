package com.uav.lowaltitude.modules.device.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;

/**
 * 雷达 TCP 航迹帧：ops inbox + 航迹落库 + 阶段 2 信封在同一事务。
 * 点迹/RTK 不走这里。提升是否真正写入 {@code live-radar:} 由 {@code SourceObservationPort} 的开关实现决定。
 */
@Service
public class LiveRadarFrameIngestService {

    private final ProtocolDataRepository protocolData;
    private final SourceObservationPort port;

    public LiveRadarFrameIngestService(ProtocolDataRepository protocolData, SourceObservationPort port) {
        this.protocolData = protocolData;
        this.port = port;
    }

    static String trackMessageKey(String opsDeviceId, TrackBatch batch) {
        return opsDeviceId + ":" + Long.toUnsignedString(batch.radarBootMicros()) + ":"
                + Integer.toUnsignedString(RadarV300Codec.COMMAND_UPLOAD_TRACK_V3) + ":" + batch.payloadFrameId();
    }

    /**
     * @return false 表示 ops {@code live-device:} 已有同一键，整帧（含提升）跳过
     */
    @Transactional
    public boolean ingestTrack(String opsSourceId, String opsDeviceId, String deviceNo, String sourceCode,
                               TrackBatch batch, byte[] raw, long receivedAt) {
        String key = trackMessageKey(opsDeviceId, batch);
        if (!protocolData.insertInbox(opsSourceId, opsDeviceId, key, raw, receivedAt)) return false;
        protocolData.saveTrackBatch(opsDeviceId, deviceNo, batch, receivedAt);
        String envelopeDeviceId = sourceCode == null || sourceCode.isBlank() ? deviceNo : sourceCode.trim();
        port.accept(List.of(LiveRadarPromotionAssembler.frame(envelopeDeviceId, batch,
                protocolData.derivedLonLat(opsDeviceId, batch.radarBootMicros()))));
        return true;
    }
}
