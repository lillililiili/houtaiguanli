package com.uav.lowaltitude.modules.evidence.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.FileRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;

/** 预览只读取原件；不修改哈希、文件状态、冻结或历史材料。 */
@Service
public class EvidencePreviewService {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final AccessControlService access;
    private final EvidenceAssociationService evidence;
    private final ObjectStoragePort storage;
    private final EvidencePreviewAuditService audit;
    private final ObjectMapper json;

    public EvidencePreviewService(AccessControlService access, EvidenceAssociationService evidence,
            ObjectStoragePort storage, EvidencePreviewAuditService audit, ObjectMapper json) {
        this.access = access; this.evidence = evidence; this.storage = storage; this.audit = audit; this.json = json;
    }

    // 不包长事务；访问日志经独立事务提交，拒绝后抛错不会抹掉记录。
    public Content open(String evidenceId, boolean thumbnail) {
        String action = thumbnail ? "THUMBNAIL" : "PREVIEW";
        FileRow file = null;
        try {
            var decision = access.require(PermissionCode.EVIDENCE_PREVIEW);
            file = evidence.visibleForContent(evidenceId, decision);
            if (!"AVAILABLE".equals(file.status())) {
                String message = switch (file.status()) {
                    case "PENDING" -> "文件尚未完成入库";
                    case "MISSING" -> "证据原件缺失";
                    case "CORRUPT" -> "证据校验异常";
                    case "DESTROYED" -> "文件已销毁，仅保留元数据";
                    default -> "证据内容不可用";
                };
                throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_" + file.status(), message);
            }
            if (file.sizeBytes() == null || file.sizeBytes() > MAX_BYTES) throw tooLarge();
            byte[] bytes;
            try (var stream = storage.open(file.objectKey()).orElseThrow(() ->
                    new ApiException(HttpStatus.CONFLICT, "EVIDENCE_MISSING", "证据原件缺失"))) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw tooLarge();
            if (bytes.length != file.sizeBytes() || !digest(bytes).equals(file.sha256())) {
                throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_CORRUPT", "原件与登记哈希不符，不能预览");
            }
            Content content = validated(bytes, file.contentType(), thumbnail);
            audit.record(file.evidenceId(), action, "GRANTED", null);
            return content;
        } catch (ApiException ex) {
            audit.record(file == null ? null : file.evidenceId(), action, "DENIED", ex.getCode());
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            audit.record(file == null ? null : file.evidenceId(), action, "DENIED", "EVIDENCE_PREVIEW_UNREADABLE");
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_PREVIEW_UNREADABLE", "文件内容读取失败，请重试");
        }
    }

    private Content validated(byte[] bytes, String declared, boolean thumbnail) throws IOException {
        String type = declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (Set.of("image/png", "image/jpeg", "image/gif").contains(type)) {
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw unsupported();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String detected = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "png" -> "image/png"; case "jpeg", "jpg" -> "image/jpeg"; case "gif" -> "image/gif"; default -> "";
                    };
                    if (!type.equals(detected)) throw unsupported();
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 40_000_000L) throw unsupported();
                    var image = reader.read(0);
                    if (image == null) throw unsupported();
                    if (!thumbnail) return new Content(bytes, type);
                    double scale = Math.min(1, 320d / Math.max(width, height));
                    var small = new java.awt.image.BufferedImage(Math.max(1, (int) (width * scale)),
                            Math.max(1, (int) (height * scale)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = small.createGraphics();
                    try { graphics.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null); }
                    finally { graphics.dispose(); }
                    var output = new ByteArrayOutputStream(); ImageIO.write(small, "png", output);
                    return new Content(output.toByteArray(), "image/png");
                } finally { reader.dispose(); }
            }
        }
        if ("image/webp".equals(type) && validWebp(bytes)) {
            if (thumbnail) throw unsupported(); // JDK 没有 WebP 解码器，前端以预览卡明确降级。
            return new Content(bytes, type);
        }
        if (thumbnail) throw unsupported();
        if ("application/pdf".equals(type) && starts(bytes, "%PDF-") &&
                new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length), StandardCharsets.ISO_8859_1).contains("%%EOF")) {
            return new Content(bytes, type);
        }
        if ("video/mp4".equals(type) && validMp4(bytes)) return new Content(bytes, type);
        if ("video/webm".equals(type) && validWebm(bytes)) return new Content(bytes, type);
        if ("text/plain".equals(type) || "application/json".equals(type)) {
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (java.nio.charset.CharacterCodingException ex) { throw unsupported(); }
            if (text.chars().anyMatch(ch -> ch < 32 && ch != '\n' && ch != '\r' && ch != '\t')) throw unsupported();
            if ("application/json".equals(type)) {
                try { if (json.readTree(text) == null) throw unsupported(); }
                catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw unsupported(); }
            }
            return new Content(bytes, type + ";charset=UTF-8");
        }
        throw unsupported();
    }

    private static boolean validWebm(byte[] bytes) {
        if (bytes.length < 32 || !signature(bytes, 0, 0x1a, 0x45, 0xdf, 0xa3)) return false;
        long[] headerSize = vint(bytes, 4, true);
        if (headerSize == null || headerSize[0] > 4096) return false;
        int offset = 4 + (int) headerSize[1], end = offset + (int) headerSize[0];
        if (end > bytes.length - 4) return false;
        boolean webm = false;
        while (offset < end) {
            long[] id = vint(bytes, offset, false);
            if (id == null || id[1] > 4) return false;
            offset += (int) id[1];
            long[] size = vint(bytes, offset, true);
            if (size == null) return false;
            offset += (int) size[1];
            if (size[0] > end - offset) return false;
            if (id[0] == 0x4282) webm = size[0] == 4 && new String(bytes, offset, 4, StandardCharsets.ISO_8859_1).equals("webm");
            offset += (int) size[0];
        }
        if (!webm || !signature(bytes, end, 0x18, 0x53, 0x80, 0x67)) return false;
        // 有完整EBML头、WebM文档类型、媒体段、轨道和帧簇；解码错误由浏览器明确呈现。
        boolean tracks = false, cluster = false;
        for (int i = end + 4; i <= bytes.length - 4; i++) {
            if (signature(bytes, i, 0x16, 0x54, 0xae, 0x6b)) tracks = true;
            if (signature(bytes, i, 0x1f, 0x43, 0xb6, 0x75)) cluster = true;
        }
        return tracks && cluster;
    }
    private static long[] vint(byte[] bytes, int offset, boolean removeMarker) {
        if (offset >= bytes.length) return null;
        int first = Byte.toUnsignedInt(bytes[offset]), marker = 0x80, length = 1;
        while (length <= 8 && (first & marker) == 0) { marker >>= 1; length++; }
        if (length > 8 || offset + length > bytes.length) return null;
        long value = removeMarker ? first & (marker - 1) : first;
        for (int i = 1; i < length; i++) value = (value << 8) | Byte.toUnsignedInt(bytes[offset + i]);
        return new long[]{value, length};
    }
    private static boolean signature(byte[] bytes, int offset, int a, int b, int c, int d) {
        return offset >= 0 && offset + 4 <= bytes.length && Byte.toUnsignedInt(bytes[offset]) == a
                && Byte.toUnsignedInt(bytes[offset + 1]) == b && Byte.toUnsignedInt(bytes[offset + 2]) == c && Byte.toUnsignedInt(bytes[offset + 3]) == d;
    }

    private static boolean validWebp(byte[] bytes) {
        if (bytes.length < 30 || !starts(bytes, "RIFF") || !new String(bytes, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) return false;
        var data = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (Integer.toUnsignedLong(data.getInt(4)) != bytes.length - 8L) return false;
        int offset = 12; boolean image = false;
        while (offset <= bytes.length - 8) {
            String chunk = new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
            long length = Integer.toUnsignedLong(data.getInt(offset + 4));
            if (length > bytes.length - offset - 8L) return false;
            int content = offset + 8;
            if ("VP8 ".equals(chunk)) {
                if (length < 10 || bytes[content + 3] != (byte) 0x9d || bytes[content + 4] != 1 || bytes[content + 5] != 0x2a) return false;
                int width = Short.toUnsignedInt(data.getShort(content + 6)) & 0x3fff;
                int height = Short.toUnsignedInt(data.getShort(content + 8)) & 0x3fff;
                if (width < 1 || height < 1 || (long) width * height > 40_000_000L) return false;
                image = true;
            } else if ("VP8L".equals(chunk)) {
                if (length < 5 || bytes[content] != 0x2f) return false;
                int dimensions = data.getInt(content + 1);
                if ((long) ((dimensions & 0x3fff) + 1) * (((dimensions >>> 14) & 0x3fff) + 1) > 40_000_000L) return false;
                image = true;
            } else if ("ANMF".equals(chunk) && length > 16) image = true;
            offset += 8 + (int) length + ((int) length & 1);
        }
        return image && offset == bytes.length;
    }

    private static boolean validMp4(byte[] bytes) {
        // 校验完整顶层 box 边界；具体编码仍由浏览器解码，失败须明确显示。
        int offset = 0; boolean ftyp = false, moov = false, mdat = false;
        while (offset <= bytes.length - 8) {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).getInt());
            String box = new String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 0) size = bytes.length - offset;
            if (size < 8 || size > bytes.length - offset) return false;
            if ("ftyp".equals(box)) ftyp = size >= 16;
            if ("moov".equals(box)) moov = size > 8;
            if ("mdat".equals(box)) mdat = size > 8;
            offset += (int) size;
        }
        return offset == bytes.length && ftyp && moov && mdat;
    }
    private static boolean starts(byte[] bytes, String prefix) {
        return bytes.length >= prefix.length() && new String(bytes, 0, prefix.length(), StandardCharsets.ISO_8859_1).equals(prefix);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "EVIDENCE_PREVIEW_UNSUPPORTED", "该文件格式或内容暂不支持预览，可按权限下载原件");
    }
    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EVIDENCE_PREVIEW_TOO_LARGE", "文件超过 32 MiB 预览上限，可按权限下载原件");
    }
    public record Content(byte[] bytes, String contentType) { }
}
