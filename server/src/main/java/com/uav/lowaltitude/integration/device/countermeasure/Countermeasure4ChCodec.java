package com.uav.lowaltitude.integration.device.countermeasure;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.uav.lowaltitude.integration.device.ProtocolException;

/**
 * 固定式四通道网络控制器 v2.0。
 * 查询 0x10 无副作用；设置 0x11/0x12/0x13 有继电器动作。不实现 0x14 及之后、无回码连发。
 */
public final class Countermeasure4ChCodec {

    public static final int REQUEST_HEADER = 0x55;
    public static final int RESPONSE_HEADER = 0x22;
    public static final int FUNCTION_QUERY = 0x10;
    public static final int FUNCTION_OFF = 0x11;
    public static final int FUNCTION_ON = 0x12;
    public static final int FUNCTION_SET = 0x13;
    public static final int BROADCAST_ADDRESS = 245;

    public static final int MASK_ALL_OFF = 0x00;
    public static final int MASK_DRIVE_AWAY = 0x0D;
    public static final int MASK_FORCE_LAND = 0x0F;
    public static final int CHANNEL_900M = 0x01;
    public static final int CHANNEL_15G = 0x02;
    public static final int CHANNEL_24G = 0x04;
    public static final int CHANNEL_58G = 0x08;

    public static final String BAND_900M = "900M";
    public static final String BAND_15G = "1.5G";
    public static final String BAND_24G = "2.4G";
    public static final String BAND_58G = "5.8G";

    private static final Set<Integer> DOCUMENTED_MASKS =
            Set.of(MASK_ALL_OFF, CHANNEL_900M, CHANNEL_15G, CHANNEL_24G, CHANNEL_58G, MASK_DRIVE_AWAY, MASK_FORCE_LAND);
    private static final Set<Integer> REST_SET_MASKS = Set.of(MASK_ALL_OFF, MASK_DRIVE_AWAY, MASK_FORCE_LAND);

    private Countermeasure4ChCodec() {
    }

    public static byte[] query(int address) {
        // 资料查询例为 55 01 10 00 00 00 01 67；查哪一路都返回全板状态，数据末字节用 1。
        return frame(address, FUNCTION_QUERY, 1);
    }

    public static byte[] channelOn(int address, int channelBit) {
        validateChannelBit(channelBit);
        return frame(address, FUNCTION_ON, channelBit);
    }

    public static byte[] channelOff(int address, int channelBit) {
        validateChannelBit(channelBit);
        return frame(address, FUNCTION_OFF, channelBit);
    }

    public static byte[] setMask(int address, int mask) {
        validateDocumentedMask(mask);
        return frame(address, FUNCTION_SET, mask);
    }

    public static int channelBit(String channel) {
        if (channel == null) throw invalid("必须指定通道");
        return switch (channel) {
            case BAND_900M -> CHANNEL_900M;
            case BAND_15G -> CHANNEL_15G;
            case BAND_24G -> CHANNEL_24G;
            case BAND_58G -> CHANNEL_58G;
            default -> throw invalid("通道必须是 900M、1.5G、2.4G 或 5.8G");
        };
    }

    public static boolean restSetMask(int mask) {
        return REST_SET_MASKS.contains(mask);
    }

    public static int documentedForceLandMaskForTestOnly() { return MASK_FORCE_LAND; }
    public static int documentedDriveAwayMaskForTestOnly() { return MASK_DRIVE_AWAY; }

    public static byte[] encodeWire(byte[] frame, WireEncoding encoding) {
        return switch (encoding) {
            case RAW_BYTES -> frame.clone();
            case ASCII_HEX_SPACED -> (HexFormat.ofDelimiter(" ").withUpperCase().formatHex(frame) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            case ASCII_HEX_COMPACT -> (HexFormat.of().withUpperCase().formatHex(frame) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            case AUTO -> throw new IllegalArgumentException("AUTO 必须先完成会话探测");
        };
    }

    public static byte[] decodeWire(byte[] bytes, WireEncoding encoding) {
        if (bytes == null) throw invalid("反制响应为空");
        if (encoding == WireEncoding.RAW_BYTES) return bytes.clone();
        String value = new String(bytes, StandardCharsets.US_ASCII).trim().replace(" ", "");
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException ex) {
            throw invalid("反制响应不是有效 ASCII Hex");
        }
    }

    public static RelayState parseResponse(byte[] logicalFrame, int expectedAddress) {
        return parseResponse(logicalFrame, expectedAddress, FUNCTION_QUERY);
    }

    public static RelayState parseResponse(byte[] logicalFrame, int expectedAddress, int expectedFunction) {
        validateAddress(expectedAddress);
        if (expectedFunction < FUNCTION_QUERY || expectedFunction > FUNCTION_SET)
            throw invalid("反制功能码必须为 0x10–0x13");
        if (logicalFrame == null || logicalFrame.length != 8) throw invalid("反制响应逻辑帧必须为 8 字节");
        if (Byte.toUnsignedInt(logicalFrame[0]) != RESPONSE_HEADER) throw invalid("反制响应帧头不是 0x22");
        if (Byte.toUnsignedInt(logicalFrame[1]) != expectedAddress) throw invalid("反制响应设备地址不匹配");
        int function = Byte.toUnsignedInt(logicalFrame[2]);
        if (function != expectedFunction) throw invalid("反制响应功能码与请求不一致");
        if (logicalFrame[7] != checksum(logicalFrame, 0, 7)) throw invalid("反制响应累加和错误");
        long word = Integer.toUnsignedLong((Byte.toUnsignedInt(logicalFrame[3]) << 24)
                | (Byte.toUnsignedInt(logicalFrame[4]) << 16)
                | (Byte.toUnsignedInt(logicalFrame[5]) << 8)
                | Byte.toUnsignedInt(logicalFrame[6]));
        int low = Byte.toUnsignedInt(logicalFrame[6]);
        Map<String, Boolean> channels = new LinkedHashMap<>();
        channels.put(BAND_900M, (low & CHANNEL_900M) != 0);
        channels.put(BAND_15G, (low & CHANNEL_15G) != 0);
        channels.put(BAND_24G, (low & CHANNEL_24G) != 0);
        channels.put(BAND_58G, (low & CHANNEL_58G) != 0);
        return new RelayState(word, channels);
    }

    public static byte checksum(byte[] bytes, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i++) sum += Byte.toUnsignedInt(bytes[i]);
        return (byte) sum;
    }

    private static byte[] frame(int address, int function, int dataLowByte) {
        validateAddress(address);
        byte[] frame = new byte[] {
                (byte) REQUEST_HEADER, (byte) address, (byte) function, 0, 0, 0, (byte) dataLowByte, 0 };
        frame[7] = checksum(frame, 0, 7);
        return frame;
    }

    private static void validateAddress(int address) {
        if (address < 1 || address >= BROADCAST_ADDRESS)
            throw new ProtocolException("PROTOCOL_FRAME_INVALID", "反制设备地址必须为 1–244，禁止广播地址 245");
    }

    private static void validateChannelBit(int channelBit) {
        if ((channelBit & ~0x0F) != 0 || Integer.bitCount(channelBit) != 1)
            throw invalid("单通道位必须是 0x01/0x02/0x04/0x08");
    }

    private static void validateDocumentedMask(int mask) {
        if (!DOCUMENTED_MASKS.contains(mask))
            throw invalid("组合掩码只允许资料用到的 0x00/0x01/0x02/0x04/0x08/0x0D/0x0F");
    }

    private static ProtocolException invalid(String message) {
        return new ProtocolException("PROTOCOL_FRAME_INVALID", message);
    }

    public enum WireEncoding { AUTO, RAW_BYTES, ASCII_HEX_SPACED, ASCII_HEX_COMPACT }
    public record RelayState(long rawStatusWord, Map<String, Boolean> channels) {
        public RelayState { channels = Map.copyOf(channels); }
    }
}
