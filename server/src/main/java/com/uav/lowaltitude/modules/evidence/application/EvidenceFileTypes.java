package com.uav.lowaltitude.modules.evidence.application;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 证据文件格式白名单：每类证据只收取证需要的格式，扩展名、上传时声明的类型和文件内容三者必须一致。
 * 入库记录的 content_type 只取服务端按文件内容识别的结果，不再沿用客户端声明。
 */
final class EvidenceFileTypes {
    static final String JPEG = "image/jpeg", PNG = "image/png", WEBP = "image/webp", MP4 = "video/mp4",
            WEBM = "video/webm", PDF = "application/pdf", TEXT = "text/plain", CSV = "text/csv", JSON = "application/json",
            HTML = "text/html";

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("jpg", JPEG), Map.entry("jpeg", JPEG), Map.entry("png", PNG), Map.entry("webp", WEBP),
            Map.entry("mp4", MP4), Map.entry("webm", WEBM), Map.entry("pdf", PDF),
            Map.entry("txt", TEXT), Map.entry("log", TEXT), Map.entry("csv", CSV), Map.entry("json", JSON));
    private static final Map<String, String> LABELS = Map.of(JPEG, "JPG 图片", PNG, "PNG 图片", WEBP, "WEBP 图片",
            MP4, "MP4 视频", WEBM, "WEBM 视频", PDF, "PDF 文档", TEXT, "TXT 文本", CSV, "CSV 表格", JSON, "JSON 数据",
            HTML, "网页文件");
    private static final List<String> IMAGES = List.of(JPEG, PNG, WEBP);
    private static final List<String> DOCUMENTS = List.of(PDF, JPEG, PNG, WEBP, TEXT);
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "EO_STILL", IMAGES, "SCENE_PHOTO", IMAGES, "EO_VIDEO", List.of(MP4, WEBM),
            "TRACK_SNAPSHOT", List.of(JSON, CSV, PNG, JPEG, WEBP),
            "NOTICE_RECEIPT", DOCUMENTS, "COMMISSION_REPORT", DOCUMENTS, "PENALTY_DOCUMENT", DOCUMENTS,
            "COMMAND_LOG", List.of(TEXT, CSV, JSON));
    /** 与业务前台证据种类中文名一致，错误提示直接给值班人员看。 */
    private static final Map<String, String> KIND_LABELS = Map.of("EO_VIDEO", "光电录像", "EO_STILL", "光电抓拍图",
            "TRACK_SNAPSHOT", "雷达轨迹记录", "NOTICE_RECEIPT", "通报单回执", "COMMISSION_REPORT", "调测报告",
            "COMMAND_LOG", "指令报文与回执", "SCENE_PHOTO", "现场照片", "PENALTY_DOCUMENT", "处罚文书");
    /** 浏览器和常用工具的同义写法；通用二进制声明不算冲突，以文件内容为准。 */
    private static final Map<String, String> ALIASES = Map.of("image/jpg", JPEG, "image/pjpeg", JPEG,
            "image/x-png", PNG, "application/x-pdf", PDF, "application/mp4", MP4, "text/json", JSON,
            "text/x-log", TEXT, "text/comma-separated-values", CSV);
    private static final Set<String> GENERIC = Set.of("application/octet-stream", "binary/octet-stream", "application/unknown");
    /** 文本类证据彼此兼容：JSON 也是合法文本；Windows 常把 .csv 声明成 Excel 类型。 */
    private static final Set<String> TEXT_FAMILY = Set.of(TEXT, CSV, JSON);
    private static final Set<String> TEXT_DECLARATIONS = Set.of(TEXT, CSV, JSON, "application/vnd.ms-excel");
    /** 历史文件可能存着客户端声明的任意类型：下载只回传这些类型，其余一律按二进制附件给出。 */
    private static final Set<String> SERVABLE = Set.of(JPEG, PNG, WEBP, "image/gif", MP4, WEBM, PDF, TEXT, CSV, JSON);
    private static final ObjectReader STRICT_JSON = new ObjectMapper().readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private EvidenceFileTypes() { }

    /** 校验通过时返回按内容识别的标准类型；不通过抛 415，提示说清楚错在哪里。 */
    static String verify(String kindCode, String fileName, String declaredType, byte[] content) {
        List<String> allowed = ALLOWED.get(kindCode);
        if (allowed == null) throw new IllegalArgumentException(kindCode);
        String extension = extension(fileName);
        String expected = EXTENSIONS.get(extension);
        if (expected == null || !allowed.contains(expected)) {
            String which = extension.isEmpty() ? "这个文件没有扩展名"
                    : extension.matches("[a-z0-9]{1,10}") ? "不能上传 ." + extension + " 文件" : "不能上传这种文件";
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "EVIDENCE_TYPE_NOT_ALLOWED",
                    "“" + KIND_LABELS.get(kindCode) + "”只收 " + allowedText(allowed) + "，" + which + "。");
        }
        String detected = detect(content);
        boolean matches = expected.equals(detected) || (TEXT_FAMILY.contains(expected) && !JSON.equals(expected)
                && TEXT_FAMILY.contains(detected));
        if (!matches) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "EVIDENCE_TYPE_MISMATCH", detected == null
                    ? "文件内容不是有效的 " + LABELS.get(expected) + "，可能改过扩展名或文件已损坏，请确认后重新上传。"
                    : "文件内容与扩展名不符：扩展名是 ." + extension + "，实际是" + spaced(LABELS.get(detected)) + "，请确认后重新上传。");
        }
        String declared = normalize(declaredType);
        if (!declared.isEmpty() && !GENERIC.contains(declared) && !declared.equals(expected)
                && !(TEXT_FAMILY.contains(expected) && TEXT_DECLARATIONS.contains(declared))) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "EVIDENCE_TYPE_MISMATCH",
                    "上传时声明的文件类型与实际内容（" + LABELS.get(expected) + "）不符，请确认后重新上传。");
        }
        return expected;
    }

    /** 下载响应头使用的类型：白名单外的历史声明（如网页、程序）一律按二进制附件处理。 */
    static String downloadType(String stored) {
        String type = normalize(stored);
        return SERVABLE.contains(type) ? type : "application/octet-stream";
    }

    static String detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length >= 4 && unsigned(bytes, 0) == 0xFF && unsigned(bytes, 1) == 0xD8 && unsigned(bytes, 2) == 0xFF) return JPEG;
        if (png(bytes)) return PNG;
        if (webp(bytes)) return WEBP;
        if (starts(bytes, 0, "%PDF-") && pdfEnd(bytes)) return PDF;
        if (bytes.length >= 8 && starts(bytes, 4, "ftyp")) return mp4(bytes) ? MP4 : null;
        if (bytes.length >= 4 && unsigned(bytes, 0) == 0x1A && unsigned(bytes, 1) == 0x45
                && unsigned(bytes, 2) == 0xDF && unsigned(bytes, 3) == 0xA3) return webm(bytes) ? WEBM : null;
        String text = text(bytes);
        if (text == null) return null;
        if (markup(text)) return HTML;
        return json(text) ? JSON : TEXT;
    }

    /** 中文后接英文开头的名称时留一个空格。 */
    private static String spaced(String label) {
        return label.charAt(0) < 128 ? " " + label : label;
    }

    private static String allowedText(List<String> types) {
        return types.stream().map(LABELS::get).collect(Collectors.joining("、"));
    }

    private static String extension(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String type) {
        String value = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(value, value);
    }

    private static boolean png(byte[] b) {
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length < 24) return false;
        for (int i = 0; i < signature.length; i++) if (unsigned(b, i) != signature[i]) return false;
        return starts(b, 12, "IHDR");
    }

    private static boolean webp(byte[] b) {
        if (b.length < 20 || !starts(b, 0, "RIFF") || !starts(b, 8, "WEBP")) return false;
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(b, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        return size + 8 == b.length && (starts(b, 12, "VP8 ") || starts(b, 12, "VP8L") || starts(b, 12, "VP8X"));
    }

    private static boolean pdfEnd(byte[] b) {
        int from = Math.max(0, b.length - 1024);
        return new String(b, from, b.length - from, StandardCharsets.ISO_8859_1).contains("%%EOF");
    }

    /** 顶层 box 必须首尾相接覆盖整个文件，且含文件类型、媒体描述和媒体数据（含分段录制格式）。 */
    private static boolean mp4(byte[] b) {
        long offset = 0;
        boolean first = true, moov = false, media = false;
        while (offset < b.length) {
            if (b.length - offset < 8) return false;
            int at = (int) offset;
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(b, at, 4).getInt());
            String box = new String(b, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                if (b.length - offset < 16) return false;
                size = ByteBuffer.wrap(b, at + 8, 8).getLong();
                if (size < 16) return false;
            } else if (size == 0) {
                size = b.length - offset;
            }
            if (size < 8 || size > b.length - offset) return false;
            if (first && !"ftyp".equals(box)) return false;
            first = false;
            if ("moov".equals(box)) moov = true;
            if ("mdat".equals(box) || "moof".equals(box)) media = true;
            offset += size;
        }
        return moov && media;
    }

    /** EBML 头声明的文档类型必须是 webm（普通 Matroska 不收），其后紧跟媒体段。 */
    private static boolean webm(byte[] b) {
        long[] headerSize = vint(b, 4, true);
        if (headerSize == null || headerSize[0] > 4096) return false;
        int offset = 4 + (int) headerSize[1];
        long end = offset + headerSize[0];
        if (end > b.length - 4) return false;
        boolean webm = false;
        while (offset < end) {
            long[] id = vint(b, offset, false);
            if (id == null || id[1] > 4) return false;
            offset += (int) id[1];
            long[] size = vint(b, offset, true);
            if (size == null) return false;
            offset += (int) size[1];
            if (size[0] > end - offset) return false;
            if (id[0] == 0x4282) webm = size[0] == 4 && starts(b, offset, "webm");
            offset += (int) size[0];
        }
        int segment = (int) end;
        return webm && unsigned(b, segment) == 0x18 && unsigned(b, segment + 1) == 0x53
                && unsigned(b, segment + 2) == 0x80 && unsigned(b, segment + 3) == 0x67;
    }

    private static long[] vint(byte[] b, int offset, boolean removeMarker) {
        if (offset >= b.length) return null;
        int first = unsigned(b, offset), marker = 0x80, length = 1;
        while (length <= 8 && (first & marker) == 0) { marker >>= 1; length++; }
        if (length > 8 || offset + length > b.length) return null;
        long value = removeMarker ? first & (marker - 1) : first;
        for (int i = 1; i < length; i++) value = (value << 8) | unsigned(b, offset + i);
        return new long[]{value, length};
    }

    /**
     * 文本：除换行、回车、制表、换页和终端颜色转义外不含控制字符（程序、压缩包等二进制文件都有）。
     * 优先按 UTF-8（可带 BOM）读；不是 UTF-8 的本地编码文本（如 GBK 日志）也算文本，只按 ASCII 部分判断格式。
     */
    private static String text(byte[] b) {
        for (byte value : b) {
            int c = Byte.toUnsignedInt(value);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f' && c != 0x1B) || c == 0x7F) return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException ex) {
            text = new String(b, StandardCharsets.ISO_8859_1);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text.isBlank() ? null : text;
    }

    /** 网页、SVG 这类标记内容可以夹带脚本，按网页文件识别，不当普通文本收。 */
    private static boolean markup(String text) {
        String head = text.stripLeading();
        if (!head.startsWith("<")) return false;
        head = head.substring(0, Math.min(head.length(), 1024)).toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.contains("<html") || head.contains("<head")
                || head.contains("<body") || head.contains("<script") || head.contains("<svg") || head.contains("<iframe");
    }

    private static boolean json(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;
        try {
            return STRICT_JSON.readValue(trimmed) != null;
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static boolean starts(byte[] b, int offset, String prefix) {
        return offset >= 0 && b.length >= offset + prefix.length()
                && new String(b, offset, prefix.length(), StandardCharsets.ISO_8859_1).equals(prefix);
    }

    private static int unsigned(byte[] b, int index) {
        return index < b.length ? Byte.toUnsignedInt(b[index]) : -1;
    }
}
