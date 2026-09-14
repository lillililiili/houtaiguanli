package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackItem;

/** 把雷达 TCP 航迹批编成契约 §2.3 的实测雷达信封，不发明坐标、不写业务表。 */
final class LiveRadarPromotionAssembler {

    private LiveRadarPromotionAssembler() { }

    static Map<String, Object> frame(String sourceCode, TrackBatch batch, Map<String, BigDecimal[]> derivedLonLat) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("device_id", sourceCode);
        frame.put("boot_micros", batch.radarBootMicros());
        frame.put("frame_id", batch.payloadFrameId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TrackItem item : batch.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("external_track_id", item.externalTrackId());
            BigDecimal[] lonLat = derivedLonLat == null ? null : derivedLonLat.get(item.externalTrackId());
            if (lonLat != null && lonLat.length == 2 && lonLat[0] != null && lonLat[1] != null) {
                row.put("longitude", lonLat[0]);
                row.put("latitude", lonLat[1]);
            }
            row.put("z_m", item.zM());
            row.put("velocity_x_mps", item.velocityXMps());
            row.put("velocity_y_mps", item.velocityYMps());
            row.put("velocity_z_mps", item.velocityZMps());
            row.put("snr_db", item.snrDb());
            BigDecimal rcs = item.highResolutionRcsM2() != null ? item.highResolutionRcsM2() : item.legacyRcsM2();
            if (rcs != null) row.put("rcs_m2", rcs);
            if (item.categoryCode() != null) row.put("classification", item.categoryCode());
            items.add(row);
        }
        frame.put("items", items);
        return frame;
    }
}
