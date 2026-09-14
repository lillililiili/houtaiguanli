package com.uav.lowaltitude.integration.device.radar;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.uav.lowaltitude.integration.device.ProtocolException;

public final class RadarV300PayloadDecoder {

    public static final int TRACK_HEADER_BYTES = 40;
    public static final int TRACK_ITEM_BYTES = 64;
    public static final int POINT_HEADER_BYTES = 32;
    public static final int POINT_ITEM_BYTES = 24;
    public static final int RTK_BYTES = 32;

    private RadarV300PayloadDecoder() { }

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
