package com.uav.lowaltitude.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.platform.config.AppProperties;

@Component
public class LocalObjectStorage implements ObjectStoragePort {

    private final Path root;

    public LocalObjectStorage(AppProperties properties) {
        this.root = Path.of(properties.getEvidenceDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes evidence dir");
        }
        return resolved;
    }

    @Override
    public void save(String relativePath, InputStream content) {
        putNew(relativePath, content);
    }

    @Override
    public StoredObject putNew(String relativePath, InputStream content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path target = resolve(relativePath);
            Files.createDirectories(target.getParent());
            long size;
            try (DigestInputStream in = new DigestInputStream(content, digest);
                    OutputStream out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                size = in.transferTo(out);
            }
            return new StoredObject(relativePath, size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<InputStream> open(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.newInputStream(target));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    @Override
    public void deleteIfPresent(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
