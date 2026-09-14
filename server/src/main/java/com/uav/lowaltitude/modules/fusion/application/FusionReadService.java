package com.uav.lowaltitude.modules.fusion.application;

import java.io.IOException;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.FusionStatusDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.LineageDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.LocationDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ObservationDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.PageDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.SourceStatusDto;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository.LineageRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationReadRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationReadRepository.ObservationQuery;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationReadRepository.ObservationRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationReadRepository.SourceStatusRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合读取：血缘（target:read + fusion:read）、原始观测（target:read）、来源在线状态（target:read）。
 * 目标可见性一律复用阶段 2 的精确范围谓词：越权目标按 404 处理，不区分“不存在”与“不可见”。
 */
@Service
public class FusionReadService {
    private static final Set<String> PAGE_ONLY = Set.of("page", "size");
    private static final Set<String> OBSERVATION_FILTERS = Set.of("source_code", "time_from", "time_to", "page", "size");
    private static final int PAGE_DEFAULT = 20, PAGE_MAX = 100, ID_MAX = 36, CODE_MAX = 64;

    private final AccessControlService access;
    private final TargetReadRepository targets;
    private final LineageRepository lineages;
    private final ObservationReadRepository observations;
    private final FusionConfigService config;
    private final AppClock clock;
    private final ObjectMapper json;

    public FusionReadService(AccessControlService access, TargetReadRepository targets, LineageRepository lineages,
            ObservationReadRepository observations, FusionConfigService config, AppClock clock, ObjectMapper json) {
        this.access = access; this.targets = targets; this.lineages = lineages; this.observations = observations;
        this.config = config; this.clock = clock; this.json = json;
    }

    @Transactional(readOnly = true)
    public PageDto<LineageDto> lineage(String targetId, MultiValueMap<String, String> parameters) {
        // 血缘暴露的是目标之间的关系，比目标本身更敏感：目标读权限之外再要求 fusion:read。
        AccessDecision decision = access.require(PermissionCode.TARGET_READ);
        access.require(PermissionCode.FUSION_READ);
        String id = id(targetId);
        Page page = page(parameters, PAGE_ONLY);
        if (targets.findTarget(id, decision) == null) throw notFound();
        long total = lineages.countLineage(id);
        List<LineageDto> items = lineages.listLineage(id, page.offset(), page.size()).stream().map(this::dto).toList();
        return new PageDto<>(items, page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public PageDto<ObservationDto> observations(String targetId, MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.TARGET_READ);
        String id = id(targetId);
        Page page = page(parameters, OBSERVATION_FILTERS);
        String sourceCode = optional(parameters, "source_code", CODE_MAX);
        OffsetDateTime[] range = timeRange(parameters);
        if (targets.findTarget(id, decision) == null) throw notFound();
        ObservationQuery query = new ObservationQuery(sourceCode, range[0], range[1]);
        long total = observations.countObservations(id, query);
        List<ObservationDto> items = observations.listObservations(id, query, page.offset(), page.size()).stream().map(FusionReadService::dto).toList();
        return new PageDto<>(items, page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public FusionStatusDto status() {
        access.require(PermissionCode.TARGET_READ);
        long now = clock.nowMillis();
        long shortLostMs = (long) config.params(null).number("identity", "short_lost_after_ms");
        List<SourceStatusDto> sources = new ArrayList<>();
        boolean anyOnline = false;
        for (SourceStatusRow row : observations.sourceStatuses()) {
            Long last = row.lastObservedAt() == null ? null : row.lastObservedAt().toInstant().toEpochMilli();
            boolean online = last != null && last >= now - shortLostMs;
            anyOnline |= online;
            sources.add(new SourceStatusDto(row.sourceCode(), row.sourceType(), row.schemaStatus(), last, online));
        }
        // 没有任何在线来源即“数据中断”：这是页面必须显式呈现的状态，不能用空列表默默表示正常。
        return new FusionStatusDto(List.copyOf(sources), !anyOnline, now);
    }

    private LineageDto dto(LineageRow row) {
        return new LineageDto(row.lineageId(), row.op(), row.occurredAt().toInstant().toEpochMilli(), row.survivorTargetId(), row.originTargetId(),
                strings(row.memberTargetIdsJson()), strings(row.sourceTargetIdsJson()), row.algoVersion(), row.configVersion(),
                row.operatorKind(), row.operatorId(), row.note());
    }

    private static ObservationDto dto(ObservationRow row) {
        return new ObservationDto(row.observationId(), row.sourceCode(), row.sourceType(), row.externalTargetId(),
                row.observedAt().toInstant().toEpochMilli(), row.receivedAt().toInstant().toEpochMilli(), row.longitude(), row.latitude(),
                row.positionAccuracyM(), row.altitudeAmslM(), row.heightAglM(), row.speedMps(), row.headingDeg(),
                row.classCode(), row.classConfidence(), row.identityClue(), row.sourceMode(),
                row.pilotLongitude() == null || row.pilotLatitude() == null ? null
                        : new LocationDto(row.pilotLongitude(), row.pilotLatitude(), "WGS84"),
                row.classSource(), row.bearingDeg(), row.identityConfidence(), row.deviceId());
    }

    private List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            JsonNode node = json.readTree(value);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) if (item.isTextual()) out.add(item.textValue());
            return List.copyOf(out);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        }
    }

    static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }

    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "目标不存在"); }

    private static Page page(MultiValueMap<String, String> parameters, Set<String> allowed) {
        parameters.keySet().stream().filter(key -> !allowed.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        int page = integer(parameters, "page", 1), size = integer(parameters, "size", PAGE_DEFAULT);
        if (page < 1 || size < 1 || size > PAGE_MAX) throw invalid("分页参数无效");
        try { return new Page(page, size, Math.multiplyExact(page - 1, size)); }
        catch (ArithmeticException ex) { throw invalid("分页参数无效"); }
    }

    private static int integer(MultiValueMap<String, String> parameters, String name, int fallback) {
        if (!parameters.containsKey(name)) return fallback;
        try { return Integer.parseInt(single(parameters, name)); }
        catch (NumberFormatException ex) { throw invalid("分页参数无效"); }
    }

    private static String optional(MultiValueMap<String, String> parameters, String name, int max) {
        if (!parameters.containsKey(name)) return null;
        String value = single(parameters, name);
        if (value.length() > max) throw invalid(name + " 参数无效");
        return value;
    }

    private static OffsetDateTime[] timeRange(MultiValueMap<String, String> parameters) {
        boolean hasFrom = parameters.containsKey("time_from"), hasTo = parameters.containsKey("time_to");
        if (!hasFrom && !hasTo) return new OffsetDateTime[] { null, null };
        if (hasFrom != hasTo) throw badTime();
        try {
            long from = Long.parseLong(single(parameters, "time_from")), to = Long.parseLong(single(parameters, "time_to"));
            if (from > to) throw badTime();
            return new OffsetDateTime[] { Instant.ofEpochMilli(from).atOffset(ZoneOffset.UTC), Instant.ofEpochMilli(to).atOffset(ZoneOffset.UTC) };
        } catch (NumberFormatException ex) {
            throw badTime();
        }
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> found = parameters.get(name);
        if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效");
        return found.get(0).trim();
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }

    record Page(int page, int size, int offset) { }
}
