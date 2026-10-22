package com.uav.lowaltitude.modules.mapresource.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageStorage;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.config.MapPackageProperties;

/** Real ZIP -> storage -> validator coverage; no Spring context, database or business seeds. */
class MapPackageImageryTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final MapPackageValidator validator = new MapPackageValidator(json);
    private MapPackageStorage storage;

    @BeforeEach
    void setup() {
        MapPackageProperties properties = new MapPackageProperties();
        properties.setDataDir(temporary.toString());
        storage = new MapPackageStorage(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vp8", "vp8l", "vp8x"})
    void acceptsComplete512WebpPackage(String encoding) throws Exception {
        var result = validate(files(imagery("webp", 512), webp(encoding)), null);
        assertThat(json.readTree(result.manifestJson()).path("imagery").path("tileSize").asInt()).isEqualTo(512);
        assertThat(result.fileCount()).isEqualTo(5);
    }

    @Test
    void legacyPackageWithoutImageryStillValidates() throws Exception {
        assertThat(validate(files(null, null), null).maxZoom()).isEqualTo(15);
    }

    @Test
    void legacyPngDefaultsTo256AndExplicit256RemainsSupported() throws Exception {
        ObjectNode imagery = imagery("png", 256);
        imagery.remove("tileSize");
        assertThat(validate(files(imagery, png(256)), null).manifestJson()).contains("imagery");
        assertThat(validate(files(imagery("png", 256), png(256)), null).manifestJson()).contains("tileSize");
    }

    @Test
    void acceptsCompleteDongyingZ0To13Pyramid() throws Exception {
        ObjectNode metadata = imagery("webp", 512).put("maxZoom", 13);
        Map<String, byte[]> files = files(metadata, null);
        double[] bounds = {117.914, 36.737, 119.508, 38.356};
        addPyramid(files, bounds, 0, 13, webp("vp8x"));
        assertThat(files.size()).isEqualTo(2442 + 3);
        assertThat(validate(files, null).fileCount()).isEqualTo(2442 + 4);
    }

    @Test
    void differentBoundsZoomRangeAndDirectoryRequireTheirOwnTiles() throws Exception {
        ObjectNode metadata = imagery("webp", 512).put("minZoom", 1).put("maxZoom", 2)
                .put("tiles", "./satellite-2025/{z}/{x}/{y}.webp");
        Map<String, byte[]> files = files(metadata, null);
        ObjectNode manifest = (ObjectNode) json.readTree(files.get("manifest.json"));
        manifest.putArray("bounds").add(-1).add(-1).add(1).add(1);
        files.put("manifest.json", json.writeValueAsBytes(manifest));
        ByteBuffer pmtiles = ByteBuffer.wrap(files.get("map.pmtiles")).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) pmtiles.putInt(102 + 4 * i, (i < 2 ? -1 : 1) * 10_000_000);
        byte[] tile = webp("vp8l");
        // This bbox crosses both the equator and prime meridian: exactly four tiles on each of Z1/Z2.
        for (int z = 1; z <= 2; z++) {
            int middle = 1 << (z - 1);
            for (int x = middle - 1; x <= middle; x++) {
                for (int y = middle - 1; y <= middle; y++) files.put("satellite-2025/" + z + "/" + x + "/" + y + ".webp", tile);
            }
        }
        assertThat(validate(files, null).fileCount()).isEqualTo(12);
        files.remove("satellite-2025/2/2/2.webp");
        rejects(files, "影像瓦片缺失");
    }

    @Test
    void missingExpectedTileFailsEvenWhenRemainingFilesHaveValidChecksums() throws Exception {
        ObjectNode imagery = imagery("webp", 512);
        imagery.put("maxZoom", 1);
        rejects(files(imagery, webp("vp8")), "影像瓦片缺失");
    }

    @ParameterizedTest
    @ValueSource(strings = {"vp8", "vp8l", "vp8x"})
    void wrongWebpDimensionsFail(String encoding) throws Exception {
        rejects(files(imagery("webp", 256), webp(encoding)), "尺寸");
    }

    @Test
    void pngDimensionsAndMismatchedExtensionFail() throws Exception {
        rejects(files(imagery("png", 512), png(256)), "尺寸");
        rejects(files(imagery("webp", 512), png(512)), "WebP");
        rejects(files(imagery("png", 512), webp("vp8")), "PNG");
    }

    @ParameterizedTest
    @ValueSource(strings = {"vp8", "vp8l", "vp8x"})
    void truncatedOrCorruptWebpHeaderFails(String encoding) throws Exception {
        byte[] corrupted = webp(encoding);
        corrupted[0] = 0;
        rejects(files(imagery("webp", 512), corrupted), "WebP");
        rejects(files(imagery("webp", 512), java.util.Arrays.copyOf(webp(encoding), 25)), "WebP");
    }

    @Test
    void corruptPngHeaderFails() throws Exception {
        byte[] corrupted = png(256);
        corrupted[12] = 'X';
        rejects(files(imagery("png", 256), corrupted), "PNG");
        corrupted = png(256);
        corrupted[29] ^= 1;
        rejects(files(imagery("png", 256), corrupted), "PNG IHDR 校验失败");
    }

    @Test
    void webpChunkAndCodecHeadersAreCheckedWithoutDecodingPixels() throws Exception {
        byte[] corrupted = webp("vp8");
        corrupted[23] = 0; // VP8 keyframe signature, while RIFF remains intact.
        rejects(files(imagery("webp", 512), corrupted), "VP8 文件头无效");
        corrupted = webp("vp8l");
        corrupted[20] = 0;
        rejects(files(imagery("webp", 512), corrupted), "VP8L 文件头无效");
        corrupted = webp("vp8x");
        corrupted[20] |= 2; // Animation must not be accepted as a static XYZ tile.
        rejects(files(imagery("webp", 512), corrupted), "不支持动画");
        corrupted = webp("vp8");
        ByteBuffer.wrap(corrupted).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Integer.MAX_VALUE);
        rejects(files(imagery("webp", 512), corrupted), "数据块长度无效");
    }

    @Test
    void rejectsInvalidZoomSizeAndMetadata() throws Exception {
        for (String field : new String[] {"minZoom", "maxZoom", "tileSize"}) {
            ObjectNode metadata = imagery("png", 256);
            metadata.put(field, 1.5);
            rejects(files(metadata, png(256)), field);
        }
        ObjectNode metadata = imagery("png", 256);
        metadata.put("maxZoom", -1);
        rejects(files(metadata, png(256)), "maxZoom");
        metadata = imagery("png", 256);
        metadata.put("maxZoom", 25);
        rejects(files(metadata, png(256)), "maxZoom");
        metadata = imagery("png", 256);
        metadata.put("tileSize", 1024);
        rejects(files(metadata, png(256)), "tileSize");
        metadata = imagery("png", 256);
        metadata.put("attribution", " ");
        rejects(files(metadata, png(256)), "attribution");
        metadata.put("attribution", 123);
        rejects(files(metadata, png(256)), "attribution");
        for (String field : new String[] {"tiles", "minZoom", "maxZoom", "attribution"}) {
            metadata = imagery("png", 256);
            metadata.remove(field);
            rejects(files(metadata, png(256)), field);
        }
        var nonObject = files(null, null);
        ObjectNode manifest = (ObjectNode) json.readTree(nonObject.get("manifest.json"));
        manifest.put("imagery", "https://example.invalid/satellite");
        nonObject.put("manifest.json", json.writeValueAsBytes(manifest));
        rejects(nonObject, "imagery");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.invalid/{z}/{x}/{y}.png", "//example.invalid/{z}/{x}/{y}.png",
            "/imagery/{z}/{x}/{y}.png", "../imagery/{z}/{x}/{y}.png",
            "./imagery/../tiles/{z}/{x}/{y}.png", "./%2e%2e/{z}/{x}/{y}.png",
            "./imagery/{z}/{x}/{y}.png?token=x", "./imagery/{z}/{x}/{y}.png#hash",
            "./imagery/{z}/{x}/{x}/{y}.png", "./imagery/{x}/{z}/{y}.png",
            "./imagery/{z}/{x}/{y}.jpg", "./imagery/{z}/{x}/{y}/{other}.png",
            "./imagery\\{z}/{x}/{y}.png", "data:imagery/{z}/{x}/{y}.png"
    })
    void rejectsNonLocalOrAmbiguousImageryTemplate(String template) throws Exception {
        ObjectNode metadata = imagery("png", 256);
        metadata.put("tiles", template);
        rejects(files(metadata, png(256)), "影像");
    }

    @Test
    void boundsCoverageIsCheckedBeforeEnumeratingHugeTileSets() throws Exception {
        ObjectNode metadata = imagery("png", 256);
        metadata.put("maxZoom", 24);
        rejects(files(metadata, png(256)), "数量");
    }

    @Test
    void imageryChecksumAndExistingStyleSecurityRemainMandatory() throws Exception {
        Map<String, byte[]> files = files(imagery("webp", 512), webp("vp8"));
        assertThatThrownBy(() -> validate(files, packaged -> packaged.put("imagery/0/0/0.webp", new byte[] {1})))
                .isInstanceOf(ApiException.class).hasMessageContaining("文件校验失败");
        ObjectNode style = (ObjectNode) json.readTree(files.get("style.json"));
        style.put("glyphs", "https://example.invalid/{fontstack}/{range}.pbf");
        files.put("style.json", json.writeValueAsBytes(style));
        rejects(files, "包内资源");
        style.remove("glyphs");
        ((ObjectNode) style.path("sources")).putObject("satellite").put("type", "raster");
        files.put("style.json", json.writeValueAsBytes(style));
        rejects(files, "只能包含 protomaps");
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:font.pbf", "./fonts/%2e%2e/file.pbf", "./fonts/../file.pbf", "./fonts\\file.pbf"})
    void malformedStyleResourceCannotEscapeLocalPackage(String resource) throws Exception {
        Map<String, byte[]> files = files(null, null);
        ObjectNode style = (ObjectNode) json.readTree(files.get("style.json"));
        style.put("glyphs", resource);
        files.put("style.json", json.writeValueAsBytes(style));
        rejects(files, "包内资源");
    }

    @Test
    void existingUploadExpandedAndFileCountLimitsRemainEnforced() throws Exception {
        MapPackageProperties properties = new MapPackageProperties();
        assertThat(properties.getMaxUploadBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(properties.getMaxExpandedBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(properties.getMaxFiles()).isEqualTo(10_000);
        properties.setDataDir(temporary.toString());
        properties.setMaxUploadBytes(10);
        storage = new MapPackageStorage(properties);
        rejects(files(null, null), "地图包大小");
        properties.setMaxUploadBytes(512L * 1024 * 1024);
        properties.setMaxExpandedBytes(20);
        storage = new MapPackageStorage(properties);
        rejects(files(null, null), "解压后超过");
        properties.setMaxExpandedBytes(2L * 1024 * 1024 * 1024);
        properties.setMaxFiles(3);
        storage = new MapPackageStorage(properties);
        rejects(files(null, null), "文件数量超过");
    }

    @Test
    void malformedArchivePathsAreStillRejected() throws Exception {
        for (String path : new String[] {"../outside.png", "./.hidden/test.png", "imagery/%2e%2e/test.png"}) {
            Map<String, byte[]> files = files(null, null);
            files.put(path, png(256));
            rejects(files, "路径");
        }
    }

    private void rejects(Map<String, byte[]> files, String message) {
        assertThatThrownBy(() -> validate(files, null)).isInstanceOf(ApiException.class).hasMessageContaining(message);
    }

    private MapPackageValidator.ValidatedPackage validate(Map<String, byte[]> files,
            Consumer<Map<String, byte[]>> afterChecksums) throws Exception {
        var packaged = new LinkedHashMap<>(files);
        ObjectNode checksums = json.createObjectNode().put("algorithm", "SHA-256");
        var entries = checksums.putArray("files");
        for (var entry : files.entrySet()) {
            entries.addObject().put("path", entry.getKey()).put("bytes", entry.getValue().length)
                    .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())));
        }
        packaged.put("checksums.json", json.writeValueAsBytes(checksums));
        if (afterChecksums != null) afterChecksums.accept(packaged);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : packaged.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        String id = UUID.randomUUID().toString();
        var staged = storage.stage(new MockMultipartFile("file", "map.zip", "application/zip", output.toByteArray()), id);
        return validator.validate(storage.extract(staged, id), "370500", "东营市");
    }

    private ObjectNode imagery(String format, int size) {
        return json.createObjectNode().put("tiles", "./imagery/{z}/{x}/{y}." + format)
                .put("tileSize", size).put("minZoom", 0).put("maxZoom", 0)
                .put("attribution", "Contains modified Copernicus Sentinel data (2025)");
    }

    private Map<String, byte[]> files(ObjectNode imagery, byte[] tile) throws Exception {
        ObjectNode manifest = json.createObjectNode().put("version", 1).put("coordinateSystem", "WGS84")
                .put("name", "东营离线地图").put("dataVersion", "20261020")
                .put("archive", "./map.pmtiles").put("style", "./style.json")
                .put("minZoom", 0).put("maxZoom", 15).put("displayMaxZoom", 18);
        manifest.putArray("bounds").add(117.914).add(36.737).add(119.508).add(38.356);
        if (imagery != null) manifest.set("imagery", imagery);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", json.writeValueAsBytes(manifest));
        files.put("style.json", "{\"version\":8,\"sources\":{\"protomaps\":{\"type\":\"vector\",\"url\":\"pmtiles://ignored\"}},\"layers\":[]}".getBytes(StandardCharsets.UTF_8));
        ByteBuffer header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN);
        header.put("PMTiles".getBytes(StandardCharsets.US_ASCII));
        header.put(7, (byte) 3).put(99, (byte) 1).put(100, (byte) 0).put(101, (byte) 15);
        for (int i = 0; i < 4; i++) header.putInt(102 + 4 * i, (int) Math.round(manifest.path("bounds").get(i).asDouble() * 10_000_000));
        files.put("map.pmtiles", header.array());
        if (tile != null) files.put("imagery/0/0/0." + (imagery.path("tiles").asText().endsWith(".png") ? "png" : "webp"), tile);
        return files;
    }

    private byte[] webp(String encoding) throws Exception {
        // Original solid-color 512px images generated once with Pillow: RGB lossy, RGB lossless, RGBA lossy.
        try (var stream = getClass().getResourceAsStream("/mapresource/" + encoding + "-512.webp")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }

    private byte[] png(int size) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private void addPyramid(Map<String, byte[]> files, double[] bounds, int minZoom, int maxZoom, byte[] tile) {
        for (int z = minZoom; z <= maxZoom; z++) {
            int count = 1 << z;
            int west = (int) Math.floor((bounds[0] + 180) / 360 * count);
            int east = (int) Math.floor((bounds[2] + 180) / 360 * count);
            int north = (int) Math.floor((1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(bounds[3]) / 2)) / Math.PI) / 2 * count);
            int south = (int) Math.floor((1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(bounds[1]) / 2)) / Math.PI) / 2 * count);
            for (int x = west; x <= east; x++) {
                for (int y = north; y <= south; y++) files.put("imagery/" + z + "/" + x + "/" + y + ".webp", tile);
            }
        }
    }
}
