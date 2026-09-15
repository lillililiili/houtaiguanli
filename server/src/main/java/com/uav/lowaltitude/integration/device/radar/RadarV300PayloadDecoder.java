package com.uav.lowaltitude.integration.device.radar;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;

import com.uav.lowaltitude.integration.device.ProtocolException;

public final class RadarV300PayloadDecoder {

    public static final int TRACK_HEADER_BYTES = 40;
    public static final int TRACK_ITEM_BYTES = 64;
    public static final int POINT_HEADER_BYTES = 32;
    public static final int POINT_ITEM_BYTES = 24;
    public static final int RTK_BYTES = 32;

    private RadarV300PayloadDecoder() { }

    public static Map<String, Object> registers(byte[] payload) {
        ByteBuffer value = wrap(payload, 4);
        int count = value.getInt();
        requireCount(payload.length, 4, 8, count);
        Map<String, Object> result = new LinkedHashMap<>();
        var addresses = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            int address = value.getInt(), raw = value.getInt();
            if (!addresses.add(address)) throw invalid("寄存器地址重复");
            if (address == 0x440) {
                result.put("user_cfg0_raw", String.format("0x%08X", raw));
                result.put("frequency_code", (raw >>> 24) & 255);
                int speed = (raw >>> 16) & 255, detection = (raw >>> 8) & 255, rcs = raw & 255;
                result.put("speed_threshold_code", speed);
                result.put("detection_threshold_code", detection);
                result.put("rcs_threshold_code", rcs);
                if (speed <= 3) result.put("speed_threshold_mps", new BigDecimal("0.25").multiply(BigDecimal.valueOf(speed + 1)));
                if (detection <= 2) result.put("detection_threshold", List.of("低门限", "正常门限", "高门限").get(detection));
                if (rcs == 0) result.put("rcs_filter_enabled", false);
                if (rcs == 1 || rcs == 2) {
                    result.put("rcs_filter_enabled", true);
                    result.put("rcs_threshold_m2", new BigDecimal(rcs == 1 ? "0.01" : "0.05"));
                }
            } else if (address == 0x401) {
                result.put("working_mode_raw", String.format("0x%08X", raw));
                int speed = (raw >>> 8) & 255, mode = raw & 255;
                result.put("rotation_code", speed); result.put("work_mode_code", mode);
                // The RPM column contradicts angular speed in the supplied document: retain code and °/s only.
                Map<Integer, Integer> angularSpeeds = Map.of(0, 0, 1, 180, 2, 90, 4, 360);
                if (angularSpeeds.containsKey(speed)) result.put("scan_speed_deg_s", angularSpeeds.get(speed));
                if (mode <= 2) result.put("work_mode", List.of("待机", "周扫", "扇扫（协议注明暂不支持）").get(mode));
            }
        }
        return result;
    }

    public static TrackBatch track(byte[] payload) {
        ByteBuffer value = wrap(payload, TRACK_HEADER_BYTES);
        long boot = value.getLong();
        String payloadFrameId = Long.toUnsignedString(value.getLong());
        long uploadedAt = value.getLong();
        BigDecimal scanStart = scaled(value.getInt(), 4);
        BigDecimal scanEnd = scaled(value.getInt(), 4);
        value.get();
        value.get();
        int northFlag = Byte.toUnsignedInt(value.get());
        int scanDirection = Byte.toUnsignedInt(value.get());
        int count = value.getInt();
        requireCount(payload.length, TRACK_HEADER_BYTES, TRACK_ITEM_BYTES, count);
        List<TrackItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal x = scaled(value.getInt(), 2), y = scaled(value.getInt(), 2), z = scaled(value.getInt(), 2);
            BigDecimal vx = scaled(value.getInt(), 2), vy = scaled(value.getInt(), 2), vz = scaled(value.getInt(), 2);
            String id = Integer.toUnsignedString(value.getInt());
            BigDecimal snr = scaled(value.getShort(), 2);
            BigDecimal legacyRcs = scaled(value.getShort(), 2);
            value.position(value.position() + 6);
            int classification = Byte.toUnsignedInt(value.get());
            value.get();
            value.position(value.position() + 16);
            BigDecimal highResolutionRcs = scaled(value.getInt(), 6);
            boolean selected = value.getInt() == 1;
            if (classification > 5) classification = 5;
            items.add(new TrackItem(id, x, y, z, vx, vy, vz, snr, legacyRcs,
                    highResolutionRcs, classification, category(classification), selected));
        }
        return new TrackBatch(boot, payloadFrameId, uploadedAt, scanStart, scanEnd, northFlag,
                scanDirection, List.copyOf(items));
    }

    public static PointBatch points(byte[] payload) {
        ByteBuffer value = wrap(payload, POINT_HEADER_BYTES);
        long boot = value.getLong();
        String payloadFrameId = Long.toUnsignedString(value.getLong());
        BigDecimal scanStart = scaled(value.getInt(), 4), scanEnd = scaled(value.getInt(), 4);
        int direction = value.getInt();
        int count = value.getInt();
        requireCount(payload.length, POINT_HEADER_BYTES, POINT_ITEM_BYTES, count);
        List<PointItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal x = scaled(value.getInt(), 2), y = scaled(value.getInt(), 2), z = scaled(value.getInt(), 2);
            value.position(value.position() + 4);
            BigDecimal snr = BigDecimal.valueOf(Short.toUnsignedInt(value.getShort()), 2);
            value.position(value.position() + 6);
            items.add(new PointItem(x, y, z, snr));
        }
        return new PointBatch(boot, payloadFrameId, scanStart, scanEnd, direction, List.copyOf(items));
    }

    public static Rtk rtk(byte[] payload) {
        ByteBuffer value = wrap(payload, RTK_BYTES);
        return new Rtk(BigDecimal.valueOf(value.getLong(), 9), BigDecimal.valueOf(value.getLong(), 9),
                BigDecimal.valueOf(value.getLong(), 9), value.getInt(), value.getInt());
    }

    public static String category(int classification) {
        return switch (classification) {
            case 0 -> "PENDING_IDENTIFICATION";
            case 1 -> "PERSON";
            case 2 -> "VEHICLE";
            case 3 -> "UAV";
            case 4 -> "BIRD";
            default -> "UNIDENTIFIED";
        };
    }

    private static ByteBuffer wrap(byte[] payload, int minimum) {
        if (payload == null || payload.length < minimum) throw invalid("雷达业务载荷长度不足");
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    }

    private static void requireCount(int actualBytes, int headerBytes, int itemBytes, int count) {
        if (count < 0 || count > 100_000 || actualBytes != headerBytes + count * itemBytes)
            throw invalid("雷达目标数量与载荷长度不一致");
    }

    private static BigDecimal scaled(int value, int scale) { return BigDecimal.valueOf(value, scale); }
    private static ProtocolException invalid(String message) { return new ProtocolException("PROTOCOL_FRAME_INVALID", message); }

    public record TrackBatch(long radarBootMicros, String payloadFrameId, long uploadedAt,
                             BigDecimal scanStartDeg, BigDecimal scanEndDeg, int northFlag,
                             int scanDirection, List<TrackItem> items) { }
    public record TrackItem(String externalTrackId, BigDecimal xM, BigDecimal yM, BigDecimal zM,
                            BigDecimal velocityXMps, BigDecimal velocityYMps, BigDecimal velocityZMps,
                            BigDecimal snrDb, BigDecimal legacyRcsM2, BigDecimal highResolutionRcsM2,
                            int classification, String categoryCode, boolean selected) { }
    public record PointBatch(long radarBootMicros, String payloadFrameId, BigDecimal scanStartDeg,
                             BigDecimal scanEndDeg, int scanDirection, List<PointItem> items) { }
    public record PointItem(BigDecimal xM, BigDecimal yM, BigDecimal zM, BigDecimal snrDb) { }
    public record Rtk(BigDecimal latitudeDeg, BigDecimal longitudeDeg, BigDecimal headingDeg,
                      int satelliteCount, int ignoredAltitude) { }
}
