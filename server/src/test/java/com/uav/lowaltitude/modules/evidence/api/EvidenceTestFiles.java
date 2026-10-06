package com.uav.lowaltitude.modules.evidence.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 证据入库测试用的最小合法文件：内容与扩展名一致，能通过入库格式白名单与内容识别。
 * 同一扩展名返回同一份内容，便于断言哈希与大小。
 */
public final class EvidenceTestFiles {
    private static final byte[] JPEG = image("jpg");
    private static final byte[] PNG = image("png");
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    /** ftyp + moov + mdat 三个顶层 box，首尾相接。 */
    private static final byte[] MP4 = HexFormat.of().parseHex(
            "00000018" + "66747970" + "69736f6d" + "00000200" + "69736f6d" + "6d703431"
            + "00000008" + "6d6f6f76"
            + "0000000c" + "6d646174" + "00000000");
    /** EBML 头（DocType=webm）后紧跟 Segment。 */
    private static final byte[] WEBM = HexFormat.of().parseHex("1a45dfa3" + "87" + "4282" + "84" + "7765626d" + "18538067" + "80");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    private EvidenceTestFiles() { }

    public static byte[] bytes(String filename) {
        return switch (extension(filename)) {
            case "jpg", "jpeg" -> JPEG.clone();
            case "png" -> PNG.clone();
            case "webp" -> WEBP.clone();
            case "mp4" -> MP4.clone();
            case "webm" -> WEBM.clone();
            case "pdf" -> PDF.clone();
            case "json" -> "{\"sample\":true}".getBytes(StandardCharsets.UTF_8);
            case "csv" -> "时间,事件\n1,测试\n".getBytes(StandardCharsets.UTF_8);
            case "txt", "log" -> "证据测试文本\n".getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("没有该扩展名的测试样本：" + filename);
        };
    }

    public static String type(String filename) {
        return switch (extension(filename)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "pdf" -> "application/pdf";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "txt", "log" -> "text/plain";
            default -> throw new IllegalArgumentException("没有该扩展名的测试样本：" + filename);
        };
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] image(String format) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x3366cc);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) throw new IllegalStateException("ImageIO 不支持 " + format);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
