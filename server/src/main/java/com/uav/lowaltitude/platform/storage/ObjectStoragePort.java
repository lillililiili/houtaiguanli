package com.uav.lowaltitude.platform.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public interface ObjectStoragePort {

    Path resolve(String relativePath);

    void save(String relativePath, InputStream content);

    /** 写入新对象并返回实际字节数与内容 SHA-256（小写 hex）。已存在的 key 拒绝覆盖。 */
    StoredObject putNew(String relativePath, InputStream content);

    Optional<InputStream> open(String relativePath);

    boolean exists(String relativePath);

    /** 删除对象。路径不存在视为成功，不抛错。 */
    void deleteIfPresent(String relativePath);

    record StoredObject(String relativePath, long sizeBytes, String sha256) { }
}
