package com.uav.lowaltitude.modules.fusion.application;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ConfigDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ConfigOverviewDto;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository.ConfigRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合参数版本：读取（fusion:read）与激活（fusion:manage）。激活事务顺序：鉴权 → 解析 body → 锁目标版本 → claim 幂等键
 * → expected_version → 状态守卫 → 旧 ACTIVE→RETIRED + 新→ACTIVE（同事务）→ 成功审计。E2 自己的参数视图也从这里解析（缺键即抛）。
 */
@Service
public class FusionConfigService {
    static final String MODULE = "fusion", OBJECT_TYPE = "fusion_config";
    private static final Set<String> ACTIVATE_FIELDS = Set.of("expected_version");
    private static final int VERSION_MAX = 32;
    private final AccessControlService access;
    private final FusionConfigRepository repository;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    /** 参数只读且随版本不变：按版本缓存解析结果，激活会换版本号而不是改内容。 */
    private final Map<String, FusionParams> cache = new ConcurrentHashMap<>();

    public FusionConfigService(AccessControlService access, FusionConfigRepository repository, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.repository = repository; this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional(readOnly = true)
    public ConfigOverviewDto config() {
        access.require(PermissionCode.FUSION_READ);
        ConfigRow active = repository.findActive();
        List<ConfigDto> versions = new ArrayList<>();
        for (ConfigRow row : repository.listAll()) versions.add(dto(row));
        return new ConfigOverviewDto(active == null ? null : dto(active), versions);
    }

    @Transactional
    public ConfigDto activate(String versionValue, String rawRequest, String idempotencyKey) {
        access.require(PermissionCode.FUSION_MANAGE);
        long expected = parseExpectedVersion(rawRequest);
        String version = version(versionValue);
        ConfigRow target = repository.lock(version);
        if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "融合参数版本不存在");
        idempotency.claim(idempotencyKey, framed("fusion-config:ACTIVATE") + framed(version) + framed(Long.toString(expected)));
        if (target.version() != expected) throw versionConflict();
        if ("ACTIVE".equals(target.status())) throw new ApiException(HttpStatus.CONFLICT, "CONFIG_ALREADY_ACTIVE", "该版本已是生效版本");
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 旧 ACTIVE→RETIRED 与新版本激活必须同一事务：任一失败整体回滚，不会出现零个或两个生效版本。
        repository.retireActive(version);
        if (repository.activate(version, expected, at) != 1) throw versionConflict();
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "fusion_config_activated", OBJECT_TYPE, version,
                "expected_version=" + expected + "; schema_status=" + target.schemaStatus(), "SUCCESS", "", "");
        ConfigRow updated = repository.find(version);
        if (updated == null) throw new IllegalStateException("fusion_config disappeared after activation: " + version);
        return dto(updated);
    }

    /** 当前 ACTIVE 版本的参数视图；给定版本号时按该版本解析（回放/迟到帧按帧上的 config_version）。 */
    public FusionParams params(String configVersion) {
        if (configVersion == null) {
            ConfigRow active = repository.findActive();
            if (active == null) throw new IllegalStateException("no ACTIVE fusion_config");
            return cache.computeIfAbsent(active.configVersion(), v -> parseParams(active));
        }
        return cache.computeIfAbsent(configVersion, v -> {
            ConfigRow row = repository.find(v);
            if (row == null) throw new IllegalStateException("fusion_config version not found: " + v);
            return parseParams(row);
        });
    }

    public String activeVersion() {
        ConfigRow active = repository.findActive();
        if (active == null) throw new IllegalStateException("no ACTIVE fusion_config");
        return active.configVersion();
    }

    private FusionParams parseParams(ConfigRow row) {
        try {
            JsonNode root = json.readTree(row.paramsJson());
            // H2 有时把 JSON 列回读成带引号的文本：再解析一层。
            if (root != null && root.isTextual()) root = json.readTree(root.textValue());
            if (root == null || !root.isObject()) throw new IllegalStateException("fusion_config.params is not an object: " + row.configVersion());
            return new JsonFusionParams(row.configVersion(), root);
        } catch (IOException ex) {
            throw new IllegalStateException("fusion_config.params unreadable: " + row.configVersion(), ex);
        }
    }

    private ConfigDto dto(ConfigRow row) {
        Object params;
        try {
            JsonNode node = json.readTree(row.paramsJson());
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            params = node;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        }
        return new ConfigDto(row.configVersion(), row.status(), row.schemaStatus(), params, row.note(), millis(row.createdAt()), millis(row.activatedAt()), row.version());
    }

    private long parseExpectedVersion(String raw) {
        if (raw == null || raw.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) { String name = names.next(); if (!ACTIVATE_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name); }
            JsonNode expected = node.get("expected_version");
            if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0) throw invalidRequest();
            return expected.longValue();
        } catch (IOException ex) {
            throw invalidRequest();
        }
    }

    private static String version(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > VERSION_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "版本号格式无效");
        return v;
    }

    static String framed(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ":" + value; }
    static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }
    static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "对象已被其他操作更新"); }
    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }

    /** fusion_config.params 的只读视图：缺组/缺键/类型不符一律抛 IllegalStateException——参数缺失是部署错误，不能用代码默认值掩盖。 */
    static final class JsonFusionParams implements FusionParams {
        private final String version;
        private final JsonNode root;
        private final Map<String, Double> accuracyDefaults;
        private final Map<String, Map<String, Double>> weights;

        JsonFusionParams(String version, JsonNode root) {
            this.version = version; this.root = root;
            JsonNode defaults = root.path("filter").path("accuracy_default_m");
            if (!defaults.isObject()) throw new IllegalStateException("missing fusion param filter.accuracy_default_m");
            Map<String, Double> acc = new LinkedHashMap<>();
            defaults.fields().forEachRemaining(e -> acc.put(e.getKey(), requiredNumber(e.getValue(), "filter.accuracy_default_m." + e.getKey())));
            this.accuracyDefaults = Map.copyOf(acc);
            JsonNode w = root.path("weights");
            if (!w.isObject()) throw new IllegalStateException("missing fusion param weights");
            Map<String, Map<String, Double>> byType = new HashMap<>();
            w.fields().forEachRemaining(e -> {
                Map<String, Double> attrs = new HashMap<>();
                e.getValue().fields().forEachRemaining(a -> attrs.put(a.getKey(), requiredNumber(a.getValue(), "weights." + e.getKey() + "." + a.getKey())));
                byType.put(e.getKey(), Map.copyOf(attrs));
            });
            this.weights = Map.copyOf(byType);
        }

        @Override public String configVersion() { return version; }
        @Override public double number(String group, String key) { return requiredNumber(root.path(group).path(key), group + "." + key); }
        @Override public int integer(String group, String key) { return (int) Math.round(number(group, key)); }
        @Override public Map<String, Double> accuracyDefaults() { return accuracyDefaults; }
        @Override public Map<String, Map<String, Double>> weights() { return weights; }

        private static double requiredNumber(JsonNode node, String path) {
            if (node == null || !node.isNumber()) throw new IllegalStateException("missing fusion param " + path);
            return node.doubleValue();
        }
    }
}
