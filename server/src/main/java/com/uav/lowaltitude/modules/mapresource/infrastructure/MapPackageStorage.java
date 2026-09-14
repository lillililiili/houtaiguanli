package com.uav.lowaltitude.modules.mapresource.infrastructure;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.config.MapPackageProperties;

@Component
public class MapPackageStorage {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".json", ".pmtiles", ".pbf", ".png", ".otf", ".txt");

    private final Path root;
    private final long maxUploadBytes;
    private final long maxExpandedBytes;
    private final int maxFiles;

    public MapPackageStorage(MapPackageProperties properties) {
        root = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
        maxUploadBytes = properties.getMaxUploadBytes();
        maxExpandedBytes = properties.getMaxExpandedBytes();
        maxFiles = properties.getMaxFiles();
    }

    public StagedArchive stage(MultipartFile upload, String packageId) {
        if (upload == null || upload.isEmpty()) throw invalid("请选择 ZIP 地图包");
        String original = Optional.ofNullable(upload.getOriginalFilename()).orElse("").trim();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".zip")) throw invalid("地图包仅支持 .zip 格式");
        if (original.length() > 256) throw invalid("地图包文件名最多 256 个字符");
        if (upload.getSize() <= 0 || upload.getSize() > maxUploadBytes) {
            throw invalid("地图包大小必须在 1 字节至 " + mib(maxUploadBytes) + " MiB 之间");
        }
        try {
            Path staging = inside(root.resolve(".staging"));
            Files.createDirectories(staging);
            Path target = inside(staging.resolve(packageId + ".zip"));
            MessageDigest digest = sha256();
            long size = 0;
            boolean tooLarge = false;
            try (InputStream raw = upload.getInputStream();
                    DigestInputStream in = new DigestInputStream(new BufferedInputStream(raw), digest);
                    OutputStream out = new BufferedOutputStream(Files.newOutputStream(target,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    size += count;
                    if (size > maxUploadBytes) {
                        tooLarge = true;
                        break;
                    }
                    out.write(buffer, 0, count);
                }
            }
            if (tooLarge) {
                Files.deleteIfExists(target);
                throw invalid("地图包超过 " + mib(maxUploadBytes) + " MiB 上限");
            }
            return new StagedArchive(target, size, HexFormat.of().formatHex(digest.digest()), original);
        } catch (IOException ex) {
            throw storageFailure("地图包暂存失败", ex);
        }
    }

    public ExtractedArchive extract(StagedArchive staged, String packageId) {
        Path incoming = inside(root.resolve(".incoming").resolve(packageId));
        try {
            Files.createDirectories(incoming.getParent());
            Files.createDirectory(incoming);
            List<Path> files = new ArrayList<>();
            Set<String> normalizedNames = new HashSet<>();
            long expanded = 0;
            try (ZipFile zip = new ZipFile(staged.path().toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String rawName = entry.getName();
                    Path relative = safeEntry(rawName);
                    if (entry.isDirectory()) {
                        Files.createDirectories(inside(incoming.resolve(relative)));
                        continue;
                    }
                    if (files.size() >= maxFiles) throw invalid("地图包文件数量超过 " + maxFiles + " 个");
                    String folded = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                    if (!normalizedNames.add(folded)) throw invalid("地图包包含重复文件名：" + rawName);
                    String extension = extension(relative.getFileName().toString());
                    if (!ALLOWED_EXTENSIONS.contains(extension)) throw invalid("地图包包含不允许的文件类型：" + rawName);
                    long declared = entry.getSize();
                    if (declared < 0 || declared > maxExpandedBytes || expanded > maxExpandedBytes - declared) {
                        throw invalid("地图包解压后超过 " + mib(maxExpandedBytes) + " MiB 上限");
                    }
                    long compressed = entry.getCompressedSize();
                    if (declared > 1024 * 1024 && compressed > 0 && declared / Math.max(1, compressed) > 200) {
                        throw invalid("地图包压缩比异常，已拒绝解压");
                    }
                    Path output = inside(incoming.resolve(relative));
                    Files.createDirectories(output.getParent());
                    long copied = 0;
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                            OutputStream out = new BufferedOutputStream(Files.newOutputStream(output,
                                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = in.read(buffer)) >= 0) {
                            copied += count;
                            if (copied > declared || expanded + copied > maxExpandedBytes) {
                                throw invalid("地图包解压数据长度异常");
                            }
                            out.write(buffer, 0, count);
                        }
                    }
                    if (copied != declared) throw invalid("地图包文件长度不完整：" + rawName);
                    expanded += copied;
                    files.add(output);
                }
            }
            if (files.size() < 3) throw invalid("地图包内容不完整");
            return new ExtractedArchive(incoming, List.copyOf(files), expanded);
        } catch (ApiException ex) {
            deleteTree(incoming);
            throw ex;
        } catch (IOException ex) {
            deleteTree(incoming);
            throw invalid("ZIP 地图包无法读取或已损坏");
        }
    }

    public Path publish(ExtractedArchive extracted, String packageId) {
        Path packages = inside(root.resolve("packages"));
        Path target = inside(packages.resolve(packageId));
        try {
            Files.createDirectories(packages);
            move(extracted.directory(), target, false);
            return target;
        } catch (IOException ex) {
            throw storageFailure("地图包发布到版本目录失败", ex);
        }
    }

    public String relativePackagePath(String packageId) {
        return "packages/" + packageId;
    }

    public Path resolvePackagePath(String packagePath) {
        Path packages = inside(root.resolve("packages"));
        Path resolved = inside(root.resolve(packagePath));
        if (!resolved.startsWith(packages)) throw new IllegalArgumentException("path escapes map packages");
        return resolved;
    }

    public Optional<byte[]> readRuntimeConfig() {
        Path file = runtimeConfigPath();
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException ex) {
            throw storageFailure("读取地图运行配置失败", ex);
        }
    }

    public void writeRuntimeConfig(byte[] content) {
        Path target = runtimeConfigPath();
        Path temp = inside(target.getParent().resolve("map-config.json.tmp-" + UUID.randomUUID()));
        try {
            Files.createDirectories(target.getParent());
            Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            move(temp, target, true);
        } catch (IOException ex) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw storageFailure("切换地图运行配置失败", ex);
        }
    }

    public void deleteRuntimeConfig() {
        try {
            Files.deleteIfExists(runtimeConfigPath());
        } catch (IOException ex) {
            throw storageFailure("恢复地图运行配置失败", ex);
        }
    }

    public boolean runtimeConfigReady() {
        return Files.isRegularFile(runtimeConfigPath());
    }

    public void deleteStaged(StagedArchive staged) {
        if (staged == null) return;
        try { Files.deleteIfExists(inside(staged.path())); } catch (IOException ignored) { }
    }

    public void discardExtracted(ExtractedArchive extracted) {
        if (extracted == null) return;
        deleteTree(extracted.directory());
    }

    public void deletePackage(String packagePath) {
        deleteTree(resolvePackagePath(packagePath));
    }

    private Path runtimeConfigPath() {
        return inside(root.resolve("control").resolve("map-config.json"));
    }

    private Path safeEntry(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.contains(":")) throw invalid("地图包包含非法路径");
        Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")
                || relative.toString().startsWith(".")) throw invalid("地图包包含非法路径：" + name);
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("..") || value.startsWith(".")) throw invalid("地图包包含非法路径：" + name);
        }
        return relative;
    }

    private Path inside(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("path escapes map data dir");
        return normalized;
    }

    private void deleteTree(Path path) {
        Path target = inside(path);
        if (!Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        } catch (IOException ex) {
            throw storageFailure("清理地图包文件失败", ex);
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        var options = replace
                ? new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING }
                : new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE };
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException ex) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static long mib(long bytes) { return Math.max(1, bytes / (1024 * 1024)); }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MAP_PACKAGE_INVALID", message);
    }

    private static ApiException storageFailure(String message, Exception cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAP_STORAGE_UNAVAILABLE",
                message + "：" + cause.getMessage());
    }

    public record StagedArchive(Path path, long sizeBytes, String sha256, String originalFilename) { }
    public record ExtractedArchive(Path directory, List<Path> files, long expandedBytes) { }
}
