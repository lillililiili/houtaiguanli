package com.uav.lowaltitude.modules.mapresource.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.CatalogDto;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.PackageDto;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.RuntimeDto;
import com.uav.lowaltitude.modules.mapresource.application.MapPackageValidator.ValidatedPackage;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageRepository;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageRepository.PackageRow;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageRepository.RuntimeRow;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageStorage;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageStorage.ExtractedArchive;
import com.uav.lowaltitude.modules.mapresource.infrastructure.MapPackageStorage.StagedArchive;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.MapPackageProperties;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class MapPackageService {
    private static final Logger log = LoggerFactory.getLogger(MapPackageService.class);
    private static final String CONFIG_URL = "/map-data/control/map-config.json";

    private final MapPackageRepository repository;
    private final MapPackageStorage storage;
    private final MapPackageValidator validator;
    private final MapPackageAccessPolicy access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final MapPackageProperties properties;
    private final AppClock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MapPackageService(MapPackageRepository repository, MapPackageStorage storage, MapPackageValidator validator,
            MapPackageAccessPolicy access, IdempotencyGuard idempotency, AuditService audit,
            MapPackageProperties properties, AppClock clock, ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.storage = storage;
        this.validator = validator;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    public CatalogDto catalog() {
        access.requireRead();
        return catalogInternal();
    }

    /** 数据库是运行状态的事实源；启动时修复极端宕机窗口中可能遗留的文件指针。 */
    public void reconcileRuntimePointer() {
        RuntimeRow runtime = repository.runtime(false);
        if (runtime.activePackageId() == null) {
            // 数据库没有启用版本时，磁盘指针只能是上一次数据库/环境遗留的数据。
            // 前台会优先读取该文件，因此必须清理，避免管理端显示“尚未启用”却仍加载旧地图。
            storage.deleteRuntimeConfig();
            return;
        }
        PackageRow active = repository.find(runtime.activePackageId(), false);
        if (active == null || !"ACTIVE".equals(active.status())) {
            throw new IllegalStateException("active map runtime row is inconsistent");
        }
        long activatedAt = active.activatedAt() == null
                ? (runtime.updatedAt() == null ? clock.nowMillis() : runtime.updatedAt())
                : active.activatedAt();
        storage.writeRuntimeConfig(runtimeConfig(active, runtime.revision(), activatedAt));
    }

    public PackageDto upload(MultipartFile file, String cityCode, String cityName, String reason,
            String idempotencyKey, String ip, String userAgent) {
        access.requireUpload();
        String normalizedCityCode = required(cityCode, "城市编码", 32);
        if (!normalizedCityCode.matches("[A-Za-z0-9_-]+")) invalid("城市编码只能包含字母、数字、下划线和连字符");
        String normalizedCityName = required(cityName, "城市名称", 64);
        String normalizedReason = required(reason, "上传原因", 500);
        String packageId = UUID.randomUUID().toString();
        StagedArchive staged = null;
        ExtractedArchive extracted = null;
        boolean published = false;
        try {
            staged = storage.stage(file, packageId);
            if (repository.findIdByArchiveSha256(staged.sha256()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_DUPLICATE", "相同地图包已上传，请勿重复提交");
            }
            extracted = storage.extract(staged, packageId);
            ValidatedPackage valid = validator.validate(extracted, normalizedCityCode, normalizedCityName);
            storage.publish(extracted, packageId);
            published = true;
            StagedArchive finalStaged = staged;
            PackageDto created = tx.execute(status -> {
                idempotency.claim(idempotencyKey, "map-upload:" + finalStaged.sha256() + ":" + normalizedCityCode);
                if (repository.findIdByArchiveSha256(finalStaged.sha256()) != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_DUPLICATE", "相同地图包已上传，请勿重复提交");
                }
                AuthUser actor = AuthContext.require();
                long now = clock.nowMillis();
                PackageRow row = new PackageRow(packageId, valid.packageName(), normalizedCityCode, normalizedCityName,
                        valid.dataVersion(), valid.coordinateSystem(), finalStaged.originalFilename(),
                        storage.relativePackagePath(packageId), finalStaged.sha256(), valid.manifestSha256(),
                        valid.sizeBytes(), valid.fileCount(), valid.boundsWest(), valid.boundsSouth(),
                        valid.boundsEast(), valid.boundsNorth(), valid.minZoom(), valid.maxZoom(),
                        valid.displayMaxZoom(), valid.manifestJson(), "VALIDATED", valid.validationMessage(),
                        actor.userId(), actor.name(), now, null, null, null, 0);
                try {
                    repository.insert(row);
                } catch (DataIntegrityViolationException duplicate) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_DUPLICATE", "相同地图包已上传，请勿重复提交");
                }
                audit(actor, "map_package_uploaded", packageId,
                        "city=" + normalizedCityName + "; data_version=" + valid.dataVersion()
                                + "; reason=" + normalizedReason, ip, userAgent);
                return dto(row);
            });
            return created;
        } catch (RuntimeException ex) {
            if (published) safeDelete(storage.relativePackagePath(packageId));
            else if (extracted != null) safeDiscard(extracted);
            throw ex;
        } finally {
            storage.deleteStaged(staged);
        }
    }

    public CatalogDto activate(String packageId, int expectedRuntimeVersion, String reason,
            String idempotencyKey, String ip, String userAgent) {
        access.requireActivate();
        String normalizedReason = required(reason, "启用原因", 500);
        switchTo(packageId, expectedRuntimeVersion, normalizedReason, idempotencyKey,
                "map_package_activated", ip, userAgent);
        return catalogInternal();
    }

    public CatalogDto rollback(int expectedRuntimeVersion, String reason, String idempotencyKey,
            String ip, String userAgent) {
        access.requireActivate();
        String normalizedReason = required(reason, "回滚原因", 500);
        RuntimeRow runtime = repository.runtime(false);
        if (runtime.previousPackageId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MAP_ROLLBACK_UNAVAILABLE", "当前没有可回滚的历史地图");
        }
        switchTo(runtime.previousPackageId(), expectedRuntimeVersion, normalizedReason, idempotencyKey,
                "map_package_rolled_back", ip, userAgent);
        return catalogInternal();
    }

    public CatalogDto delete(String packageId, int expectedPackageVersion, String reason,
            String idempotencyKey, String ip, String userAgent) {
        access.requireDelete();
        String normalizedReason = required(reason, "删除原因", 500);
        String packagePath = tx.execute(status -> {
            idempotency.claim(idempotencyKey,
                    "map-delete:" + packageId + ":" + expectedPackageVersion + ":" + normalizedReason);
            RuntimeRow runtime = repository.runtime(true);
            if (packageId.equals(runtime.activePackageId()) || packageId.equals(runtime.previousPackageId())) {
                throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_IN_USE", "当前启用或回滚保留的地图不能删除");
            }
            PackageRow row = find(packageId, true);
            if (repository.markDeleted(packageId, expectedPackageVersion, AuthContext.require().userId(),
                    clock.nowMillis(), normalizedReason) != 1) conflict();
            audit(AuthContext.require(), "map_package_deleted", packageId,
                    "city=" + row.cityName() + "; data_version=" + row.dataVersion()
                            + "; reason=" + normalizedReason, ip, userAgent);
            return row.packagePath();
        });
        safeDelete(packagePath);
        return catalogInternal();
    }

    private void switchTo(String packageId, int expectedRuntimeVersion, String reason, String idempotencyKey,
            String action, String ip, String userAgent) {
        tx.executeWithoutResult(status -> {
            idempotency.claim(idempotencyKey,
                    action + ":" + packageId + ":" + expectedRuntimeVersion + ":" + reason);
            RuntimeRow runtime = repository.runtime(true);
            if (runtime.version() != expectedRuntimeVersion) conflict();
            if (packageId.equals(runtime.activePackageId())) {
                throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_ALREADY_ACTIVE", "该地图已处于启用状态");
            }
            PackageRow target = find(packageId, true);
            if (!("VALIDATED".equals(target.status()) || "RETIRED".equals(target.status()))) {
                throw new ApiException(HttpStatus.CONFLICT, "MAP_PACKAGE_NOT_ACTIVATABLE", "该地图当前不能启用");
            }
            byte[] previousConfig = storage.readRuntimeConfig().orElse(null);
            registerConfigRollback(previousConfig);
            AuthUser actor = AuthContext.require();
            long now = clock.nowMillis();
            long revision = Math.max(runtime.revision() + 1, now);
            repository.retire(runtime.activePackageId());
            if (repository.activate(packageId, target.version(), actor.userId(), actor.name(), now) != 1) conflict();
            if (repository.updateRuntime(packageId, runtime.activePackageId(), runtime.version(), revision,
                    actor.userId(), now) != 1) conflict();
            storage.writeRuntimeConfig(runtimeConfig(target, revision, now));
            audit(actor, action, packageId,
                    "city=" + target.cityName() + "; data_version=" + target.dataVersion() + "; reason=" + reason,
                    ip, userAgent);
        });
    }

    private CatalogDto catalogInternal() {
        RuntimeRow runtime = repository.runtime(false);
        List<PackageDto> items = repository.list().stream().map(MapPackageService::dto).toList();
        PackageDto active = items.stream().filter(item -> item.packageId().equals(runtime.activePackageId()))
                .findFirst().orElse(null);
        boolean businessVisible = active == null || properties.getBusinessCityCode().equals(active.cityCode());
        return new CatalogDto(items, new RuntimeDto(runtime.activePackageId(), runtime.previousPackageId(),
                runtime.revision(), runtime.version(), CONFIG_URL, runtimePointerMatches(runtime),
                properties.getBusinessCityCode(), businessVisible));
    }

    private boolean runtimePointerMatches(RuntimeRow runtime) {
        if (runtime.activePackageId() == null) return false;
        return storage.readRuntimeConfig().map(bytes -> {
            try {
                var value = json.readTree(bytes);
                return runtime.activePackageId().equals(value.path("package_id").asText())
                        && runtime.revision() == value.path("revision").asLong(-1);
            } catch (Exception ex) {
                return false;
            }
        }).orElse(false);
    }

    private byte[] runtimeConfig(PackageRow target, long revision, long activatedAt) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", 1);
        config.put("revision", revision);
        config.put("package_id", target.packageId());
        config.put("city_code", target.cityCode());
        config.put("city_name", target.cityName());
        config.put("data_version", target.dataVersion());
        config.put("business_data_city_code", properties.getBusinessCityCode());
        config.put("clear_business_overlays", !properties.getBusinessCityCode().equals(target.cityCode()));
        config.put("activated_at", activatedAt);
        config.put("manifest", "/map-data/" + target.packagePath() + "/manifest.json");
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(config).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize map runtime config", ex);
        }
    }

    private void registerConfigRollback(byte[] previousConfig) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    if (previousConfig == null) storage.deleteRuntimeConfig();
                    else storage.writeRuntimeConfig(previousConfig);
                } catch (RuntimeException ex) {
                    log.error("failed to restore map runtime config after transaction rollback", ex);
                }
            }
        });
    }

    private void audit(AuthUser actor, String action, String packageId, String detail, String ip, String userAgent) {
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "maps", action, "map_package", packageId,
                detail, "SUCCESS", safe(ip), safe(userAgent));
    }

    private PackageRow find(String packageId, boolean lock) {
        PackageRow row = repository.find(packageId, lock);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "MAP_PACKAGE_NOT_FOUND", "地图包不存在");
        return row;
    }

    private void safeDelete(String path) {
        try { storage.deletePackage(path); }
        catch (RuntimeException ex) { log.warn("map package files could not be removed: {}", path, ex); }
    }

    private void safeDiscard(ExtractedArchive archive) {
        try { storage.discardExtracted(archive); }
        catch (RuntimeException ex) { log.warn("map incoming files could not be removed: {}", archive.directory(), ex); }
    }

    private static PackageDto dto(PackageRow row) {
        return new PackageDto(row.packageId(), row.packageName(), row.cityCode(), row.cityName(), row.dataVersion(),
                row.coordinateSystem(), row.archiveName(), row.archiveSha256(), row.manifestSha256(), row.sizeBytes(),
                row.fileCount(), List.of(row.boundsWest(), row.boundsSouth(), row.boundsEast(), row.boundsNorth()),
                row.minZoom(), row.maxZoom(), row.displayMaxZoom(), row.status(), row.validationMessage(),
                row.uploadedByName(), row.uploadedAt(), row.activatedByName(), row.activatedAt(), row.version());
    }

    private static String required(String value, String label, int max) {
        String normalized = safe(value).trim();
        if (normalized.isEmpty() || normalized.length() > max) invalid(label + "不能为空且最多 " + max + " 个字符");
        return normalized;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被其他操作更新，请刷新后重试");
    }
}
