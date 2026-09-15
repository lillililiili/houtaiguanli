package com.uav.lowaltitude.modules.target.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.DegradationRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.SelectionRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository.AliasRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository.LineageRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository.TrackStatusRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.target.api.TargetDtos.AttributeSelectionDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.ContributionDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.DegradationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.FieldIssueDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LineageSummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackStatusDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.PageDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.RiskSummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LegalitySummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.DisposalSummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetDetailDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSourceLinkDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetStateDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackPointDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackSummaryDto;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.Coordinate;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.PointRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.SourceLinkRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetQuery;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetSummariesRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.RiskSummaryRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.LegalitySummaryRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.DisposalSummaryRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.BearingRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TimeQuery;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TrackQuery;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TrackRow;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class TargetReadService {

    private static final Set<String> ISSUE_REASONS = Set.of(
            "NOT_REPORTED", "INVALID_VALUE", "REFERENCE_UNKNOWN", "TIME_UNTRUSTED",
            "NOT_APPLICABLE", "UNSUPPORTED");
    private static final Set<String> ISSUE_FIELDS = Set.of(
            "location", "altitude_amsl_m", "height_agl_m", "speed_mps", "heading_deg",
            "classification_confidence", "fusion_confidence");

    /** 阶段 8：融合层轨迹默认与原始层一起返回；点默认只给实测与桥接，PRED 需显式请求。 */
    private static final Set<String> LAYERS = Set.of("RAW", "FUSED");
    private static final Set<String> POINT_KINDS = Set.of("MEAS", "BRIDGE", "PRED");
    private static final List<String> DEFAULT_POINT_KINDS = List.of("MEAS", "BRIDGE");

    private final AccessControlService accessControl;
    private final TargetReadRepository repository;
    private final LineageRepository lineages;
    private final DegradationRepository degradations;
    private final FusedTrackRepository fusedTracks;
    private final ObjectMapper objectMapper;

    public TargetReadService(
            AccessControlService accessControl,
            TargetReadRepository repository,
            LineageRepository lineages,
            DegradationRepository degradations,
            FusedTrackRepository fusedTracks,
            ObjectMapper objectMapper) {
        this.accessControl = accessControl;
        this.repository = repository;
        this.lineages = lineages;
        this.degradations = degradations;
        this.fusedTracks = fusedTracks;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageDto<TargetSummaryDto> targets(MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.TARGET_READ);
        RequestValues request = new RequestValues(parameters);
        Pagination page = request.pagination();
        TimeRange seen = request.timeRange("seen_from", "seen_to");
        TargetQuery query = new TargetQuery(
                request.optional("source_code", 64),
                request.optional("device_id", 36),
                request.optional("object_type_code", 32),
                seen.from, seen.to,
                request.optional("owner_org_id", 36),
                request.optional("district_id", 36),
                request.flag("include_merged"));
        long total = repository.countTargets(query, access);
        List<TargetRow> rows = repository.listTargets(query, access, page.offset(), page.size);
        // 三摘要与方位按**整页**一次取回（决策 15-4）：逐条查会变成 N+1，而列表最大 100 条。
        Map<String, TargetSummariesRow> summaries = repository.summaries(rows.stream().map(TargetRow::targetId).toList());
        List<TargetSummaryDto> items = rows.stream()
                .map(row -> summary(row, summaries.get(row.targetId())))
                .toList();
        return new PageDto<>(items, page.page, page.size, total);
    }

    @Transactional(readOnly = true)
    public TargetDetailDto target(String targetId) {
        AccessDecision access = accessControl.require(PermissionCode.TARGET_READ);
        String id = pathId(targetId);
        TargetRow row = repository.findTarget(id, access);
        if (row == null) throw notFound("TARGET_NOT_FOUND", "目标不存在");
        List<TargetSourceLinkDto> links = repository.sourceLinks(id, access).stream().map(this::sourceLink).toList();
        // 阶段 8 追加字段：引擎未接管的目标这些行不存在，全部返回 null 而不是零值。
        TrackStatusRow status = lineages.findTrackStatus(id);
        DegradationRow degradation = degradations.findDegradation(id);
        SelectionRow selection = degradations.findSelection(id);
        // 详情与列表走同一套取数（决策 15-4）：同一张悬浮卡在两处都要能画出来，
        // 两条路各写一份就迟早会长出差异，而那种差异只有对着页面看才发现得了。
        TargetSummariesRow summaries = repository.summaries(List.of(id)).get(id);
        return new TargetDetailDto(
                row.targetId(), row.targetNo(), millis(row.firstSeenAt()), millis(row.lastSeenAt()),
                row.objectTypeCode(), row.subtype(), row.uavSn(), row.sourceMode(), row.ownerOrgId(),
                row.districtId(), state(row, summaries), links, requiredMillis(row.createdAt()),
                requiredMillis(row.updatedAt()), row.ownerOrgName(), row.districtName(),
                status == null ? null : new TrackStatusDto(status.status(), requiredMillis(status.since())),
                degradationDto(degradation), selectionDto(selection), lineageSummary(id),
                allowedActions(id, status, links.size()), row.version(),
                riskSummary(summaries), legalitySummary(summaries), disposalSummary(summaries));
    }

    @Transactional(readOnly = true)
    public PageDto<TrackSummaryDto> tracks(String targetId, MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.TARGET_READ);
        String id = pathId(targetId);
        RequestValues request = new RequestValues(parameters);
        Pagination page = request.pagination();
        TimeRange started = request.timeRange("started_from", "started_to");
        TrackQuery query = new TrackQuery(started.from, started.to,
                request.optional("source_code", 64), request.optional("device_id", 36), request.enumerated("layer", LAYERS));
        if (repository.findTarget(id, access) == null) throw notFound("TARGET_NOT_FOUND", "目标不存在");
        long total = repository.countTracks(id, query, access);
        List<TrackSummaryDto> items = repository.listTracks(id, query, access, page.offset(), page.size).stream()
                .map(this::track)
                .toList();
        return new PageDto<>(items, page.page, page.size, total);
    }

    @Transactional(readOnly = true)
    public PageDto<TrackPointDto> points(String trackId, MultiValueMap<String, String> parameters) {
        AccessDecision access = accessControl.require(PermissionCode.TARGET_READ);
        String id = pathId(trackId);
        RequestValues request = new RequestValues(parameters);
        Pagination page = request.pagination();
        TimeRange time = request.timeRange("time_from", "time_to");
        TimeQuery query = new TimeQuery(time.from, time.to, request.kinds());
        if (!repository.accessibleValidTrack(id, access)) throw notFound("TRACK_NOT_FOUND", "轨迹不存在");
        long total = repository.countPoints(id, query, access);
        List<TrackPointDto> items = repository.listPoints(id, query, access, page.offset(), page.size).stream()
                .map(this::point)
                .toList();
        return new PageDto<>(items, page.page, page.size, total);
    }

    private TargetSummaryDto summary(TargetRow row, TargetSummariesRow summaries) {
        return new TargetSummaryDto(
                row.targetId(), row.targetNo(), millis(row.firstSeenAt()), millis(row.lastSeenAt()),
                row.objectTypeCode(), row.subtype(), row.uavSn(), row.sourceMode(), row.ownerOrgId(),
                row.districtId(), state(row, summaries), row.ownerOrgName(), row.districtName(),
                riskSummary(summaries), legalitySummary(summaries), disposalSummary(summaries));
    }

    private TargetStateDto state(TargetRow row, TargetSummariesRow summaries) {
        if (row.stateObservedAt() == null) return null;
        // 方位键只在目标**自身**当前没有位置时给（决策 15-15）：
        // 有位置就画点，再给方位线会让同一个目标在图上同时出现一个点和一条方向线，读图的人不知道信哪个。
        // 只看 location，**不看 pilot_location**：只测到飞手、目标本身未定位，正是需要方位线的场景。
        BearingRow bearing = summaries == null || row.location() != null ? null : summaries.bearing();
        return new TargetStateDto(
                requiredMillis(row.stateObservedAt()), requiredMillis(row.stateReceivedAt()), issues(row),
                location(row.location()), row.altitudeAmslM(), row.heightAglM(), row.speedMps(), row.headingDeg(),
                row.classificationConfidence(), row.fusionConfidence(), location(row.pilotLocation()),
                bearing == null ? null : bearing.bearingDeg(), bearing == null ? null : bearing.deviceId());
    }

    /* 三段各自没有就返回 null，键被 non_null 序列化省略——空对象在页面上会渲染成一行没有内容的标题。 */

    private RiskSummaryDto riskSummary(TargetSummariesRow summaries) {
        if (summaries == null || summaries.risk() == null) return null;
        RiskSummaryRow risk = summaries.risk();
        return new RiskSummaryDto(risk.riskId(), risk.severity(), risk.state(), millis(risk.occurredAt()));
    }

    private LegalitySummaryDto legalitySummary(TargetSummariesRow summaries) {
        if (summaries == null || summaries.legality() == null) return null;
        LegalitySummaryRow legality = summaries.legality();
        return new LegalitySummaryDto(legality.evaluationId(), legality.legalStatus(), legality.grade(),
                violationReasons(legality.violationReasonsJson()));
    }

    private DisposalSummaryDto disposalSummary(TargetSummariesRow summaries) {
        if (summaries == null || summaries.disposal() == null) return null;
        DisposalSummaryRow disposal = summaries.disposal();
        return new DisposalSummaryDto(disposal.authorizationId(), disposal.authorizationNo(),
                disposal.actionType(), disposal.status());
    }

    /** violation_reasons 是 JSON 数组；读不出来就不给这一项，而不是让整条目标打不开。 */
    private List<String> violationReasons(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.textValue());
            if (node == null || !node.isArray()) return null;
            List<String> reasons = new ArrayList<>();
            for (JsonNode item : node) reasons.add(item.isTextual() ? item.asText() : item.toString());
            return reasons.isEmpty() ? null : reasons;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<FieldIssueDto> issues(TargetRow row) {
        List<FieldIssueDto> issues = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(row.unknownFields());
        } catch (Exception ignored) {
            throw internalError();
        }
        if (root == null || !root.isArray()) throw internalError();
        for (JsonNode issue : root) {
            String field = issue.path("field").asText("");
            String reason = issue.path("reason_code").asText("");
            if (ISSUE_FIELDS.contains(field) && ISSUE_REASONS.contains(reason)
                    && unavailable(row, field)) {
                issues.add(new FieldIssueDto(field, reason));
            }
        }
        issues.sort(Comparator.comparing(FieldIssueDto::field).thenComparing(FieldIssueDto::reasonCode));
        return List.copyOf(issues);
    }

    private static boolean unavailable(TargetRow row, String field) {
        return switch (field) {
            case "location" -> row.location() == null;
            case "altitude_amsl_m" -> row.altitudeAmslM() == null;
            case "height_agl_m" -> row.heightAglM() == null;
            case "speed_mps" -> row.speedMps() == null;
            case "heading_deg" -> row.headingDeg() == null;
            case "classification_confidence" -> row.classificationConfidence() == null;
            case "fusion_confidence" -> row.fusionConfidence() == null;
            default -> false;
        };
    }

    private TargetSourceLinkDto sourceLink(SourceLinkRow row) {
        return new TargetSourceLinkDto(
                row.linkId(), row.sourceId(), row.sourceCode(), row.sourceMode(), row.sourceSessionKey(),
                row.externalTargetId(), row.deviceId(), row.protocolVersion(), row.sourceName(),
                row.sourceType(), row.schemaStatus());
    }

    private TrackSummaryDto track(TrackRow row) {
        return new TrackSummaryDto(
                row.trackId(), row.targetId(), row.linkId(), row.externalTrackId(), row.sourceId(),
                row.sourceCode(), row.sourceMode(), row.deviceId(), millis(row.startedAt()),
                row.layer(), row.configVersion(), millis(row.endedAt()));
    }

    private DegradationDto degradationDto(DegradationRow row) {
        if (row == null) return null;
        return new DegradationDto(row.level(), strings(row.availableSourceIdsJson()).stream()
                .map(id -> sourceCode(id)).filter(java.util.Objects::nonNull).toList(), row.deficit(), row.determined());
    }

    private AttributeSelectionDto selectionDto(SelectionRow row) {
        if (row == null) return null;
        return new AttributeSelectionDto(sourceCode(row.positionSourceId()), sourceCode(row.classSourceId()),
                sourceCode(row.identitySourceId()), sourceCode(row.motionSourceId()), row.manualClassOverride());
    }

    /** 被并目标返回 200，但 current_target_id 指向幸存目标：历史外键仍可解析，页面据此提示“已合并”。 */
    private LineageSummaryDto lineageSummary(String targetId) {
        long count = lineages.countLineage(targetId);
        AliasRow alias = lineages.findAlias(targetId);
        LineageRow last = lineages.lastLineage(targetId);
        if (count == 0 && alias == null) return null;
        return new LineageSummaryDto(alias == null ? targetId : alias.currentTargetId(), count,
                last == null ? null : last.op(), last == null ? null : requiredMillis(last.occurredAt()));
    }

    /** 动作可用性只反映状态与关联数；具体权限由写接口自己再校验一次（这里不提前泄露权限判断）。 */
    private List<String> allowedActions(String targetId, TrackStatusRow status, int linkCount) {
        if (!visible(PermissionCode.FUSION_REVISE)) return List.of();
        if (lineages.findAlias(targetId) != null) return List.of();
        String state = status == null ? null : status.status();
        if ("TERMINATED".equals(state) || "MERGE".equals(state)) return List.of();
        List<String> actions = new ArrayList<>(List.of("REVISE_CLASS", "MERGE"));
        if (linkCount >= 2) actions.add("SPLIT");
        return List.copyOf(actions);
    }

    private boolean visible(PermissionCode permission) {
        try { accessControl.require(permission); return true; }
        catch (ApiException ignored) { return false; }
    }

    private String sourceCode(String sourceId) {
        if (sourceId == null) return null;
        return fusedTracks.sourceCodes(List.of(sourceId)).get(sourceId);
    }

    private List<String> strings(String json) {
        if (json == null) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.textValue());
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) if (item.isTextual()) out.add(item.textValue());
            return List.copyOf(out);
        } catch (Exception ignored) {
            throw internalError();
        }
    }

    private List<ContributionDto> contributions(String json) {
        if (json == null) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.textValue());
            if (node == null || !node.isArray() || node.isEmpty()) return null;
            List<String> ids = new ArrayList<>();
            for (JsonNode item : node) if (item.path("source_id").isTextual()) ids.add(item.path("source_id").textValue());
            var codes = fusedTracks.sourceCodes(ids);
            List<ContributionDto> out = new ArrayList<>();
            for (JsonNode item : node) {
                String code = codes.get(item.path("source_id").asText());
                if (code == null) continue;
                out.add(new ContributionDto(code, item.path("weight").isNumber() ? item.path("weight").decimalValue() : null));
            }
            return out.isEmpty() ? null : List.copyOf(out);
        } catch (Exception ignored) {
            throw internalError();
        }
    }

    private TrackPointDto point(PointRow row) {
        if (row.location() == null) {
            throw internalError();
        }
        OffsetDateTime sortTime = row.observedAt() == null ? row.receivedAt() : row.observedAt();
        return new TrackPointDto(
                row.pointId(), row.trackId(), row.pointSeq(), requiredMillis(sortTime),
                row.observedAt() == null ? "RECEIVED" : "OBSERVED", requiredMillis(row.receivedAt()),
                millis(row.observedAt()), location(row.location()), row.altitudeAmslM(), row.heightAglM(),
                row.pointKind(), row.positionAccuracyM(), contributions(row.contributingJson()),
                row.sourceSwitched(), row.degradationLevel());
    }

    private static LocationDto location(Coordinate coordinate) {
        return coordinate == null ? null
                : new LocationDto(coordinate.longitude(), coordinate.latitude(), "WGS84");
    }

    private static String pathId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        }
        return normalized;
    }

    private static Long millis(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toEpochMilli();
    }

    private static long requiredMillis(OffsetDateTime value) {
        if (value == null) {
            throw internalError();
        }
        return value.toInstant().toEpochMilli();
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static ApiException internalError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
    }

    private static final class RequestValues {
        private final MultiValueMap<String, String> values;

        private RequestValues(MultiValueMap<String, String> values) {
            this.values = values;
        }

        private Pagination pagination() {
            int page = integer("page", 1);
            int size = integer("size", 20);
            if (page < 1 || size < 1 || size > 100) throw invalidPage();
            return new Pagination(page, size);
        }

        private int integer(String name, int defaultValue) {
            if (!values.containsKey(name)) return defaultValue;
            List<String> found = values.get(name);
            if (found == null || found.size() != 1) throw invalidPage();
            String value = found.get(0);
            if (value == null || value.isBlank()) throw invalidPage();
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                throw invalidPage();
            }
        }

        /** 只认 true；缺省、空、别的取值一律当假——默认隐藏被并目标（决策 16-6）。 */
        private boolean flag(String name) {
            List<String> found = values.get(name);
            return found != null && found.size() == 1 && "true".equalsIgnoreCase(found.get(0));
        }

        private String enumerated(String name, Set<String> allowed) {
            String value = optional(name, 16);
            if (value != null && !allowed.contains(value)) throw validation(name);
            return value;
        }

        /** kind 是逗号分隔集合，默认 MEAS,BRIDGE：预测点是推断而非观测，必须显式索取。 */
        private List<String> kinds() {
            if (!values.containsKey("kind")) return DEFAULT_POINT_KINDS;
            String raw = optional("kind", 32);
            List<String> kinds = new ArrayList<>();
            for (String part : raw.split(",")) {
                String kind = part.trim();
                if (!POINT_KINDS.contains(kind) || kinds.contains(kind)) throw validation("kind");
                kinds.add(kind);
            }
            if (kinds.isEmpty()) throw validation("kind");
            return List.copyOf(kinds);
        }

        private String optional(String name, int maxLength) {
            if (!values.containsKey(name)) return null;
            List<String> found = values.get(name);
            if (found == null || found.size() != 1) throw validation(name);
            String value = found.get(0);
            if (value == null || value.isBlank() || value.length() > maxLength) throw validation(name);
            return value;
        }

        private TimeRange timeRange(String fromName, String toName) {
            boolean hasFrom = values.containsKey(fromName);
            boolean hasTo = values.containsKey(toName);
            if (!hasFrom && !hasTo) return new TimeRange(null, null);
            if (!hasFrom || !hasTo) throw invalidTime();
            List<String> fromValues = values.get(fromName);
            List<String> toValues = values.get(toName);
            if (fromValues == null || toValues == null || fromValues.size() != 1 || toValues.size() != 1) {
                throw invalidTime();
            }
            try {
                String fromText = fromValues.get(0);
                String toText = toValues.get(0);
                if (fromText == null || toText == null || fromText.isBlank() || toText.isBlank()) throw invalidTime();
                long fromMillis = Long.parseLong(fromText);
                long toMillis = Long.parseLong(toText);
                if (fromMillis > toMillis) throw invalidTime();
                return new TimeRange(
                        Instant.ofEpochMilli(fromMillis).atOffset(ZoneOffset.UTC),
                        Instant.ofEpochMilli(toMillis).atOffset(ZoneOffset.UTC));
            } catch (NumberFormatException ex) {
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
    }

    private record Pagination(int page, int size) {
        private int offset() {
            try {
                return Math.multiplyExact(page - 1, size);
            } catch (ArithmeticException ex) {
                throw RequestValues.invalidPage();
            }
        }
    }

    private record TimeRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
