package com.uav.lowaltitude.modules.airspace.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportBatchDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportDecisionDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportIssueDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportItemDto;
import com.uav.lowaltitude.modules.airspace.domain.AirspaceKind;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.Feature;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.Issue;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.ParseResult;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceImportRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceImportRepository.BatchRow;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceImportRepository.ItemRow;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository.VersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * GeoJSON 导入：暂存 → 确认 / 放弃。
 * 为什么分两步：导入的是别人给的文件，一次性写入会让操作者在看清"哪几条被拒绝、为什么"之前就产生了
 * 不可回退的历史事实（空域版本只增、只允许关闭 valid_to）。暂存把解析结果原样摆出来，确认才写业务表。
 */
@Service
public class AirspaceImportService {
    private static final Set<String> STAGE_FIELDS = Set.of("geojson", "defaults", "note", "owner_org_id", "district_id");
    private static final Set<String> DEFAULT_FIELDS = Set.of("kind_code", "altitude_datum", "valid_from");
    private static final Set<String> DECISION_FIELDS = Set.of("expected_version");
    private static final int NOTE_MAX = 500, ID_MAX = 36;

    private final AccessControlService access;
    private final AirspaceImportRepository imports;
    private final AirspaceWriteRepository airspaces;
    private final AirspaceWriteService writes;
    private final GeoJsonParser geoJson;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public AirspaceImportService(AccessControlService access, AirspaceImportRepository imports, AirspaceWriteRepository airspaces,
            AirspaceWriteService writes, GeoJsonParser geoJson, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.imports = imports; this.airspaces = airspaces; this.writes = writes; this.geoJson = geoJson;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional
    public ImportBatchDto stage(String rawBody, String idempotencyKey) {
        AccessDecision decision = writes.requireManage();
        StageRequest request = parseStage(rawBody);
        idempotency.claim(idempotencyKey, AirspaceWriteService.framed("airspace-import")
                + AirspaceWriteService.framed(request.ownerOrgId()) + AirspaceWriteService.framed(request.districtId())
                + AirspaceWriteService.framed(Integer.toString(request.geoJson().length())));
        ParseResult parsed = geoJson.parse(request.geoJson());
        if (parsed.fatalIssue() != null) {
            HttpStatus status = HttpStatus.BAD_REQUEST;
            String code = "IMPORT_TOO_LARGE".equals(parsed.fatalIssue()) ? "IMPORT_TOO_LARGE" : "INVALID_GEOJSON";
            throw new ApiException(status, code, "GeoJSON 无法导入：" + parsed.fatalIssue());
        }
        Instant now = clock.now();
        String batchId = UUID.randomUUID().toString();
        AuthUser actor = AuthContext.require();
        List<ItemRow> rows = new ArrayList<>();
        List<String> ewkts = new ArrayList<>();
        for (Feature feature : parsed.features()) {
            List<Issue> issues = new ArrayList<>(feature.issues());
            String kindCode = feature.kindCode() != null ? feature.kindCode() : request.defaultKindCode();
            if (kindCode == null) issues.add(new Issue("kind_code", "KIND_MISSING"));
            else if (!AirspaceKind.supported(kindCode)) { issues.add(new Issue("kind_code", "KIND_NOT_SUPPORTED")); kindCode = null; }
            Instant validFrom = feature.validFrom() != null ? feature.validFrom() : request.defaultValidFrom();
            if (validFrom == null) issues.add(new Issue("valid_from", "VALID_FROM_MISSING"));
            String datum = feature.altitudeDatum() != null ? feature.altitudeDatum() : request.defaultAltitudeDatum();
            // 高度数字缺基准就不可比较：宁可整组不采用，也不给一个没有基准的高度带。
            boolean altitudeUsable = feature.minAltitudeM() != null && feature.maxAltitudeM() != null && datum != null;
            boolean accepted = issues.isEmpty() && feature.boundaryEwkt() != null;
            rows.add(new ItemRow(UUID.randomUUID().toString(), batchId, feature.seq(), feature.name(), feature.airspaceNo(), kindCode,
                    feature.boundaryGeoJson(), altitudeUsable ? feature.minAltitudeM() : null, altitudeUsable ? feature.maxAltitudeM() : null,
                    altitudeUsable ? datum : null, validFrom, feature.validTo(), write(issues), accepted, null, null));
            ewkts.add(feature.boundaryEwkt());
        }
        int accepted = (int) rows.stream().filter(ItemRow::accepted).count();
        imports.insertBatch(batchId, rows.size(), accepted, request.note(), request.ownerOrgId(), request.districtId(), actor.userId(), now);
        for (int i = 0; i < rows.size(); i++) imports.insertItem(rows.get(i), ewkts.get(i), now);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), AirspaceWriteService.MODULE, "airspace_import_staged",
                "airspace_import_batch", batchId, "features=" + rows.size() + "; accepted=" + accepted, "SUCCESS", "", "");
        return batch(imports.findBatch(batchId, AirspaceWriteService.scopeUser(decision)), imports.items(batchId));
    }

    @Transactional(readOnly = true)
    public ImportBatchDto batch(String batchId) {
        AccessDecision decision = access.require(com.uav.lowaltitude.modules.identity.domain.PermissionCode.AIRSPACE_READ);
        BatchRow row = imports.findBatch(AirspaceWriteService.identifier(batchId), AirspaceWriteService.scopeUser(decision));
        if (row == null) throw notFound();
        return batch(row, imports.items(row.batchId()));
    }

    @Transactional
    public ImportDecisionDto confirm(String batchId, String rawBody, String idempotencyKey) {
        AccessDecision decision = writes.requireManage();
        long expectedVersion = parseDecision(rawBody);
        String id = AirspaceWriteService.identifier(batchId);
        BatchRow batch = imports.lockBatch(id, AirspaceWriteService.scopeUser(decision));
        if (batch == null) throw notFound();
        idempotency.claim(idempotencyKey, AirspaceWriteService.framed("airspace-import-confirm") + AirspaceWriteService.framed(id)
                + AirspaceWriteService.framed(Long.toString(expectedVersion)));
        requireStaged(batch, expectedVersion);
        Instant now = clock.now();
        AuthUser actor = AuthContext.require();
        int createdAirspaces = 0, createdVersions = 0;
        for (ItemRow item : imports.items(id)) {
            if (!item.accepted()) continue;
            String boundary = imports.boundaryEwkt(item.itemId(), airspaces.postgis());
            String airspaceNo = item.airspaceNo() != null ? item.airspaceNo() : generatedNo(batch, item);
            String existingId = airspaces.findAirspaceIdByNo(airspaceNo);
            String versionId = UUID.randomUUID().toString();
            String supersededId = null;
            String airspaceId;
            int versionNo;
            if (existingId == null) {
                airspaceId = UUID.randomUUID().toString();
                airspaces.insertAirspace(airspaceId, airspaceNo, item.name() != null ? item.name() : airspaceNo,
                        batch.ownerOrgId(), batch.districtId(), now);
                versionNo = 1;
                createdAirspaces++;
            } else {
                // 编号已存在：同一片空域的新一版，走与人工追加版本相同的接替式写入，不覆盖旧版内容。
                airspaceId = existingId;
                VersionRow latest = airspaces.findLatestVersion(existingId);
                if (latest == null || !item.validFrom().isAfter(latest.validFrom())) {
                    throw new ApiException(HttpStatus.CONFLICT, "VERSION_OVERLAP",
                            "第 " + item.seq() + " 条的生效时间不晚于该空域现有版本");
                }
                VersionRow open = airspaces.findOpenVersion(existingId, item.validFrom());
                if (open != null && open.validTo() == null) {
                    if (airspaces.closeVersion(open.airspaceVersionId(), item.validFrom()) != 1) throw AirspaceWriteService.versionConflict();
                    supersededId = open.airspaceVersionId();
                }
                versionNo = latest.versionNo() + 1;
            }
            airspaces.insertVersion(new VersionRow(versionId, airspaceId, versionNo, item.kindCode(), item.minAltitudeM(), item.maxAltitudeM(),
                    item.altitudeDatum(), item.validFrom(), item.validTo(), "GeoJSON 导入", now), boundary);
            airspaces.insertOrigin(UUID.randomUUID().toString(), versionId, "GEOJSON_IMPORT", actor.userId(), item.itemId(), supersededId, now);
            imports.linkItemResult(item.itemId(), airspaceId, versionId);
            createdVersions++;
        }
        if (imports.decide(id, expectedVersion, "CONFIRMED", actor.userId(), now) != 1) throw AirspaceWriteService.versionConflict();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), AirspaceWriteService.MODULE, "airspace_import_confirmed",
                "airspace_import_batch", id, "created_airspaces=" + createdAirspaces + "; created_versions=" + createdVersions, "SUCCESS", "", "");
        return new ImportDecisionDto(id, "CONFIRMED", createdAirspaces, createdVersions, expectedVersion + 1);
    }

    @Transactional
    public ImportDecisionDto discard(String batchId, String rawBody, String idempotencyKey) {
        AccessDecision decision = writes.requireManage();
        long expectedVersion = parseDecision(rawBody);
        String id = AirspaceWriteService.identifier(batchId);
        BatchRow batch = imports.lockBatch(id, AirspaceWriteService.scopeUser(decision));
        if (batch == null) throw notFound();
        idempotency.claim(idempotencyKey, AirspaceWriteService.framed("airspace-import-discard") + AirspaceWriteService.framed(id)
                + AirspaceWriteService.framed(Long.toString(expectedVersion)));
        requireStaged(batch, expectedVersion);
        Instant now = clock.now();
        AuthUser actor = AuthContext.require();
        if (imports.decide(id, expectedVersion, "DISCARDED", actor.userId(), now) != 1) throw AirspaceWriteService.versionConflict();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), AirspaceWriteService.MODULE, "airspace_import_discarded",
                "airspace_import_batch", id, "expected_version=" + expectedVersion, "SUCCESS", "", "");
        return new ImportDecisionDto(id, "DISCARDED", 0, 0, expectedVersion + 1);
    }

    /** 已决定的批次不能再决定第二次：确认会产生空域版本，重复确认等于凭同一份文件写两遍历史。 */
    private static void requireStaged(BatchRow batch, long expectedVersion) {
        if (!"STAGED".equals(batch.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_ALREADY_DECIDED", "该导入批次已经处理过");
        }
        if (batch.version() != expectedVersion) throw AirspaceWriteService.versionConflict();
    }

    /** 文件没给编号时按批次与序号生成，保证同一批次内稳定且不与既有编号相撞。 */
    private String generatedNo(BatchRow batch, ItemRow item) {
        String candidate = "KY-IMP-" + batch.batchId().substring(0, 8) + "-" + String.format("%03d", item.seq());
        return airspaces.airspaceNoExists(candidate) ? candidate + "-" + UUID.randomUUID().toString().substring(0, 4) : candidate;
    }

    private record StageRequest(String geoJson, String defaultKindCode, String defaultAltitudeDatum, Instant defaultValidFrom,
            String note, String ownerOrgId, String districtId) { }

    private StageRequest parseStage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(rawBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            node.fieldNames().forEachRemaining(name -> {
                if (!STAGE_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            });
            JsonNode geoJsonNode = node.get("geojson");
            if (geoJsonNode == null || geoJsonNode.isNull()) throw validation("geojson 必填");
            String text = geoJsonNode.isTextual() ? geoJsonNode.textValue() : geoJsonNode.toString();
            JsonNode defaults = node.get("defaults");
            String kindCode = null, datum = null;
            Instant validFrom = null;
            if (defaults != null && !defaults.isNull()) {
                if (!defaults.isObject()) throw validation("defaults 必须是对象");
                defaults.fieldNames().forEachRemaining(name -> {
                    if (!DEFAULT_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "defaults 包含未知字段 " + name);
                });
                kindCode = text(defaults, "kind_code");
                if (kindCode != null && !AirspaceKind.supported(kindCode)) throw validation("默认空域种类只能是 " + AirspaceKind.options());
                datum = text(defaults, "altitude_datum");
                if (datum != null && !"AGL".equals(datum) && !"AMSL".equals(datum)) throw validation("默认高度基准只能是 AGL 或 AMSL");
                JsonNode from = defaults.get("valid_from");
                if (from != null && !from.isNull()) {
                    if (!from.isIntegralNumber() || !from.canConvertToLong()) throw validation("默认生效时间必须是毫秒时间戳");
                    validFrom = Instant.ofEpochMilli(from.longValue());
                }
            }
            String note = text(node, "note");
            if (note != null && note.length() > NOTE_MAX) throw validation("note 超出长度限制");
            String org = text(node, "owner_org_id"), district = text(node, "district_id");
            if (org == null || district == null || org.length() > ID_MAX || district.length() > ID_MAX) throw validation("归属机构与区域必填");
            return new StageRequest(text, kindCode, datum, validFrom, note, org, district);
        } catch (java.io.IOException ex) {
            throw invalidRequest();
        }
    }

    private long parseDecision(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(rawBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            node.fieldNames().forEachRemaining(name -> {
                if (!DECISION_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            });
            JsonNode expected = node.get("expected_version");
            if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0) {
                throw validation("expected_version 无效");
            }
            return expected.longValue();
        } catch (java.io.IOException ex) {
            throw invalidRequest();
        }
    }

    private ImportBatchDto batch(BatchRow row, List<ItemRow> items) {
        List<ImportItemDto> dtos = new ArrayList<>();
        for (ItemRow item : items) {
            dtos.add(new ImportItemDto(item.itemId(), item.seq(), item.name(), item.airspaceNo(), item.kindCode(),
                    item.minAltitudeM(), item.maxAltitudeM(), item.altitudeDatum(),
                    item.validFrom() == null ? null : item.validFrom().toEpochMilli(),
                    item.validTo() == null ? null : item.validTo().toEpochMilli(),
                    issues(item.issuesJson()), item.accepted(), item.targetAirspaceId(), item.resultVersionId()));
        }
        return new ImportBatchDto(row.batchId(), row.status(), row.featureCount(), row.acceptedCount(), row.ownerOrgId(), row.districtId(),
                row.createdAt() == null ? null : row.createdAt().toEpochMilli(),
                row.decidedAt() == null ? null : row.decidedAt().toEpochMilli(), row.version(), List.copyOf(dtos));
    }

    private List<ImportIssueDto> issues(String issuesJson) {
        List<ImportIssueDto> out = new ArrayList<>();
        if (issuesJson == null) return out;
        try {
            JsonNode node = json.readTree(issuesJson);
            // H2 把 CAST(? AS JSON) 存成 JSON 文本，读回是带引号的字符串；PostgreSQL 直接是数组。两种形态都要能解开。
            if (node.isTextual()) node = json.readTree(node.textValue());
            for (JsonNode issue : node) out.add(new ImportIssueDto(issue.path("field").asText(null), issue.path("reason_code").asText(null)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("导入问题列表无法解析", ex);
        }
        return out;
    }

    private String write(List<Issue> issues) {
        List<java.util.Map<String, String>> plain = new ArrayList<>();
        for (Issue issue : issues) plain.add(java.util.Map.of("field", issue.field(), "reason_code", issue.reasonCode()));
        try { return json.writeValueAsString(plain); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("导入问题列表无法序列化", ex); }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.textValue().trim();
        return text.isEmpty() ? null : text;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "IMPORT_BATCH_NOT_FOUND", "导入批次不存在或不可见");
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体无效");
    }
}
