package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.flight.api.FlightDtos.FieldIssueDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.FlightPlanDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.GeoJsonLineStringDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.PageDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RouteDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RouteReferenceDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RouteVersionDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.SourceDto;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanQuery;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.RouteQuery;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.RouteRow;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.RouteVersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class FlightReadService {

    private static final Set<String> PLAN_PARAMETERS = Set.of("page", "size", "status_code", "source_code",
            "route_id", "uav_sn", "owner_org_id", "district_id", "window_from", "window_to", "keyword");
    private static final Set<String> ROUTE_PARAMETERS = Set.of("page", "size", "enabled", "source_mode",
            "owner_org_id", "district_id", "keyword");

    private final AccessControlService accessControl;
    private final FlightReadRepository repository;

    public FlightReadService(AccessControlService accessControl, FlightReadRepository repository) {
        this.accessControl = accessControl;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageDto<FlightPlanDto> flightPlans(MultiValueMap<String, String> parameters) {
        // 先拒绝无动作权限请求，避免参数校验差异成为枚举受保护计划的侧信道。
        AccessDecision access = accessControl.require(PermissionCode.FLIGHT_READ);
        RequestValues request = new RequestValues(parameters, PLAN_PARAMETERS);
        Pagination page = request.pagination();
        TimeRange window = request.timeRange("window_from", "window_to");
        PlanQuery query = new PlanQuery(request.optional("status_code", 32), request.optional("source_code", 64),
                request.optional("route_id", 36), request.optional("uav_sn", 128), request.optional("owner_org_id", 36),
                request.optional("district_id", 36), window.from(), window.to(), request.optional("keyword", 128));
        long total = repository.countPlans(query, access);
        return new PageDto<>(repository.listPlans(query, access, page.offset(), page.size()).stream().map(this::plan).toList(),
                page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public FlightPlanDto flightPlan(String planId) {
        AccessDecision access = accessControl.require(PermissionCode.FLIGHT_READ);
        PlanRow row = repository.findPlan(pathId(planId), access);
        if (row == null) throw notFound("FLIGHT_PLAN_NOT_FOUND", "飞行计划不存在");
        return plan(row);
    }

    @Transactional(readOnly = true)
    public PageDto<RouteDto> routes(MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.ROUTE_READ);
        RequestValues request = new RequestValues(parameters, ROUTE_PARAMETERS);
        Pagination page = request.pagination();
        RouteQuery query = new RouteQuery(request.bool("enabled"), request.optional("source_mode", 8),
                request.optional("owner_org_id", 36), request.optional("district_id", 36), request.optional("keyword", 128));
        long total = repository.countRoutes(query, access);
        return new PageDto<>(repository.listRoutes(query, access, page.offset(), page.size()).stream().map(this::route).toList(),
                page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public RouteDto route(String routeId) {
        AccessDecision access = accessControl.require(PermissionCode.ROUTE_READ);
        RouteRow row = repository.findRoute(pathId(routeId), access);
        if (row == null) throw notFound("ROUTE_NOT_FOUND", "航线不存在");
        return route(row);
    }

    @Transactional(readOnly = true)
    public PageDto<RouteVersionDto> routeVersions(String routeId, MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.ROUTE_READ);
        String id = pathId(routeId);
        RequestValues request = new RequestValues(parameters, Set.of("page", "size"));
        Pagination page = request.pagination();
        if (repository.findRoute(id, access) == null) throw notFound("ROUTE_NOT_FOUND", "航线不存在");
        long total = repository.countRouteVersions(id, access);
        return new PageDto<>(repository.listRouteVersions(id, access, page.offset(), page.size()).stream()
                .map(this::routeVersion).toList(), page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public RouteVersionDto routeVersion(String routeVersionId) {
        AccessDecision access = accessControl.require(PermissionCode.ROUTE_READ);
        RouteVersionRow row = repository.findRouteVersion(pathId(routeVersionId), access);
        if (row == null) throw notFound("ROUTE_VERSION_NOT_FOUND", "航线版本不存在");
        return routeVersion(row);
    }

    private FlightPlanDto plan(PlanRow row) {
        List<FieldIssueDto> issues = new ArrayList<>();
        if (row.startAt() == null) issues.add(new FieldIssueDto("start_at", "TIME_UNTRUSTED"));
        if (row.endAt() == null) issues.add(new FieldIssueDto("end_at", "TIME_UNTRUSTED"));
        return new FlightPlanDto(row.planId(), row.planNo(), row.statusCode(), source(row.sourceId(), row.sourceCode(), row.sourceMode(), row.sourceName()),
                row.sourceMode(), row.uavSn(), millis(row.startAt()), millis(row.endAt()), row.ownerOrgId(), row.districtId(),
                new RouteReferenceDto(row.routeVersionId(), row.routeId(), row.routeNo(), row.routeName(), row.versionNo(), row.maxAltitudeM()),
                List.copyOf(issues), requiredMillis(row.createdAt()), requiredMillis(row.updatedAt()), row.version(),
                row.ownerOrgName(), row.districtName());
    }

    private RouteDto route(RouteRow row) {
        return new RouteDto(row.routeId(), row.routeNo(), row.name(), row.enabled(),
                source(row.sourceId(), row.sourceCode(), row.sourceMode(), row.sourceName()), row.sourceMode(), row.ownerOrgId(), row.districtId(),
                requiredMillis(row.createdAt()), requiredMillis(row.updatedAt()), row.version(), row.ownerOrgName(), row.districtName());
    }

    private RouteVersionDto routeVersion(RouteVersionRow row) {
        GeoJsonLineStringDto centerline = lineString(row.centerlineText());
        List<FieldIssueDto> issues = centerline == null
                ? List.of(new FieldIssueDto("centerline", "GEOMETRY_UNTRUSTED")) : List.of();
        // 坐标不可验证时不伪造默认线；调用方据此显示“不可绘制”，而不是画到 (0,0)。
        return new RouteVersionDto(row.routeVersionId(), row.routeId(), row.versionNo(), centerline, row.corridorWidthM(),
                row.minAltitudeM(), row.maxAltitudeM(), row.altitudeDatum(), requiredMillis(row.validFrom()), millis(row.validTo()),
                row.changeReason(), issues, requiredMillis(row.createdAt()));
    }

    private static SourceDto source(String sourceId, String sourceCode, String sourceMode, String sourceName) {
        return sourceId == null ? null : new SourceDto(sourceId, sourceCode, sourceMode, sourceName);
    }

    private static GeoJsonLineStringDto lineString(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        int line = normalized.toUpperCase().indexOf("LINESTRING");
        int open = normalized.indexOf('(', line);
        int close = normalized.lastIndexOf(')');
        if (line < 0 || open < 0 || close <= open) return null;
        List<List<BigDecimal>> coordinates = new ArrayList<>();
        try {
            for (String point : normalized.substring(open + 1, close).split(",")) {
                String[] pair = point.trim().split("\\s+");
                if (pair.length != 2) return null;
                BigDecimal longitude = new BigDecimal(pair[0]);
                BigDecimal latitude = new BigDecimal(pair[1]);
                if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                        || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) return null;
                coordinates.add(List.of(longitude, latitude));
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return coordinates.size() < 2 ? null : new GeoJsonLineStringDto("LineString", List.copyOf(coordinates), "WGS84");
    }

    private static String pathId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        }
        return normalized;
    }

    private static long requiredMillis(OffsetDateTime value) {
        if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        return value.toInstant().toEpochMilli();
    }

    private static Long millis(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toEpochMilli();
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static final class RequestValues {
        private final MultiValueMap<String, String> values;
        private final Set<String> accepted;

        private RequestValues(MultiValueMap<String, String> values, Set<String> accepted) {
            this.values = values;
            this.accepted = accepted;
            for (String key : values.keySet()) if (!accepted.contains(key)) throw validation(key);
        }

        private Pagination pagination() {
            int page = integer("page", 1);
            int size = integer("size", 20);
            if (page < 1 || size < 1 || size > 100) throw invalidPage();
            return new Pagination(page, size);
        }

        private int integer(String name, int defaultValue) {
            if (!values.containsKey(name)) return defaultValue;
            String value = scalar(name, invalidPage());
            try { return Integer.parseInt(value); } catch (NumberFormatException ex) { throw invalidPage(); }
        }

        private Boolean bool(String name) {
            if (!values.containsKey(name)) return null;
            String value = scalar(name, validation(name));
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw validation(name);
        }

        private String optional(String name, int maximumLength) {
            if (!values.containsKey(name)) return null;
            String value = scalar(name, validation(name));
            if (value.isBlank() || value.length() > maximumLength) throw validation(name);
            return value;
        }

        private TimeRange timeRange(String fromName, String toName) {
            if (!values.containsKey(fromName) && !values.containsKey(toName)) return new TimeRange(null, null);
            if (!values.containsKey(fromName) || !values.containsKey(toName)) throw invalidTime();
            try {
                long from = Long.parseLong(scalar(fromName, invalidTime()));
                long to = Long.parseLong(scalar(toName, invalidTime()));
                if (from > to) throw invalidTime();
                return new TimeRange(Instant.ofEpochMilli(from).atOffset(ZoneOffset.UTC), Instant.ofEpochMilli(to).atOffset(ZoneOffset.UTC));
            } catch (NumberFormatException ex) {
                throw invalidTime();
            }
        }

        private String scalar(String name, ApiException error) {
            List<String> found = values.get(name);
            if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw error;
            return found.get(0);
        }

        private static ApiException invalidPage() {
            return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效");
        }

        private static ApiException invalidTime() {
            return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效");
        }

        private static ApiException validation(String name) {
            return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", name + " 参数无效");
        }
    }

    private record Pagination(int page, int size) {
        private int offset() {
            try { return Math.multiplyExact(page - 1, size); }
            catch (ArithmeticException ex) { throw RequestValues.invalidPage(); }
        }
    }

    private record TimeRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
