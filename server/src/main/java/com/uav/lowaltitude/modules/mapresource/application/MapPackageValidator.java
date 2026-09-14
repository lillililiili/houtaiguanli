package com.uav.lowaltitude.modules.mapresource.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageStorage.ExtractedArchive;
import com.uav.lowaltitude.platform.api.ApiException;

@Component
public class MapPackageValidator {
    private static final double GEO_TOLERANCE = 0.0000002d;

    private final ObjectMapper json;

    public MapPackageValidator(ObjectMapper json) {
        this.json = json;
    }

    public ValidatedPackage validate(ExtractedArchive archive, String requestedCityCode, String requestedCityName) {
        Path root = archive.directory();
        Path manifestFile = requiredRootFile(root, "manifest.json");
        Path checksumsFile = requiredRootFile(root, "checksums.json");
        try {
            verifyChecksums(root, checksumsFile);
            byte[] manifestBytes = Files.readAllBytes(manifestFile);
            JsonNode manifest = json.readTree(manifestBytes);
            if (manifest.path("version").asInt(-1) != 1) invalid("manifest.json 的 version 必须为 1");
            if (!"WGS84".equals(manifest.path("coordinateSystem").asText())) {
                invalid("地图坐标系必须为 WGS84");
            }
            String packageName = requiredText(manifest, "name", 100);
            String dataVersion = requiredText(manifest, "dataVersion", 64);
            String manifestCityCode = optionalText(manifest, "cityCode", "city_code");
            String manifestCityName = optionalText(manifest, "cityName", "city_name");
            if (manifestCityCode != null && !manifestCityCode.equals(requestedCityCode)) {
                invalid("上传城市编码与 manifest.json 不一致");
            }
            if (manifestCityName != null && !manifestCityName.equals(requestedCityName)) {
                invalid("上传城市名称与 manifest.json 不一致");
            }

            Path pmtiles = localFile(root, requiredText(manifest, "archive", 255), ".pmtiles");
            Path styleFile = localFile(root, requiredText(manifest, "style", 255), ".json");
            double[] bounds = bounds(manifest.path("bounds"));
            int minZoom = zoom(manifest, "minZoom", 0, 24);
            int maxZoom = zoom(manifest, "maxZoom", minZoom, 24);
            int displayMaxZoom = zoom(manifest, "displayMaxZoom", maxZoom, 24);
            if (maxZoom < 15) invalid("离线地图至少需要包含到 Z15 的矢量瓦片");
            validateStyle(root, styleFile);
            validatePmtiles(pmtiles, bounds, minZoom, maxZoom);

            return new ValidatedPackage(packageName, dataVersion, "WGS84", pmtiles.getFileName().toString(),
                    sha256(manifestFile), archive.files().size(), archive.expandedBytes(), bounds[0], bounds[1],
                    bounds[2], bounds[3], minZoom, maxZoom, displayMaxZoom,
                    json.writeValueAsString(manifest), "校验通过：清单、校验和、样式和 PMTiles 头信息一致");
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAP_PACKAGE_INVALID", "地图包校验失败：" + ex.getMessage());
        }
    }

    private void verifyChecksums(Path root, Path checksumsFile) throws IOException {
        JsonNode checksums = json.readTree(Files.readAllBytes(checksumsFile));
        if (!"SHA-256".equalsIgnoreCase(checksums.path("algorithm").asText())) {
            invalid("checksums.json 仅支持 SHA-256");
        }
        JsonNode declared = checksums.path("files");
        if (!declared.isArray() || declared.isEmpty()) invalid("checksums.json 缺少 files");
        Map<String, Path> actual = new HashMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = slash(root.relativize(path));
                if (!"checksums.json".equals(name)) actual.put(name, path);
            });
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : declared) {
            String name = requiredText(item, "path", 500);
            Path file = localFile(root, name, null);
            String normalized = slash(root.relativize(file));
            if (!seen.add(normalized.toLowerCase(Locale.ROOT))) invalid("checksums.json 包含重复路径：" + name);
            if (!actual.containsKey(normalized)) invalid("校验清单引用了不存在的文件：" + name);
            long bytes = item.path("bytes").asLong(-1);
            String expected = item.path("sha256").asText("").toLowerCase(Locale.ROOT);
            if (bytes < 0 || !expected.matches("[0-9a-f]{64}")) invalid("文件校验项格式错误：" + name);
            if (Files.size(file) != bytes || !sha256(file).equals(expected)) invalid("文件校验失败：" + name);
            actual.remove(normalized);
        }
        if (!actual.isEmpty()) invalid("地图包包含未登记校验和的文件：" + actual.keySet().iterator().next());
    }

    private void validateStyle(Path root, Path styleFile) throws IOException {
        JsonNode style = json.readTree(Files.readAllBytes(styleFile));
        if (style.path("version").asInt(-1) != 8) invalid("MapLibre 样式 version 必须为 8");
        if (style.has("imports") && !style.path("imports").isEmpty()) invalid("地图样式不得引用外部 imports");
        JsonNode sources = style.path("sources");
        if (!sources.isObject() || sources.size() != 1 || !sources.has("protomaps")) {
            invalid("地图样式必须且只能包含 protomaps 数据源");
        }
        validateOptionalAsset(root, styleFile, style.get("glyphs"));
        validateOptionalAsset(root, styleFile, style.get("sprite"));
        JsonNode faces = style.path("font-faces");
        if (!faces.isMissingNode()) {
            if (!faces.isObject()) invalid("font-faces 格式不正确");
            Iterator<JsonNode> groups = faces.elements();
            while (groups.hasNext()) {
                JsonNode group = groups.next();
                if (!group.isArray()) invalid("font-faces 字体组格式不正确");
                for (JsonNode face : group) validateOptionalAsset(root, styleFile, face.get("url"));
            }
        }
    }

    private void validateOptionalAsset(Path root, Path styleFile, JsonNode value) {
        if (value == null || value.isNull()) return;
        if (!value.isTextual()) invalid("样式资源地址格式不正确");
        String raw = value.asText();
        if (raw.contains("://") || raw.startsWith("//") || raw.startsWith("/")) {
            invalid("地图样式只能引用包内资源");
        }
        String probe = raw.replace("{fontstack}", "placeholder").replace("{range}", "0-255");
        Path candidate = styleFile.getParent().resolve(probe).normalize();
        if (!candidate.startsWith(root)) invalid("地图样式资源路径越界");
    }

    private void validatePmtiles(Path file, double[] bounds, int minZoom, int maxZoom) throws IOException {
        byte[] header = new byte[127];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(header, 0, header.length) != header.length) invalid("PMTiles 文件头不完整");
        }
        if (!new String(header, 0, 7, StandardCharsets.US_ASCII).equals("PMTiles") || header[7] != 3) {
            invalid("仅支持 PMTiles v3");
        }
        if (Byte.toUnsignedInt(header[99]) != 1) invalid("PMTiles 必须为 MVT 矢量瓦片");
        int headerMinZoom = Byte.toUnsignedInt(header[100]);
        int headerMaxZoom = Byte.toUnsignedInt(header[101]);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        double[] headerBounds = {
                buffer.getInt(102) / 10_000_000d,
                buffer.getInt(106) / 10_000_000d,
                buffer.getInt(110) / 10_000_000d,
                buffer.getInt(114) / 10_000_000d
        };
        if (headerMinZoom != minZoom || headerMaxZoom != maxZoom) invalid("PMTiles 缩放级别与 manifest.json 不一致");
        for (int index = 0; index < bounds.length; index++) {
            if (Math.abs(bounds[index] - headerBounds[index]) > GEO_TOLERANCE) {
                invalid("PMTiles 覆盖范围与 manifest.json 不一致");
            }
        }
    }

    private static Path requiredRootFile(Path root, String name) {
        Path file = root.resolve(name);
        if (!Files.isRegularFile(file)) invalid("地图包根目录缺少 " + name);
        return file;
    }

    private static Path localFile(Path root, String value, String extension) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.contains(":") || value.startsWith("/")) {
            invalid("地图包包含非法资源路径");
        }
        String normalizedValue = value.startsWith("./") ? value.substring(2) : value;
        Path file = root.resolve(normalizedValue).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) invalid("地图包资源不存在：" + value);
        if (extension != null && !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
            invalid("地图包资源类型不正确：" + value);
        }
        return file;
    }

    private static String requiredText(JsonNode node, String field, int max) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > max) invalid(field + " 缺失或长度不正确");
        return value;
    }

    private static String optionalText(JsonNode node, String camel, String snake) {
        String value = node.hasNonNull(camel) ? node.path(camel).asText("").trim()
                : node.path(snake).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static int zoom(JsonNode manifest, String field, int min, int max) {
        if (!manifest.path(field).canConvertToInt()) invalid(field + " 必须是整数");
        int value = manifest.path(field).asInt();
        if (value < min || value > max) invalid(field + " 超出允许范围");
        return value;
    }

    private static double[] bounds(JsonNode node) {
        if (!node.isArray() || node.size() != 4) invalid("bounds 必须是 [west,south,east,north]");
        double[] value = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!node.get(i).isNumber()) invalid("bounds 必须是数字");
            value[i] = node.get(i).asDouble();
            if (!Double.isFinite(value[i])) invalid("bounds 包含无效数字");
        }
        if (value[0] < -180 || value[2] > 180 || value[1] < -90 || value[3] > 90
                || value[0] >= value[2] || value[1] >= value[3]) invalid("bounds 范围无效");
        return value;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "MAP_PACKAGE_INVALID", message);
    }

    public record ValidatedPackage(String packageName, String dataVersion, String coordinateSystem,
            String archiveName, String manifestSha256, int fileCount, long sizeBytes,
            double boundsWest, double boundsSouth, double boundsEast, double boundsNorth,
            int minZoom, int maxZoom, int displayMaxZoom, String manifestJson, String validationMessage) { }
}
