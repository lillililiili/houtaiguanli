package com.uav.lowaltitude.integration.device.radar;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.uav.lowaltitude.integration.device.ProtocolException;

/** T02/机扫雷达 v3.0.0 大端 TCP 帧。 */
public final class RadarV300Codec {

    public static final int HEADER = 0x55AA55AA;
    public static final int COMMAND_HEARTBEAT = 0x00000001;
    public static final int COMMAND_LOGIN = 0x00000002;
    public static final int COMMAND_GET_REGISTER = 0x00000041;
    public static final int COMMAND_UPLOAD_TARGET_V3 = 0x00030001;
    public static final int COMMAND_UPLOAD_TRACK_V3 = 0x00030002;
    public static final int COMMAND_REQUEST_RTK = 0x00010005;
    public static final int COMMAND_UPLOAD_RTK = 0x00010006;
    public static final int WORK_MODE_REGISTER = 0x00000401;
    public static final int MIN_FRAME_BODY_LENGTH = 10;
    public static final int DEFAULT_MAX_FRAME_LENGTH = 1024 * 1024;

    private RadarV300Codec() {
    }

    public static byte[] encode(int command, long frameId, byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        int bodyLength = 4 + 4 + safePayload.length + 2;
        ByteBuffer result = ByteBuffer.allocate(8 + bodyLength).order(ByteOrder.BIG_ENDIAN);
        result.putInt(HEADER).putInt(bodyLength).putInt(command).putInt((int) frameId).put(safePayload);
        byte[] bytes = result.array();
        int crc = crc16Modbus(bytes, 8, bodyLength - 2);
        result.putShort((short) crc);
        return result.array();
    }

    public static RadarFrame decode(byte[] bytes, boolean allowDebugCrc) {
        if (bytes == null || bytes.length < 18) throw invalid("雷达帧长度不足");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != HEADER) throw invalid("雷达帧头无效");
        int bodyLength = buffer.getInt();
        if (bodyLength < MIN_FRAME_BODY_LENGTH || bodyLength > DEFAULT_MAX_FRAME_LENGTH
                || bytes.length != bodyLength + 8) throw invalid("雷达帧长度字段无效");
        int command = buffer.getInt();
        long frameId = Integer.toUnsignedLong(buffer.getInt());
        int payloadLength = bodyLength - 10;
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        int receivedCrc = Short.toUnsignedInt(buffer.getShort());
        int expectedCrc = crc16Modbus(bytes, 8, bodyLength - 2);
        if (receivedCrc != expectedCrc && !(allowDebugCrc && receivedCrc == 0xCCCC))
            throw invalid("雷达帧 CRC16-MODBUS 校验失败");
        return new RadarFrame(command, frameId, payload, receivedCrc == 0xCCCC);
    }

    public static int crc16Modbus(byte[] bytes, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= bytes[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xFFFF;
    }

    public static byte[] heartbeatPayload() {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(0L).array();
    }

    /** 资料：权限类型 UINT32（数据权限 0x5）+ 权限识别码 UINT32。 */
    public static byte[] loginDataPayload(long recognitionCode) {
        if ((recognitionCode >>> 32) != 0) throw invalid("雷达识别码必须为 32 位无符号整数");
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(5)
                .putInt((int) recognitionCode)
                .array();
    }

    /** 登录回包附加数据：权限类型 4 字节 + 登录状态码 UINT16。返回状态码，0 为成功。 */
    public static int loginStatus(byte[] payload) {
        if (payload == null || payload.length < 6) throw invalid("雷达登录响应附加数据必须为权限类型 4 字节加状态码 2 字节");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buffer.getInt();
        return Short.toUnsignedInt(buffer.getShort());
    }

    public static byte[] getWorkModePayload() {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(WORK_MODE_REGISTER).array();
    }

    public static byte[] enableRtkUploadPayload() { return new byte[] { 1, 0 }; }

    private static ProtocolException invalid(String message) {
        return new ProtocolException("PROTOCOL_FRAME_INVALID", message);
    }

    public record RadarFrame(int command, long frameId, byte[] payload, boolean debugCrc) {
        public RadarFrame {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    /**
     * 有界增量解码器。错误帧只丢弃当前帧头，随后从剩余字节继续寻找 0x55AA55AA。
     */
    public static final class StreamDecoder {
        private final int maxFrameLength;
        private final boolean allowDebugCrc;
        private byte[] pending = new byte[0];
        private long invalidFrameCount;

        public StreamDecoder(int maxFrameLength, boolean allowDebugCrc) {
            if (maxFrameLength < MIN_FRAME_BODY_LENGTH) throw new IllegalArgumentException("maxFrameLength too small");
            this.maxFrameLength = maxFrameLength;
            this.allowDebugCrc = allowDebugCrc;
        }

        public List<RadarFrame> feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return List.of();
            ByteArrayOutputStream combined = new ByteArrayOutputStream(pending.length + chunk.length);
            combined.writeBytes(pending);
            combined.writeBytes(chunk);
            byte[] bytes = combined.toByteArray();
            List<RadarFrame> frames = new ArrayList<>();
            int cursor = 0;
            while (bytes.length - cursor >= 8) {
                int headerAt = findHeader(bytes, cursor);
                if (headerAt < 0) {
                    cursor = Math.max(cursor, bytes.length - 3);
                    break;
                }
                cursor = headerAt;
                int bodyLength = ByteBuffer.wrap(bytes, cursor + 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (bodyLength < MIN_FRAME_BODY_LENGTH || bodyLength > maxFrameLength) {
                    invalidFrameCount++;
                    cursor++;
                    continue;
                }
                int total = bodyLength + 8;
                if (bytes.length - cursor < total) break;
                byte[] candidate = Arrays.copyOfRange(bytes, cursor, cursor + total);
                try {
                    frames.add(decode(candidate, allowDebugCrc));
                    cursor += total;
                } catch (ProtocolException ex) {
                    invalidFrameCount++;
                    cursor++;
                }
            }
            pending = Arrays.copyOfRange(bytes, cursor, bytes.length);
            if (pending.length > maxFrameLength + 8) {
                invalidFrameCount++;
                pending = Arrays.copyOfRange(pending, pending.length - 3, pending.length);
            }
            return frames;
        }

        public long invalidFrameCount() { return invalidFrameCount; }
        public int pendingBytes() { return pending.length; }

        private static int findHeader(byte[] bytes, int start) {
            for (int i = start; i <= bytes.length - 4; i++) {
                if ((bytes[i] & 0xFF) == 0x55 && (bytes[i + 1] & 0xFF) == 0xAA
                        && (bytes[i + 2] & 0xFF) == 0x55 && (bytes[i + 3] & 0xFF) == 0xAA) return i;
            }
            return -1;
        }
    }
}
