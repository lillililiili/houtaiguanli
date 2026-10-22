package com.uav.lowaltitude.modules.mapresource.application;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/** Reads bounded image headers only. Never allocates or decodes the untrusted raster pixel buffer. */
final class MapTileImageHeader {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private MapTileImageHeader() { }

    static void validate(Path file, int tileSize) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            if (file.getFileName().toString().endsWith(".png")) validatePng(input, tileSize);
            else validateWebp(input, tileSize);
        }
    }

    private static void validatePng(RandomAccessFile input, int tileSize) throws IOException {
        if (input.length() < 45) invalid("PNG 文件头不完整");
        byte[] header = new byte[33];
        input.readFully(header);
        ByteBuffer bigEndian = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (!Arrays.equals(Arrays.copyOf(header, 8), PNG_SIGNATURE)
                || bigEndian.getInt(8) != 13 || !ascii(header, 12, 4).equals("IHDR")) {
            invalid("PNG 文件头无效");
        }
        CRC32 crc = new CRC32();
        crc.update(header, 12, 17);
        if (crc.getValue() != Integer.toUnsignedLong(bigEndian.getInt(29))) invalid("PNG IHDR 校验失败");
        int depth = Byte.toUnsignedInt(header[24]);
        int color = Byte.toUnsignedInt(header[25]);
        boolean validDepth = switch (color) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> false;
        };
        if (!validDepth || header[26] != 0 || header[27] != 0 || (header[28] != 0 && header[28] != 1)) {
            invalid("PNG IHDR 格式无效");
        }
        dimensions(bigEndian.getInt(16), bigEndian.getInt(20), tileSize);
    }

    private static void validateWebp(RandomAccessFile input, int tileSize) throws IOException {
        if (input.length() < 26) invalid("WebP 文件头不完整");
        byte[] riff = new byte[12];
        input.readFully(riff);
        if (!ascii(riff, 0, 4).equals("RIFF") || !ascii(riff, 8, 4).equals("WEBP")
                || uint32(riff, 4) + 8 != input.length()) invalid("WebP RIFF 文件头或长度无效");
        boolean extended = false;
        boolean frame = false;
        int chunks = 0;
        while (input.getFilePointer() < input.length()) {
            // Static WebP needs very few chunks; bound even a malicious sequence of empty RIFF chunks.
            if (++chunks > 1024 || input.length() - input.getFilePointer() < 8) invalid("WebP 数据块无效");
            byte[] chunk = new byte[8];
            input.readFully(chunk);
            String type = ascii(chunk, 0, 4);
            long length = uint32(chunk, 4);
            long start = input.getFilePointer();
            long end = start + length + (length & 1);
            if (end > input.length()) invalid("WebP 数据块长度无效");
            if (chunks == 1 && !type.equals("VP8X") && !type.equals("VP8 ") && !type.equals("VP8L")) {
                invalid("WebP 缺少图像文件头");
            }
            if (type.equals("VP8X")) {
                if (chunks != 1 || length != 10) invalid("WebP VP8X 文件头无效");
                byte[] header = new byte[10];
                input.readFully(header);
                if ((header[0] & 0xc3) != 0 || header[1] != 0 || header[2] != 0 || header[3] != 0) {
                    invalid("WebP VP8X 不支持动画或保留标志");
                }
                dimensions(uint24(header, 4) + 1, uint24(header, 7) + 1, tileSize);
                extended = true;
            } else if (type.equals("VP8 ") || type.equals("VP8L")) {
                if (frame || (!extended && chunks != 1)) invalid("WebP 包含重复图像数据");
                frame = true;
                if (type.equals("VP8 ")) {
                    if (length < 10) invalid("WebP VP8 文件头不完整");
                    byte[] header = new byte[10];
                    input.readFully(header);
                    if ((header[0] & 1) != 0 || (header[3] & 255) != 0x9d
                            || header[4] != 1 || header[5] != 0x2a) invalid("WebP VP8 文件头无效");
                    dimensions(((header[6] & 255) | (header[7] & 255) << 8) & 0x3fff,
                            ((header[8] & 255) | (header[9] & 255) << 8) & 0x3fff, tileSize);
                } else {
                    if (length < 5) invalid("WebP VP8L 文件头不完整");
                    byte[] header = new byte[5];
                    input.readFully(header);
                    if (header[0] != 0x2f || (header[4] & 0xe0) != 0) invalid("WebP VP8L 文件头无效");
                    long bits = uint32(header, 1);
                    dimensions((int) (bits & 0x3fff) + 1, (int) ((bits >>> 14) & 0x3fff) + 1, tileSize);
                }
            } else if (type.equals("ANIM") || type.equals("ANMF")) {
                invalid("WebP 影像瓦片不支持动画");
            }
            input.seek(end);
        }
        if (!frame) invalid("WebP 缺少图像数据");
    }

    private static long uint32(byte[] value, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt(offset));
    }

    private static int uint24(byte[] value, int offset) {
        return (value[offset] & 255) | (value[offset + 1] & 255) << 8 | (value[offset + 2] & 255) << 16;
    }

    private static String ascii(byte[] value, int offset, int length) {
        return new String(value, offset, length, StandardCharsets.US_ASCII);
    }

    private static void dimensions(int width, int height, int expected) {
        if (width != expected || height != expected) invalid("影像瓦片尺寸必须与 tileSize 一致：" + expected + "×" + expected);
    }

    private static void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "MAP_PACKAGE_INVALID", message);
    }
}
