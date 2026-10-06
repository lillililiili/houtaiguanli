package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.Message;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository.Row;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Local replay route input. It remains available for standalone route messages;
 * a plan message may also register its embedded centerline before plan creation.
 */
@Service
@org.springframework.context.annotation.Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
public class LocalRouteInputService {
    private static final Set<String> FIELDS = Set.of("message_id", "name", "valid_from", "valid_to", "geometry",
            "corridor_width_m", "min_altitude_m", "max_altitude_m", "altitude_datum", "owner_org_id", "district_id");
    private static final Pattern MESSAGE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final DeviceAccessPolicy interfaces;
    private final AccessControlService access;
    private final IdempotencyGuard idempotency;
    private final LocalInterfaceRepository messages;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;

    public LocalRouteInputService(DeviceAccessPolicy interfaces, AccessControlService access, IdempotencyGuard idempotency,
            LocalInterfaceRepository messages, JdbcTemplate jdbc, ObjectMapper json, AppClock clock, AuditService audit) {
        this.interfaces = interfaces;
        this.access = access;
        this.idempotency = idempotency;
        this.messages = messages;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public Message create(String raw, String idempotencyKey) {
        AuthUser actor = interfaces.requireInterfacesOperate();
        AccessDecision scope = access.require(PermissionCode.ROUTE_READ);
        messages.actorLock(actor.userId());
        ObjectNode body = object(raw);
        String messageId = text(body, "message_id", 64, true);
        if (!MESSAGE.matcher(messageId).matches()) throw bad("消息编号格式无效");
        Row previous = messages.existing(actor.userId(), "ROUTE", messageId);
        if (previous != null) {
            if (!tree(previous.payload()).equals(body)) throw conflict("同一消息编号的内容已变化");
            return dto(previous);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw bad("Idempotency-Key 必须为8至128个字符");
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) throw bad("Idempotency-Key 必须为8至128个字符");
        idempotency.claim(idempotencyKey, "local-route:" + body.toString());

        String name = text(body, "name", 128, true);
        String owner = text(body, "owner_org_id", 36, true);
        String district = text(body, "district_id", 36, true);
        if (!scopeAllowed(scope, actor.userId(), owner, district)) throw missing();
        long validFrom = time(body, "valid_from", true);
        Long validTo = body.hasNonNull("valid_to") ? time(body, "valid_to", true) : null;
        if (validTo != null && validFrom >= validTo) throw bad("航线有效期无效");
        if (validTo != null && validTo - validFrom > 7L * 86400000L) throw bad("航线有效期不能超过7天");
        BigDecimal width = decimal(body, "corridor_width_m");
        BigDecimal min = decimal(body, "min_altitude_m");
        BigDecimal max = decimal(body, "max_altitude_m");
        String datum = optionalText(body, "altitude_datum", 16);
        if ((min == null) != (max == null) || (min != null && (datum == null || !("AGL".equals(datum) || "AMSL".equals(datum)) || min.compareTo(max) > 0))) {
            throw bad("高度带必须同时给出有效的下限、上限和基准");
        }
        String wkt = lineString(body.get("geometry"));
        long now = clock.nowMillis();
        String routeId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String routeNo = messageId;
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,0)",
                routeId, routeNo, name, true, "replay", owner, district, at(now), at(now));
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,change_reason,created_at) VALUES(?,?,1,CAST(? AS GEOMETRY),?,?,?,?,?,?,?,?)",
                versionId, routeId, wkt, width, min, max, datum, at(validFrom), validTo == null ? null : at(validTo), "本地地图模拟批次", at(now));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route_id", routeId);
        result.put("route_version_id", versionId);
        result.put("route_no", routeNo);
        result.put("source_mode", "replay");
        Row saved = new Row(UUID.randomUUID().toString(), messageId, "ROUTE", "IN", routeId, actor.userId(), "ACCEPTED",
                body.toString(), encode(result), now, 0);
        messages.insert(saved);
        audit.record(actor.userId(), actor.account(), "local_interface_input", "local_interface", saved.id(),
                "接收模拟ROUTE; subject_id=" + routeId, null);
        return dto(saved);
    }

    private boolean scopeAllowed(AccessDecision scope, String user, String org, String district) {
        if (scope.scopeMode() != ScopeMode.ASSIGNED) return true;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user_data_scope s JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE WHERE s.user_id=? AND s.org_id=? AND s.district_id=?",
                Long.class, user, org, district);
        return count != null && count > 0;
    }

    private ObjectNode object(String raw) {
        try {
            JsonNode node = json.readTree(raw == null ? "" : raw);
            if (node == null || !node.isObject()) throw bad("航线报文必须是对象");
            Set<String> unknown = new HashSet<>();
            node.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) unknown.add(field); });
            if (!unknown.isEmpty()) throw bad("航线报文包含未知字段：" + unknown.iterator().next());
            return (ObjectNode) node;
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw bad("航线报文无法解析"); }
    }

    private String lineString(JsonNode geometry) {
        if (geometry == null || !geometry.isObject() || !"LineString".equals(geometry.path("type").asText())) throw bad("航线几何必须是 LineString");
        JsonNode coordinates = geometry.get("coordinates");
        if (coordinates == null || !coordinates.isArray() || coordinates.size() < 2) throw bad("航线至少需要两个坐标点");
        List<String> points = new ArrayList<>();
        for (JsonNode point : coordinates) {
            if (!point.isArray() || point.size() < 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) throw bad("航线坐标无效");
            double lon = point.get(0).asDouble(), lat = point.get(1).asDouble();
            if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90) throw bad("航线坐标超出范围");
            points.add(point.get(0).decimalValue().toPlainString() + " " + point.get(1).decimalValue().toPlainString());
        }
        return "SRID=4326;LINESTRING(" + String.join(",", points) + ")";
    }

    private String text(ObjectNode body, String key, int max, boolean required) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            if (required) throw bad(key + " 无效");
            return null;
        }
        return value.asText().trim();
    }

    private String optionalText(ObjectNode body, String key, int max) { return body.hasNonNull(key) ? text(body, key, max, true) : null; }

    private BigDecimal decimal(ObjectNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw bad(key + " 必须是数字");
        return value.decimalValue();
    }

    private long time(ObjectNode body, String key, boolean required) {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            if (required) throw bad(key + " 必须是正整数毫秒时间戳");
            return 0;
        }
        return value.longValue();
    }

    private JsonNode tree(String value) {
        try { return json.readTree(value); } catch (Exception ex) { throw new IllegalStateException("航线回放记录损坏", ex); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private Message dto(Row row) {
        return new Message(row.externalId(), row.kind(), row.direction(), row.subjectId(), row.state(), row.version(),
                row.createdAt(), tree(row.payload()), tree(row.result()));
    }

    private static Timestamp at(long millis) { return Timestamp.from(Instant.ofEpochMilli(millis)); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "SIMULATION_CONFLICT", message); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "归属不存在或不在当前权限范围"); }
}
