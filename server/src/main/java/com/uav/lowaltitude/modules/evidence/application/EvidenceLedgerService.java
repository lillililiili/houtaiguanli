package com.uav.lowaltitude.modules.evidence.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.CountDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.PageDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.*;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceLedgerRepository;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.export.CsvExport;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

/** One authorized selection for list, counts, export and precise record/context lookup. No state transitions. */
@Service
@Transactional(readOnly = true)
public class EvidenceLedgerService {
    private static final List<String> CATEGORIES = List.of("VIDEO", "TRACK", "IMAGE", "COMMAND");
    private static final Set<String> SUBJECTS = Set.of("EVENT", "DEVICE", "TARGET", "PLAN", "COMMAND", "COMMISSION", "CASE", "AUTHORIZATION");
    private final EvidenceAssociationService files;
    private final EvidenceRepository subjects;
    private final EvidenceLedgerRepository repository;
    private final AccessControlService access;
    private final DeviceAccessPolicy deviceAccess;
    private final DeviceService devices;
    private final AppClock clock;
    private final AuditService audit;

    public EvidenceLedgerService(EvidenceAssociationService files, EvidenceRepository subjects,
            EvidenceLedgerRepository repository, AccessControlService access, DeviceAccessPolicy deviceAccess,
            DeviceService devices, AppClock clock, AuditService audit) {
        this.files = files; this.subjects = subjects; this.repository = repository; this.access = access;
        this.deviceAccess = deviceAccess; this.devices = devices; this.clock = clock; this.audit = audit;
    }

    public PageDto<Entry> list(MultiValueMap<String, String> values) {
        AccessDecision scope = access.require(PermissionCode.EVIDENCE_READ);
        Query query = Query.parse(values);
        List<Entry> rows = select(query, scope);
        long offset = (long) (query.page - 1) * query.size;
        int from = (int) Math.min(offset, rows.size());
        return new PageDto<>(rows.subList(from, Math.min(from + query.size, rows.size())), query.page, query.size, rows.size());
    }

    public Stats stats(MultiValueMap<String, String> values) {
        AccessDecision scope = access.require(PermissionCode.EVIDENCE_READ);
        List<Entry> rows = select(Query.parse(values), scope);
        return new Stats(rows.size(), counts(rows, "category", CATEGORIES),
                counts(rows.stream().filter(r -> r.sourceKind().equals("FILE")).toList(), "status", List.of("PENDING", "AVAILABLE", "MISSING", "CORRUPT", "DESTROYED")),
                counts(rows.stream().filter(r -> r.sourceKind().equals("FILE")).toList(), "custody", List.of("KEPT", "NEARING", "DUE", "HELD")));
    }

    public Detail record(String kind, String id, MultiValueMap<String, String> values) {
        AccessDecision scope = access.require(PermissionCode.EVIDENCE_READ);
        if (!Set.of("FILE", "TRACK", "COMMAND").contains(kind) || id == null || id.isBlank()) throw invalid();
        List<Entry> rows = select(Query.parse(values), scope);
        Entry entry = rows.stream().filter(r -> kind.equals(r.sourceKind()) && id.equals(r.sourceId())).findFirst().orElseThrow(EvidenceLedgerService::missing);
        Object command = kind.equals("COMMAND") ? devices.command(id) : null;
        List<Entry> attachments = kind.equals("COMMAND") ? select(new Query(1, 100, null, null, null, "COMMAND", id, null), scope)
                .stream().filter(r -> r.sourceKind().equals("FILE")).toList() : List.of();
        return new Detail(entry, command, attachments, links(entry, scope));
    }

    public Materials materials(String kind, String id) {
        AccessDecision scope = access.require(PermissionCode.EVIDENCE_READ);
        if (!SUBJECTS.contains(kind) || id == null || id.isBlank()) throw invalid();
        if (!canSee(kind) || !subjects.subjectVisible(kind, id, scope)) throw missing();
        List<Entry> rows = select(new Query(1, 100, null, null, null, kind, id, null), scope);
        List<Entry> returned = new ArrayList<>();
        Map<String, Coverage> coverage = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            long count = rows.stream().filter(r -> category.equals(r.category())).count();
            boolean allowed = !category.equals("TRACK") || probe(PermissionCode.TARGET_READ);
            if (category.equals("COMMAND")) allowed = canReadCommands();
            coverage.put(category, new Coverage(count > 0 ? "PRESENT" : allowed ? "ABSENT" : "FORBIDDEN", count, count > 100));
            returned.addAll(rows.stream().filter(r -> category.equals(r.category())).limit(100).toList());
        }
        List<Material> records = returned.stream().sorted(order().reversed()).map(r -> new Material(r.category(), r.sourceId(), r.occurredAt(),
                Set.of("MISSING", "CORRUPT", "DESTROYED", "PENDING", "NO_POINTS").contains(r.status()) ? "UNAVAILABLE" : "PRESENT", r)).toList();
        return new Materials(kind, id, coverage, records);
    }

    @Transactional
    public ResponseEntity<byte[]> export(MultiValueMap<String, String> values, String ip, String userAgent) {
        AccessDecision scope = access.require(PermissionCode.EVIDENCE_READ);
        List<Entry> rows = select(Query.parse(values), scope);
        if (rows.size() > CsvExport.MAX_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE", "导出记录超过上限，请缩小筛选范围");
        var actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_exported",
                "evidence_ledger", null, "count=" + rows.size(), "SUCCESS", ip, userAgent == null ? "" : userAgent);
        return CsvExport.response(CsvExport.fileName("evidence-ledger", clock.now()),
                List.of("编号", "名称", "类型", "来源类型", "状态", "发生时间", "保管状态"),
                rows.stream().map(r -> java.util.Arrays.asList(r.evidenceNo(), r.originalName(), r.category(), r.sourceKind(),
                        r.status(), r.occurredAt() == null ? "" : String.valueOf(r.occurredAt()), r.custody())).toList());
    }

    private List<Entry> select(Query query, AccessDecision scope) {
        if (query.subjectKind != null && !canSee(query.subjectKind)) return List.of();
        if (query.subjectId != null && !subjects.subjectVisible(query.subjectKind, query.subjectId, scope)) return List.of();
        List<Entry> rows = new ArrayList<>();
        // Reuse file visibility, retention and hidden-unlinked rules; do not bypass them with a second file query.
        MultiValueMap<String, String> fileQuery = new LinkedMultiValueMap<>();
        fileQuery.set("size", "100");
        if (query.status != null) fileQuery.set("status", query.status);
        if (query.custody != null) fileQuery.set("custody", query.custody);
        if (query.q != null) fileQuery.set("q", query.q);
        if (query.subjectId != null) { fileQuery.set("subject_kind", query.subjectKind); fileQuery.set("subject_id", query.subjectId); }
        for (int page = 1; ; page++) {
            fileQuery.set("page", String.valueOf(page));
            var result = files.list(fileQuery);
            for (var f : result.items()) {
                String category = switch (f.kindCode()) {
                    case "EO_VIDEO" -> "VIDEO";
                    case "EO_STILL", "SCENE_PHOTO" -> "IMAGE";
                    case "TRACK_SNAPSHOT" -> "TRACK";
                    case "COMMAND_LOG" -> "COMMAND";
                    default -> null;
                };
                if (category != null) rows.add(new Entry("FILE", f.evidenceId(), category, f.evidenceNo(), f.originalName(), f.kindCode(), f.status(),
                        f.capturedAt() != null ? f.capturedAt() : f.storedAt(), f.sizeBytes(), f.retainUntil(), f.custody(), null, null, null, null, null));
            }
            if ((long) page * 100 >= result.total()) break;
        }
        // File status/retention filters do not invent a file lifecycle for raw observations or commands.
        if (query.status == null && query.custody == null) {
            if (probe(PermissionCode.TARGET_READ) && (query.category == null || query.category.equals("TRACK"))) rows.addAll(repository.tracks(scope));
            if (canReadCommands() && (query.category == null || query.category.equals("COMMAND"))) rows.addAll(repository.commands(scope));
        }
        Map<String, Set<String>> related = new LinkedHashMap<>();
        if (query.subjectKind != null) for (String source : List.of("FILE", "TRACK", "COMMAND")) {
            related.put(source, repository.relatedIds(source, query.subjectKind, query.subjectId, scope));
        }
        return rows.stream().filter(r -> query.category == null || query.category.equals(r.category()))
                .filter(r -> query.q == null || (r.evidenceNo() + " " + r.originalName() + " " + r.sourceId()).toLowerCase(Locale.ROOT).contains(query.q.toLowerCase(Locale.ROOT)))
                .filter(r -> query.subjectKind == null || related.get(r.sourceKind()).contains(r.sourceId()))
                .sorted(order()).toList();
    }

    private List<Link> links(Entry entry, AccessDecision scope) {
        return repository.links(entry.sourceKind(), entry.sourceId()).stream()
                .filter(l -> canSee(l.subjectKind()) && subjects.subjectVisible(l.subjectKind(), l.subjectId(), scope))
                .map(l -> new Link(l.subjectKind(), l.subjectId(), subjects.findSubject(l.subjectKind(), l.subjectId()).no())).distinct().toList();
    }
    private boolean canSee(String kind) {
        return switch (kind) {
            case "CASE" -> probe(PermissionCode.PUNISHMENT_READ);
            case "AUTHORIZATION" -> probe(PermissionCode.DISPOSAL_READ);
            case "TARGET" -> probe(PermissionCode.TARGET_READ);
            case "EVENT" -> probe(PermissionCode.ALARM_READ);
            case "PLAN" -> probe(PermissionCode.FLIGHT_READ);
            case "COMMAND", "DEVICE", "COMMISSION" -> canReadCommands();
            default -> false;
        };
    }
    private boolean probe(PermissionCode permission) {
        try { access.require(permission); return true; }
        catch (ApiException e) { if (e.getStatus() == HttpStatus.FORBIDDEN) return false; throw e; }
    }
    private boolean canReadCommands() {
        try { deviceAccess.requireMonitoringRead(); return true; }
        catch (ApiException e) { if (e.getStatus() == HttpStatus.FORBIDDEN) return false; throw e; }
    }
    private static Comparator<Entry> order() {
        return Comparator.comparing(Entry::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Entry::sourceKind).thenComparing(Entry::sourceId);
    }
    private static List<CountDto> counts(List<Entry> rows, String field, List<String> codes) {
        return codes.stream().map(code -> new CountDto(code, rows.stream().filter(r -> code.equals(switch (field) {
            case "category" -> r.category(); case "status" -> r.status(); default -> r.custody();
        })).count())).toList();
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "证据筛选或定位参数无效"); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在或不在当前可见范围内"); }
    private record Query(int page, int size, String category, String status, String custody, String subjectKind, String subjectId, String q) {
        static Query parse(MultiValueMap<String, String> p) {
            if (p.keySet().stream().anyMatch(k -> !Set.of("page", "size", "category", "status", "custody", "subject_kind", "subject_id", "q").contains(k))
                    || p.values().stream().anyMatch(v -> v.size() != 1 || v.get(0) == null || v.get(0).isBlank())) throw invalid();
            String category = p.getFirst("category"), status = p.getFirst("status"), custody = p.getFirst("custody");
            String kind = p.getFirst("subject_kind"), id = p.getFirst("subject_id"), q = p.getFirst("q");
            if (category != null && !CATEGORIES.contains(category) || status != null && !EvidenceAssociationService.STATUSES.contains(status)
                    || custody != null && !Set.of("KEPT", "NEARING", "DUE", "HELD").contains(custody)
                    || kind != null && !SUBJECTS.contains(kind) || id != null && kind == null || q != null && q.length() > 200) throw invalid();
            try {
                int page = p.containsKey("page") ? Integer.parseInt(p.getFirst("page")) : 1;
                int size = p.containsKey("size") ? Integer.parseInt(p.getFirst("size")) : 20;
                if (page < 1 || size < 1 || size > 100) throw invalid();
                return new Query(page, size, category, status, custody, kind, id, q);
            } catch (NumberFormatException e) { throw invalid(); }
        }
    }
}
