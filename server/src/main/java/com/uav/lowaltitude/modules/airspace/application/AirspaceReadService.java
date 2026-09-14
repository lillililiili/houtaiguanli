package com.uav.lowaltitude.modules.airspace.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceSummaryDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceVersionDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.FieldIssueDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.PageDto;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository.AirspaceQuery;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository.AirspaceRow;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository.AirspaceVersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class AirspaceReadService {

    private final AccessControlService accessControl;
    private final AirspaceReadRepository repository;
    private final AirspaceConflictService conflictService;
    private final ObjectMapper objectMapper;
    private final AppClock clock;

    public AirspaceReadService(AccessControlService accessControl, AirspaceReadRepository repository,
            AirspaceConflictService conflictService, ObjectMapper objectMapper, AppClock clock) {
        this.accessControl = accessControl;
        this.repository = repository;
        this.conflictService = conflictService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageDto<AirspaceSummaryDto> airspaces(MultiValueMap<String, String> parameters) {
        // 动作权限必须先判定，避免用错误参数或是否存在的对象泄露空域访问能力。
        AccessDecision access = accessControl.require(PermissionCode.AIRSPACE_READ);
        RequestValues values = new RequestValues(parameters);
        Pagination page = values.pagination();
        AirspaceQuery query = new AirspaceQuery(
                values.optional("kind_code", 32), values.optional("source_mode", 8),
                values.optional("owner_org_id", 36), values.optional("district_id", 36),
                values.optionalInstant("valid_at"), values.optional("keyword", 128));
        long total = repository.countAirspaces(query, access);
        List<AirspaceSummaryDto> items = repository.listAirspaces(query, access, page.offset(), page.size()).stream()
                .map(this::summary)
                .toList();
        return new PageDto<>(items, page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public AirspaceDtos.AirspaceDetailDto airspace(String airspaceId) {
        AccessDecision access = accessControl.require(PermissionCode.AIRSPACE_READ);
        AirspaceRow row = repository.findAirspace(pathId(airspaceId), access);
        // 将越权与不存在统一成 404，避免用 ID 探测其他组织/区域的空域目录。
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "AIRSPACE_NOT_FOUND", "空域不存在");
        // 以统一业务时钟读取左闭右开有效版本；0 个版本是事实，不用“最新版本”替代。
        List<AirspaceVersionRow> current = repository.effectiveVersions(row.airspaceId(), clock.now().atOffset(ZoneOffset.UTC), access);
        if (current.size() > 1) throw new ApiException(HttpStatus.CONFLICT, "VERSION_AMBIGUOUS", "空域有效版本重叠");
        return new AirspaceDtos.AirspaceDetailDto(row.airspaceId(), row.airspaceNo(), row.name(), row.sourceMode(), row.ownerOrgId(), row.districtId(), millis(row.createdAt()), millis(row.updatedAt()), row.version(), current.isEmpty() ? null : version(current.get(0)), row.ownerOrgName(), row.districtName());
    }

    @Transactional(readOnly = true)
    public PageDto<AirspaceVersionDto> versions(String airspaceId, MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.AIRSPACE_READ);
        String id = pathId(airspaceId);
        if (repository.findAirspace(id, access) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AIRSPACE_NOT_FOUND", "空域不存在");
        }
        parameters.keySet().stream().filter(key -> !Set.of("page", "size").contains(key)).findFirst()
                .ifPresent(key -> { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", key + " 参数无效"); });
        Pagination page = new RequestValues(parameters).pagination();
        long total = repository.countVersions(id, access);
        return new PageDto<>(repository.listVersions(id, access, page.offset(), page.size()).stream()
                .map(this::version).toList(), page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public AirspaceVersionDto version(String airspaceVersionId) {
        AccessDecision access = accessControl.require(PermissionCode.AIRSPACE_READ);
        AirspaceVersionRow row = repository.findVersion(pathId(airspaceVersionId), access);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "AIRSPACE_VERSION_NOT_FOUND", "空域版本不存在");
        return version(row);
    }

    public List<AirspaceDtos.AirspaceConflictDto> conflicts(String planId) {
        return conflictService.conflicts(planId);
    }

    private AirspaceSummaryDto summary(AirspaceRow row) {
        return new AirspaceSummaryDto(row.airspaceId(), row.airspaceNo(), row.name(), row.sourceMode(),
                row.ownerOrgId(), row.districtId(), millis(row.createdAt()), millis(row.updatedAt()), row.version(),
                row.ownerOrgName(), row.districtName());
    }

    private AirspaceVersionDto version(AirspaceVersionRow row) {
        AirspaceDtos.GeoJsonMultiPolygonDto boundary = parseBoundary(row);
        // H2 和任何未通过 WGS-84 结构校验的几何都不能让前端绘制默认区域。
        List<FieldIssueDto> issues = boundary == null ? List.of(new FieldIssueDto("boundary", "GEOMETRY_UNTRUSTED")) : List.of();
        return new AirspaceVersionDto(row.airspaceVersionId(), row.airspaceId(), row.versionNo(), row.kindCode(), boundary,
                row.minAltitudeM(), row.maxAltitudeM(), row.altitudeDatum(), millis(row.validFrom()),
                row.validTo() == null ? null : millis(row.validTo()), row.changeReason(), issues,
                millis(row.createdAt()));
    }

    private AirspaceDtos.GeoJsonMultiPolygonDto parseBoundary(AirspaceVersionRow row) {
        if (!row.postgis() || row.boundaryGeoJson() == null) return null;
        try {
            JsonNode root = objectMapper.readTree(row.boundaryGeoJson());
            if (!"MultiPolygon".equals(root.path("type").asText()) || !root.path("coordinates").isArray()) return null;
            List<List<List<List<BigDecimal>>>> polygons = new ArrayList<>();
            for (JsonNode polygon : root.path("coordinates")) {
                List<List<List<BigDecimal>>> rings = new ArrayList<>();
                for (JsonNode ring : polygon) {
                    List<List<BigDecimal>> points = new ArrayList<>();
                    for (JsonNode point : ring) {
                        if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) return null;
                        BigDecimal lon = point.get(0).decimalValue();
                        BigDecimal lat = point.get(1).decimalValue();
                        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0
                                || lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) return null;
                        points.add(List.of(lon, lat));
                    }
                    if (points.size() < 4) return null;
                    rings.add(List.copyOf(points));
                }
                if (rings.isEmpty()) return null;
                polygons.add(List.copyOf(rings));
            }
            return polygons.isEmpty() ? null : new AirspaceDtos.GeoJsonMultiPolygonDto("MultiPolygon", List.copyOf(polygons), "WGS84");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long millis(OffsetDateTime value) {
        if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        return value.toInstant().toEpochMilli();
    }

    private static String pathId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        }
        return normalized;
    }

    private static final class RequestValues {
        private static final Set<String> ALLOWED = Set.of(
                "page", "size", "kind_code", "source_mode", "owner_org_id", "district_id", "valid_at", "keyword");
        private final MultiValueMap<String, String> parameters;

        private RequestValues(MultiValueMap<String, String> parameters) {
            this.parameters = parameters;
            parameters.keySet().stream().filter(name -> !ALLOWED.contains(name)).findFirst()
                    .ifPresent(RequestValues::throwUnknown);
        }

        private Pagination pagination() {
            int page = integer("page", 1);
            int size = integer("size", 20);
            if (page < 1 || size < 1 || size > 100) throw invalidPage();
            return new Pagination(page, size);
        }

        private int integer(String name, int defaultValue) {
            if (!parameters.containsKey(name)) return defaultValue;
            List<String> values = parameters.get(name);
            if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
                throw invalidPage();
            }
            try {
                return Integer.parseInt(values.get(0));
            } catch (NumberFormatException ignored) {
                throw invalidPage();
            }
        }

        private String optional(String name, int maxLength) {
            if (!parameters.containsKey(name)) return null;
            List<String> values = parameters.get(name);
            if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isBlank()
                    || values.get(0).length() > maxLength) {
                throw validation(name);
            }
            return values.get(0);
        }

        private OffsetDateTime optionalInstant(String name) {
            if (!parameters.containsKey(name)) return null;
            List<String> values = parameters.get(name);
            if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
                throw invalidTime();
            }
            try {
                return Instant.ofEpochMilli(Long.parseLong(values.get(0))).atOffset(ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                throw invalidTime();
            }
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

        private static void throwUnknown(String name) {
            throw validation(name);
        }
    }

    private record Pagination(int page, int size) {
        private int offset() {
            try {
                return Math.multiplyExact(page - 1, size);
            } catch (ArithmeticException ignored) {
                throw RequestValues.invalidPage();
            }
        }
    }

}
