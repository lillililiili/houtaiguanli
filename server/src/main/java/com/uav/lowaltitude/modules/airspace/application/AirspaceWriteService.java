package com.uav.lowaltitude.modules.airspace.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedAirspaceDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedVersionDto;
import com.uav.lowaltitude.modules.airspace.domain.AirspaceKind;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.Feature;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.ParseResult;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository.AirspaceHead;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository.VersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 空域写入：新建空域与接替式追加版本。
 * 事务顺序与阶段 5/7/8 一致：鉴权（airspace:read → airspace:manage）→ 严格解析 body → 锁空域头行
 * → claim 幂等键 → expected_version 条件更新 → 写版本与来源 → 成功审计（同事务）。失败审计由全局异常处理在事务外落库。
 *
 * 为什么是接替式而不是原地改：已保存的研判引用某一版的几何与高度带，原地改会让历史结论悄悄换掉依据。
 * 新版本插入时把上一开放版本的 valid_to 关闭为新版本的 valid_from，历史区间连续且每一版的内容都不再变（决策 9-1）。
 */
@Service
public class AirspaceWriteService {
    static final String MODULE = "airspace", OBJECT_TYPE = "airspace";
    private static final Set<String> CREATE_FIELDS = Set.of("airspace_no", "name", "kind_code", "boundary", "min_altitude_m",
            "max_altitude_m", "altitude_datum", "valid_from", "valid_to", "change_reason", "owner_org_id", "district_id");
    private static final Set<String> VERSION_FIELDS = Set.of("kind_code", "boundary", "min_altitude_m", "max_altitude_m",
            "altitude_datum", "valid_from", "valid_to", "change_reason", "expected_version");
    private static final int NO_MAX = 64, NAME_MAX = 128, REASON_MAX = 256, ID_MAX = 36;

    private final AccessControlService access;
    private final AirspaceWriteRepository repository;
    private final GeoJsonParser geoJson;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public AirspaceWriteService(AccessControlService access, AirspaceWriteRepository repository, GeoJsonParser geoJson,
            IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.repository = repository; this.geoJson = geoJson; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional
    public CreatedAirspaceDto create(String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        CreateRequest request = parseCreate(rawBody);
        requireScope(decision, request.ownerOrgId(), request.districtId());
        idempotency.claim(idempotencyKey, framed("airspace-create") + framed(request.airspaceNo()) + framed(request.name())
                + framed(request.kindCode()) + framed(Long.toString(request.validFrom().toEpochMilli())));
        if (repository.airspaceNoExists(request.airspaceNo())) {
            throw new ApiException(HttpStatus.CONFLICT, "AIRSPACE_NO_EXISTS", "空域编号已存在");
        }
        Instant now = clock.now();
        String airspaceId = UUID.randomUUID().toString(), versionId = UUID.randomUUID().toString();
        try {
            repository.insertAirspace(airspaceId, request.airspaceNo(), request.name(), request.ownerOrgId(), request.districtId(), now);
        } catch (DataIntegrityViolationException duplicate) {
            // 上面的存在性预检挡不住并发：两个请求可能同时查到"编号不存在"再一起插入。
            // airspace_no 的唯一约束是最终保障，这里把它翻成契约的 409，而不是让调用方看到 500。
            throw new ApiException(HttpStatus.CONFLICT, "AIRSPACE_NO_EXISTS", "空域编号已存在");
        }
        repository.insertVersion(new VersionRow(versionId, airspaceId, 1, request.kindCode(), request.minAltitudeM(), request.maxAltitudeM(),
                request.altitudeDatum(), request.validFrom(), request.validTo(), request.changeReason(), now), request.boundaryEwkt());
        AuthUser actor = AuthContext.require();
        repository.insertOrigin(UUID.randomUUID().toString(), versionId, "MANUAL", actor.userId(), null, null, now);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "airspace_created", OBJECT_TYPE, airspaceId,
                "airspace_no=" + request.airspaceNo() + "; kind_code=" + request.kindCode(), "SUCCESS", "", "");
        return new CreatedAirspaceDto(airspaceId, versionId, 1, 0);
    }

    @Transactional
    public CreatedVersionDto addVersion(String airspaceId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        VersionRequest request = parseVersion(rawBody);
        String id = identifier(airspaceId);
        AirspaceHead head = repository.lockAirspace(id, scopeUser(decision));
        if (head == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "空域不存在或不可见");
        idempotency.claim(idempotencyKey, framed("airspace-version") + framed(id) + framed(request.kindCode())
                + framed(Long.toString(request.validFrom().toEpochMilli())) + framed(request.changeReason())
                + framed(Long.toString(request.expectedVersion())));
        if (head.version() != request.expectedVersion()) throw versionConflict();

        VersionRow open = repository.findOpenVersion(id, request.validFrom());
        VersionRow latest = repository.findLatestVersion(id);
        if (latest == null) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "空域尚无版本，无法追加");
        // 新版本必须晚于上一版生效时刻：否则两版的生效区间会重叠或倒挂，历史上"某一时刻生效的是哪一版"就不唯一了。
        if (!request.validFrom().isAfter(latest.validFrom())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_OVERLAP", "新版本生效时间必须晚于上一版本");
        }
        Instant now = clock.now();
        String versionId = UUID.randomUUID().toString();
        String supersededId = null;
        Long supersededValidTo = null;
        if (open != null && open.validTo() == null) {
            if (repository.closeVersion(open.airspaceVersionId(), request.validFrom()) != 1) throw versionConflict();
            supersededId = open.airspaceVersionId();
            supersededValidTo = request.validFrom().toEpochMilli();
        }
        if (repository.bumpAirspaceVersion(id, request.expectedVersion(), now) != 1) throw versionConflict();
        repository.insertVersion(new VersionRow(versionId, id, latest.versionNo() + 1, request.kindCode(), request.minAltitudeM(),
                request.maxAltitudeM(), request.altitudeDatum(), request.validFrom(), request.validTo(), request.changeReason(), now),
                request.boundaryEwkt());
        AuthUser actor = AuthContext.require();
        repository.insertOrigin(UUID.randomUUID().toString(), versionId, "MANUAL", actor.userId(), null, supersededId, now);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "airspace_version_created", OBJECT_TYPE, id,
                "version_no=" + (latest.versionNo() + 1) + "; expected_version=" + request.expectedVersion(), "SUCCESS", "", "");
        return new CreatedVersionDto(id, versionId, latest.versionNo() + 1, head.version() + 1, supersededId, supersededValidTo);
    }

    AccessDecision requireManage() {
        // 读权限先于写权限：没有读权限的人不应该通过写接口的报错差异探测空域是否存在。
        AccessDecision decision = access.require(PermissionCode.AIRSPACE_READ);
        access.require(PermissionCode.AIRSPACE_MANAGE);
        return decision;
    }

    static String scopeUser(AccessDecision decision) {
        return decision.scopeMode() == ScopeMode.ASSIGNED ? decision.userId() : null;
    }

    private void requireScope(AccessDecision decision, String ownerOrgId, String districtId) {
        if (decision.scopeMode() != ScopeMode.ASSIGNED) return;
        if (!repository.scopeGranted(decision.userId(), ownerOrgId, districtId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "归属不在可见范围内");
        }
    }

    record CreateRequest(String airspaceNo, String name, String kindCode, String boundaryEwkt, BigDecimal minAltitudeM,
            BigDecimal maxAltitudeM, String altitudeDatum, Instant validFrom, Instant validTo, String changeReason,
            String ownerOrgId, String districtId) { }

    record VersionRequest(String kindCode, String boundaryEwkt, BigDecimal minAltitudeM, BigDecimal maxAltitudeM,
            String altitudeDatum, Instant validFrom, Instant validTo, String changeReason, long expectedVersion) { }

    private CreateRequest parseCreate(String rawBody) {
        JsonNode node = strictObject(rawBody, CREATE_FIELDS);
        String airspaceNo = requiredText(node, "airspace_no", NO_MAX);
        String name = requiredText(node, "name", NAME_MAX);
        String kindCode = requiredKind(node);
        String boundary = requiredBoundary(node);
        Altitude altitude = altitude(node);
        Instant validFrom = requiredTime(node, "valid_from");
        Instant validTo = optionalTime(node, "valid_to");
        requireValidity(validFrom, validTo);
        return new CreateRequest(airspaceNo, name, kindCode, boundary, altitude.min(), altitude.max(), altitude.datum(),
                validFrom, validTo, optionalText(node, "change_reason", REASON_MAX),
                requiredText(node, "owner_org_id", ID_MAX), requiredText(node, "district_id", ID_MAX));
    }

    private VersionRequest parseVersion(String rawBody) {
        JsonNode node = strictObject(rawBody, VERSION_FIELDS);
        String kindCode = requiredKind(node);
        String boundary = requiredBoundary(node);
        Altitude altitude = altitude(node);
        Instant validFrom = requiredTime(node, "valid_from");
        Instant validTo = optionalTime(node, "valid_to");
        requireValidity(validFrom, validTo);
        // 追加版本必须写变更原因：版本时间线是给后来人看的，"为什么改"不能只留在某个人的记忆里。
        String reason = requiredText(node, "change_reason", REASON_MAX);
        JsonNode expected = node.get("expected_version");
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0) {
            throw validation("expected_version 无效");
        }
        return new VersionRequest(kindCode, boundary, altitude.min(), altitude.max(), altitude.datum(), validFrom, validTo, reason, expected.longValue());
    }

    private record Altitude(BigDecimal min, BigDecimal max, String datum) { }

    /** 高度带三件套要么都给要么都不给：没有基准的高度数字不可比较，AGL 与 AMSL 永不互推。 */
    private Altitude altitude(JsonNode node) {
        BigDecimal min = decimal(node, "min_altitude_m"), max = decimal(node, "max_altitude_m");
        String datum = optionalText(node, "altitude_datum", 16);
        if (min == null && max == null && datum == null) return new Altitude(null, null, null);
        if (min == null || max == null || datum == null) throw validation("高度带必须同时给出下限、上限与基准");
        if (!"AGL".equals(datum) && !"AMSL".equals(datum)) throw validation("高度基准只能是 AGL 或 AMSL");
        if (min.compareTo(max) > 0) throw validation("高度下限不能大于上限");
        return new Altitude(min, max, datum);
    }

    private String requiredKind(JsonNode node) {
        String kindCode = requiredText(node, "kind_code", 32);
        if (!AirspaceKind.supported(kindCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "空域种类只能是 " + AirspaceKind.options());
        }
        return kindCode;
    }

    private String requiredBoundary(JsonNode node) {
        JsonNode boundary = node.get("boundary");
        if (boundary == null || boundary.isNull() || !boundary.isObject()) throw invalidGeoJson("边界必须是 GeoJSON 几何对象");
        // 复用导入解析器：包一层单要素 FeatureCollection，保证手工创建与文件导入用同一套几何校验。
        String collection = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":"
                + boundary + "}]}";
        ParseResult result = geoJson.parse(collection);
        if (result.fatalIssue() != null) throw invalidGeoJson("边界无法解析：" + result.fatalIssue());
        Feature feature = result.features().get(0);
        if (!feature.usable()) {
            String reason = feature.issues().isEmpty() ? "GEOMETRY_INVALID" : feature.issues().get(0).reasonCode();
            throw invalidGeoJson("边界几何无效：" + reason);
        }
        return feature.boundaryEwkt();
    }

    private static void requireValidity(Instant validFrom, Instant validTo) {
        if (validTo != null && !validFrom.isBefore(validTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VALIDITY", "失效时间必须晚于生效时间");
        }
    }

    private JsonNode strictObject(String rawBody, Set<String> allowed) {
        if (rawBody == null || rawBody.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(rawBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            node.fieldNames().forEachRemaining(name -> {
                if (!allowed.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            });
            return node;
        } catch (java.io.IOException ex) {
            throw invalidRequest();
        }
    }

    private static String requiredText(JsonNode node, String field, int max) {
        String value = optionalText(node, field, max);
        if (value == null) throw validation(field + " 必填");
        return value;
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw validation(field + " 必须是字符串");
        String text = value.textValue().trim();
        if (text.isEmpty()) return null;
        if (text.length() > max) throw validation(field + " 超出长度限制");
        return text;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw validation(field + " 必须是数值");
        return value.decimalValue();
    }

    private static Instant requiredTime(JsonNode node, String field) {
        Instant value = optionalTime(node, field);
        if (value == null) throw validation(field + " 必填");
        return value;
    }

    private static Instant optionalTime(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw validation(field + " 必须是毫秒时间戳");
        return Instant.ofEpochMilli(value.longValue());
    }

    static String identifier(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) throw validation("ID 格式无效");
        return id;
    }

    static String framed(String value) {
        String text = value == null ? "" : value;
        return text.getBytes(StandardCharsets.UTF_8).length + ":" + text;
    }

    static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "空域已被其他操作更新");
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体无效");
    }

    private static ApiException invalidGeoJson(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GEOJSON", message);
    }

    /** 供导入服务复用：解析后的要素落成空域版本时共用同一套写入顺序。 */
    List<String> supportedKinds() { return List.copyOf(AirspaceKind.CODES); }
}
