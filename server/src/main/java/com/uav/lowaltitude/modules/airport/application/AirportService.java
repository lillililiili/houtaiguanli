package com.uav.lowaltitude.modules.airport.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.AirportDetailDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.AirportDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.NotificationTargetDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.PageDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.ProcedureRouteDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.ProtectedTargetDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.RunwayDto;
import com.uav.lowaltitude.modules.airport.infrastructure.AirportRepository;
import com.uav.lowaltitude.modules.airport.infrastructure.AirportRepository.AirportRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 机场基础数据：读 airport:read，写 airport:manage，全部只增。
 * 写事务顺序与阶段 5/7/8 一致：鉴权 → 严格解析 body → claim 幂等键 → 唯一性检查 → 写 → 成功审计（同事务）。
 */
@Service
public class AirportService {
    static final String MODULE = "airport";
    private static final Set<String> AIRPORT_FIELDS = Set.of("icao_code", "name", "longitude", "latitude", "elevation_amsl_m", "owner_org_id", "district_id", "note");
    private static final Set<String> RUNWAY_FIELDS = Set.of("designator", "heading_deg", "length_m", "centerline");
    private static final Set<String> PROCEDURE_FIELDS = Set.of("kind", "name", "centerline", "protect_width_m", "min_altitude_m", "max_altitude_m", "altitude_datum");
    private static final Set<String> PROTECTED_FIELDS = Set.of("name", "kind", "longitude", "latitude", "radius_m");
    private static final Set<String> NOTIFICATION_FIELDS = Set.of("name", "role", "channel_kind");
    private static final Set<String> PROCEDURE_KINDS = Set.of("APPROACH", "DEPARTURE");
    private static final Set<String> CHANNEL_KINDS = Set.of("PHONE", "RADIO", "SYSTEM", "OTHER");
    private static final Set<String> DATUMS = Set.of("AGL", "AMSL");
    private static final Set<String> PAGE_ONLY = Set.of("page", "size");
    private static final int NAME_MAX = 128, CODE_MAX = 8, ID_MAX = 36, NOTE_MAX = 500, PAGE_DEFAULT = 20, PAGE_MAX = 100;

    private final AccessControlService access;
    private final AirportRepository repository;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public AirportService(AccessControlService access, AirportRepository repository, IdempotencyGuard idempotency,
            AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.repository = repository; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional(readOnly = true)
    public PageDto<AirportDto> list(MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.AIRPORT_READ);
        parameters.keySet().stream().filter(key -> !PAGE_ONLY.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        Page page = page(parameters);
        long total = repository.countAirports(decision);
        List<AirportDto> items = repository.listAirports(decision, page.offset(), page.size()).stream().map(AirportService::dto).toList();
        return new PageDto<>(items, page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public AirportDetailDto detail(String airportId) {
        AccessDecision decision = access.require(PermissionCode.AIRPORT_READ);
        AirportRow row = requireVisible(airportId, decision);
        return new AirportDetailDto(dto(row),
                repository.runways(row.airportId()).stream().map(r -> new RunwayDto(r.runwayId(), r.airportId(), r.designator(),
                        r.headingDeg(), r.lengthM(), millis(r.createdAt()))).toList(),
                repository.procedureRoutes(row.airportId()).stream().map(r -> new ProcedureRouteDto(r.routeId(), r.airportId(), r.kind(),
                        r.name(), r.protectWidthM(), r.minAltitudeM(), r.maxAltitudeM(), r.altitudeDatum(), millis(r.createdAt()))).toList(),
                repository.protectedTargets(row.airportId()).stream().map(r -> new ProtectedTargetDto(r.protectedTargetId(), r.airportId(),
                        r.name(), r.kind(), r.longitude(), r.latitude(), r.radiusM(), millis(r.createdAt()))).toList(),
                repository.notificationTargets(row.airportId()).stream().map(r -> new NotificationTargetDto(r.notificationTargetId(),
                        r.airportId(), r.name(), r.role(), r.channelKind(), r.enabled(), millis(r.createdAt()))).toList());
    }

    @Transactional
    public AirportDto create(String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        JsonNode body = object(rawBody, AIRPORT_FIELDS, Set.of("elevation_amsl_m", "note"));
        String icao = text(body, "icao_code", CODE_MAX);
        String name = text(body, "name", NAME_MAX);
        double longitude = coordinate(body, "longitude", -180, 180);
        double latitude = coordinate(body, "latitude", -90, 90);
        String ownerOrgId = text(body, "owner_org_id", ID_MAX), districtId = text(body, "district_id", ID_MAX);
        BigDecimal elevation = optionalNumber(body, "elevation_amsl_m");
        String note = optionalText(body, "note", NOTE_MAX);
        idempotency.claim(idempotencyKey, framed("airport-create") + framed(icao) + framed(name) + framed(ownerOrgId) + framed(districtId));
        if (repository.icaoExists(icao)) throw conflict("AIRPORT_EXISTS", "该 ICAO 代码的机场已存在");
        AuthUser actor = AuthContext.require();
        String airportId = UUID.randomUUID().toString();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        repository.insertAirport(airportId, icao, name, longitude, latitude, elevation, ownerOrgId, districtId, note, actor.userId(), at);
        record(actor, "airport_created", airportId, "icao_code=" + icao + "; name=" + name);
        AirportRow created = repository.findAirport(airportId, decision);
        // 建成后不可见说明写入的归属超出了操作者范围：整体回滚，不返回一条自己看不到的机场。
        if (created == null) throw new ApiException(HttpStatus.FORBIDDEN, "DATA_SCOPE_FORBIDDEN", "无权在该组织与区域创建机场");
        return dto(created);
    }

    @Transactional
    public RunwayDto addRunway(String airportId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        AirportRow airport = requireVisible(airportId, decision);
        JsonNode body = object(rawBody, RUNWAY_FIELDS, Set.of("length_m", "centerline"));
        String designator = text(body, "designator", 16);
        BigDecimal heading = number(body, "heading_deg");
        if (heading.compareTo(BigDecimal.ZERO) < 0 || heading.compareTo(new BigDecimal("360")) >= 0) throw invalid("heading_deg 参数无效");
        BigDecimal length = optionalNumber(body, "length_m");
        String centerline = optionalText(body, "centerline", 4000);
        idempotency.claim(idempotencyKey, framed("airport-runway") + framed(airport.airportId()) + framed(designator));
        if (repository.childExists("airport_runway", "designator", airport.airportId(), designator)) throw conflict("RUNWAY_EXISTS", "该跑道编号已存在");
        String runwayId = UUID.randomUUID().toString();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        repository.insertRunway(runwayId, airport.airportId(), designator, heading, length, centerline, at);
        record(AuthContext.require(), "airport_runway_created", airport.airportId(), "designator=" + designator);
        return new RunwayDto(runwayId, airport.airportId(), designator, heading, length, millis(at));
    }

    @Transactional
    public ProcedureRouteDto addProcedureRoute(String airportId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        AirportRow airport = requireVisible(airportId, decision);
        JsonNode body = object(rawBody, PROCEDURE_FIELDS, Set.of("min_altitude_m", "max_altitude_m", "altitude_datum"));
        String kind = enumerated(body, "kind", PROCEDURE_KINDS);
        String name = text(body, "name", NAME_MAX);
        String centerline = text(body, "centerline", 8000);
        BigDecimal width = number(body, "protect_width_m");
        if (width.compareTo(BigDecimal.ZERO) <= 0) throw invalid("protect_width_m 参数无效");
        BigDecimal min = optionalNumber(body, "min_altitude_m"), max = optionalNumber(body, "max_altitude_m");
        String datum = body.hasNonNull("altitude_datum") ? enumerated(body, "altitude_datum", DATUMS) : null;
        // 高度数值与基准必须成对：只有数值没有基准无法与目标高度比较（AGL/AMSL 不互比）。
        if ((min != null || max != null) && datum == null) throw invalid("高度值必须同时给出高度基准");
        idempotency.claim(idempotencyKey, framed("airport-procedure") + framed(airport.airportId()) + framed(name));
        if (repository.childExists("airport_procedure_route", "name", airport.airportId(), name)) throw conflict("PROCEDURE_EXISTS", "该程序名称已存在");
        String routeId = UUID.randomUUID().toString();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        repository.insertProcedureRoute(routeId, airport.airportId(), kind, name, centerline, width, min, max, datum, at);
        record(AuthContext.require(), "airport_procedure_route_created", airport.airportId(), "kind=" + kind + "; name=" + name);
        return new ProcedureRouteDto(routeId, airport.airportId(), kind, name, width, min, max, datum, millis(at));
    }

    @Transactional
    public ProtectedTargetDto addProtectedTarget(String airportId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        AirportRow airport = requireVisible(airportId, decision);
        JsonNode body = object(rawBody, PROTECTED_FIELDS, Set.of());
        String name = text(body, "name", NAME_MAX), kind = text(body, "kind", 32);
        double longitude = coordinate(body, "longitude", -180, 180), latitude = coordinate(body, "latitude", -90, 90);
        BigDecimal radius = number(body, "radius_m");
        if (radius.compareTo(BigDecimal.ZERO) <= 0) throw invalid("radius_m 参数无效");
        idempotency.claim(idempotencyKey, framed("airport-protected") + framed(airport.airportId()) + framed(name));
        if (repository.childExists("airport_protected_target", "name", airport.airportId(), name)) throw conflict("PROTECTED_TARGET_EXISTS", "该保护目标名称已存在");
        String id = UUID.randomUUID().toString();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        repository.insertProtectedTarget(id, airport.airportId(), name, kind, longitude, latitude, radius, at);
        record(AuthContext.require(), "airport_protected_target_created", airport.airportId(), "name=" + name);
        return new ProtectedTargetDto(id, airport.airportId(), name, kind, BigDecimal.valueOf(longitude), BigDecimal.valueOf(latitude), radius, millis(at));
    }

    @Transactional
    public NotificationTargetDto addNotificationTarget(String airportId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireManage();
        AirportRow airport = requireVisible(airportId, decision);
        JsonNode body = object(rawBody, NOTIFICATION_FIELDS, Set.of());
        String name = text(body, "name", NAME_MAX), role = text(body, "role", 64);
        String channelKind = enumerated(body, "channel_kind", CHANNEL_KINDS);
        idempotency.claim(idempotencyKey, framed("airport-notification") + framed(airport.airportId()) + framed(name));
        if (repository.childExists("airport_notification_target", "name", airport.airportId(), name)) throw conflict("NOTIFICATION_TARGET_EXISTS", "该通报对象已存在");
        String id = UUID.randomUUID().toString();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        repository.insertNotificationTarget(id, airport.airportId(), name, role, channelKind, at);
        record(AuthContext.require(), "airport_notification_target_created", airport.airportId(), "name=" + name + "; role=" + role);
        return new NotificationTargetDto(id, airport.airportId(), name, role, channelKind, true, millis(at));
    }

    // ---- 守卫与解析 ----

    private AccessDecision requireManage() {
        AccessDecision decision = access.require(PermissionCode.AIRPORT_READ);
        access.require(PermissionCode.AIRPORT_MANAGE);
        return decision;
    }

    private AirportRow requireVisible(String airportId, AccessDecision decision) {
        AirportRow row = repository.findAirport(id(airportId), decision);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "AIRPORT_NOT_FOUND", "机场不存在");
        return row;
    }

    private void record(AuthUser actor, String action, String objectId, String detail) {
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, action, "airport", objectId, detail, "SUCCESS", "", "");
    }

    private static AirportDto dto(AirportRow row) {
        return new AirportDto(row.airportId(), row.icaoCode(), row.name(), row.longitude(), row.latitude(), row.elevationAmslM(),
                row.ownerOrgId(), row.districtId(), row.ownerOrgName(), row.districtName(), row.enabled(), row.note(),
                millis(row.createdAt()), row.version());
    }

    private JsonNode object(String raw, Set<String> fields, Set<String> optional) {
        if (raw == null || raw.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!fields.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            }
            for (String field : fields) {
                if (!optional.contains(field) && !node.hasNonNull(field)) throw invalidRequest();
            }
            return node;
        } catch (IOException ex) {
            throw invalidRequest();
        }
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalidRequest();
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > max) throw invalid(field + " 参数无效");
        return text;
    }

    private static String optionalText(JsonNode node, String field, int max) {
        if (!node.hasNonNull(field)) return null;
        return text(node, field, max);
    }

    private static String enumerated(JsonNode node, String field, Set<String> allowed) {
        String value = text(node, field, 32);
        if (!allowed.contains(value)) throw invalid(field + " 参数无效");
        return value;
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw invalidRequest();
        return value.decimalValue();
    }

    private static BigDecimal optionalNumber(JsonNode node, String field) {
        return node.hasNonNull(field) ? number(node, field) : null;
    }

    private static double coordinate(JsonNode node, String field, double min, double max) {
        double value = number(node, field).doubleValue();
        if (value < min || value > max) throw invalid(field + " 参数无效");
        return value;
    }

    private static Page page(MultiValueMap<String, String> parameters) {
        int page = integer(parameters, "page", 1), size = integer(parameters, "size", PAGE_DEFAULT);
        if (page < 1 || size < 1 || size > PAGE_MAX) throw invalid("分页参数无效");
        try { return new Page(page, size, Math.multiplyExact(page - 1, size)); }
        catch (ArithmeticException ex) { throw invalid("分页参数无效"); }
    }

    private static int integer(MultiValueMap<String, String> parameters, String name, int fallback) {
        if (!parameters.containsKey(name)) return fallback;
        List<String> found = parameters.get(name);
        if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid("分页参数无效");
        try { return Integer.parseInt(found.get(0).trim()); }
        catch (NumberFormatException ex) { throw invalid("分页参数无效"); }
    }

    private static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }

    private static long millis(OffsetDateTime value) { return value.toInstant().toEpochMilli(); }
    private static String framed(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ":" + value; }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    private record Page(int page, int size, int offset) { }

    /** 供种子复用的 EWKT 构造，避免种子自己拼几何文本。 */
    public static List<String> supportedChannelKinds() { return List.copyOf(CHANNEL_KINDS); }
}
