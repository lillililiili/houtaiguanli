package com.uav.lowaltitude.modules.evidence.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.AlarmSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.AuthorizationSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.ChainDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.CoverageDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.FileSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.HandoffSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.IntegrityDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.JudgmentSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.LineageOpDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.LineageSectionDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.OperationSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.PreMergeJudgmentDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.RecordDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.TrackSummary;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.VerificationSummary;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceChainChecksum;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceChainChecksum.Member;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.AlarmRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.AliasRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.AuditRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.CommandRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.EventRef;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.FileRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.HandoffRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.JudgmentRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.LineageRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.TargetRef;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.TrackRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceChainRepository.VerificationRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EvidenceChainService {
    static final List<String> TYPES = List.of("TRACK", "VIDEO", "IMAGE", "ALARM", "JUDGMENT",
            "AUTHORIZATION", "DISPOSAL", "OPERATION");
    static final Set<String> ROOTS = Set.of("EVENT", "TARGET", "CASE");
    static final Set<String> AUDIT_OBJECT_TYPES = Set.of("evidence_file", "uav_event", "alarm", "target",
            "device_command", "handoff", "assessment_result");
    private static final ObjectMapper DB_JSON = new ObjectMapper();

    private final AccessControlService access;
    private final AccessService menuAccess;
    private final EvidenceRepository subjects;
    private final EvidenceChainRepository repository;
    private final AppClock clock;

    public EvidenceChainService(AccessControlService access, AccessService menuAccess, EvidenceRepository subjects,
            EvidenceChainRepository repository, AppClock clock) {
        this.access = access;
        this.menuAccess = menuAccess;
        this.subjects = subjects;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChainDto get(String subjectKind, String subjectId, MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        if (parameters != null && !parameters.isEmpty()) throw invalid("参数无效");
        String kind = kind(subjectKind);
        String id = id(subjectId);
        if ("CASE".equals(kind) && !probe(PermissionCode.PUNISHMENT_READ)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
        }
        if (!subjects.subjectVisible(kind, id, decision)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
        }
        boolean targetRead = probe(PermissionCode.TARGET_READ);
        boolean alarmRead = probe(PermissionCode.ALARM_READ);
        boolean assessmentRead = probe(PermissionCode.ASSESSMENT_READ);
        boolean fusionRead = probe(PermissionCode.FUSION_READ);
        boolean handoffRead = probe(PermissionCode.HANDOFF_READ);
        boolean auditRead = probeAudit();

        String eventId = null;
        String alarmId = null;
        String caseId = null;
        String subjectNo;
        String seedTarget = null;
        if ("EVENT".equals(kind)) {
            EventRef event = repository.findEvent(id);
            if (event == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
            eventId = event.eventId();
            alarmId = event.alarmId();
            subjectNo = event.eventId();
            if (targetRead) seedTarget = event.targetId();
        } else if ("CASE".equals(kind)) {
            EvidenceChainRepository.CaseRef crime = repository.findCase(id);
            if (crime == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
            caseId = crime.caseId();
            subjectNo = crime.caseNo();
            eventId = crime.eventId();
            if (eventId != null) {
                EventRef event = repository.findEvent(eventId);
                if (event != null) {
                    alarmId = event.alarmId();
                    if (targetRead) seedTarget = event.targetId();
                }
            }
        } else {
            TargetRef target = repository.findTarget(id);
            if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
            subjectNo = target.targetNo();
            if (targetRead) seedTarget = target.targetId();
        }

        LinkedHashSet<String> family = new LinkedHashSet<>();
        String currentTargetId = null;
        if (seedTarget != null) {
            family.add(seedTarget);
            expandFamily(family);
            currentTargetId = currentOf(seedTarget);
        }
        List<String> historical = new ArrayList<>();
        if (fusionRead && targetRead && currentTargetId != null) {
            for (String member : family) {
                if (!member.equals(currentTargetId)) historical.add(member);
            }
            historical.sort(Comparator.naturalOrder());
        }

        Map<String, List<RecordDto>> buckets = new LinkedHashMap<>();
        for (String type : TYPES) buckets.put(type, new ArrayList<>());

        if (targetRead) addTracks(buckets.get("TRACK"), repository.tracks(family));
        addFiles(buckets, repository.files(eventId, family, caseId));
        if (alarmRead) addAlarms(buckets.get("ALARM"), repository.alarms(alarmId, family));
        if (assessmentRead) addJudgments(buckets.get("JUDGMENT"), repository.judgments(family));
        addCommands(buckets.get("AUTHORIZATION"), repository.linkedCommands(eventId, family), decision);
        if (probe(PermissionCode.DISPOSAL_READ)) {
            for (CommandRow row : cap(repository.linkedAuthorizations(eventId, family))) {
                if (subjects.subjectVisible("AUTHORIZATION", row.authorizationId(), decision)) {
                    buckets.get("AUTHORIZATION").add(new RecordDto("AUTHORIZATION", row.authorizationId(), row.createdAt(),
                            EvidenceChainChecksum.fingerprintAuthorization(row.status(), row.authorizationId()), "PRESENT",
                            new AuthorizationSummary(row.commandNo(), row.authorizationId(), row.status())));
                }
            }
        }

        LinkedHashSet<String> eventIds = new LinkedHashSet<>();
        if (eventId != null) eventIds.add(eventId);
        if (targetRead && !family.isEmpty()) eventIds.addAll(repository.eventIdsForTargets(family));
        if (alarmRead) addVerifications(buckets.get("DISPOSAL"), repository.verifications(eventIds));
        if (handoffRead) addHandoffs(buckets.get("DISPOSAL"), repository.handoffs(eventIds));

        LineageSectionDto lineage;
        if (!(targetRead && fusionRead)) {
            lineage = new LineageSectionDto("FORBIDDEN", null, null);
        } else {
            List<LineageRow> rows = repository.lineage(family);
            lineage = lineageSection(rows, assessmentRead);
        }

        LinkedHashSet<String> auditIds = new LinkedHashSet<>();
        if (eventId != null) auditIds.add(eventId);
        if (alarmId != null) auditIds.add(alarmId);
        auditIds.addAll(family);
        for (List<RecordDto> records : buckets.values()) {
            for (RecordDto record : records) auditIds.add(record.recordId());
        }
        if (auditRead) addAudits(buckets.get("OPERATION"), repository.audits(auditIds, AUDIT_OBJECT_TYPES));

        Map<String, CoverageDto> coverage = new LinkedHashMap<>();
        coverage.put("TRACK", cover("TRACK", buckets.get("TRACK"), targetRead));
        coverage.put("VIDEO", cover("VIDEO", buckets.get("VIDEO"), true));
        coverage.put("IMAGE", cover("IMAGE", buckets.get("IMAGE"), true));
        coverage.put("ALARM", cover("ALARM", buckets.get("ALARM"), alarmRead));
        coverage.put("JUDGMENT", cover("JUDGMENT", buckets.get("JUDGMENT"), assessmentRead));
        coverage.put("AUTHORIZATION", cover("AUTHORIZATION", buckets.get("AUTHORIZATION"), true));
        coverage.put("DISPOSAL", cover("DISPOSAL", buckets.get("DISPOSAL"), alarmRead || handoffRead));
        coverage.put("OPERATION", cover("OPERATION", buckets.get("OPERATION"), auditRead));

        List<RecordDto> records = new ArrayList<>();
        for (String type : TYPES) records.addAll(buckets.get(type));
        records.sort(Comparator.comparing((RecordDto r) -> r.occurredAt() == null ? Long.MAX_VALUE : r.occurredAt())
                .thenComparing(RecordDto::recordType)
                .thenComparing(RecordDto::recordId));
        List<Member> members = records.stream()
                .map(r -> new Member(r.recordType(), r.recordId(), r.fingerprint()))
                .toList();
        IntegrityDto integrity = new IntegrityDto(EvidenceChainChecksum.ALGORITHM,
                EvidenceChainChecksum.digest(members), members.size(), clock.nowMillis());
        return new ChainDto(kind, id, subjectNo,
                targetRead ? currentTargetId : null,
                historical.isEmpty() ? null : List.copyOf(historical),
                coverage, integrity, List.copyOf(records), lineage);
    }

    private void expandFamily(Set<String> family) {
        boolean grew = true;
        while (grew) {
            grew = false;
            List<AliasRow> rows = repository.aliasesTouching(family);
            for (AliasRow row : rows) {
                grew |= family.add(row.historicalTargetId());
                grew |= family.add(row.currentTargetId());
            }
        }
    }

    private String currentOf(String seed) {
        for (AliasRow row : repository.aliasesTouching(List.of(seed))) {
            if (seed.equals(row.historicalTargetId())) return row.currentTargetId();
        }
        return seed;
    }

    private void addTracks(List<RecordDto> sink, List<TrackRow> rows) {
        for (TrackRow row : cap(rows)) {
            Long ended = millis(row.endedAt());
            String fp = EvidenceChainChecksum.fingerprintTrack(row.layer(), ended, row.pointCount());
            sink.add(new RecordDto("TRACK", row.trackId(), millis(row.startedAt()), fp, "PRESENT",
                    new TrackSummary(row.layer(), millis(row.startedAt()), ended, row.pointCount())));
        }
    }

    private void addFiles(Map<String, List<RecordDto>> buckets, List<FileRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (FileRow row : rows) {
            String type = EvidenceChainChecksum.fileRecordType(row.kindCode());
            if (type == null) continue;
            int n = counts.merge(type, 1, Integer::sum);
            if (n > EvidenceChainChecksum.TYPE_LIMIT) continue;
            String fp = EvidenceChainChecksum.fingerprintFile(row.sha256(), row.status());
            Long at = millis(row.capturedAt() != null ? row.capturedAt() : row.storedAt());
            String availability = EvidenceChainChecksum.fileAvailable(row.status()) ? "PRESENT" : "UNAVAILABLE";
            buckets.get(type).add(new RecordDto(type, row.evidenceId(), at, fp, availability,
                    new FileSummary(row.evidenceNo(), row.kindCode(), row.originalName(), row.status(),
                            row.sha256(), row.sizeBytes())));
        }
    }

    private void addAlarms(List<RecordDto> sink, List<AlarmRow> rows) {
        Set<String> seen = new HashSet<>();
        List<AlarmRow> unique = new ArrayList<>();
        for (AlarmRow row : rows) {
            if (seen.add(row.alarmId())) unique.add(row);
        }
        for (AlarmRow row : cap(unique)) {
            Long received = millis(row.receivedAt());
            sink.add(new RecordDto("ALARM", row.alarmId(), received,
                    EvidenceChainChecksum.fingerprintAlarm(received, row.severity()), "PRESENT",
                    new AlarmSummary(row.severity(), row.alarmType(), row.sourceMode(), received)));
        }
    }

    private void addJudgments(List<RecordDto> sink, List<JudgmentRow> rows) {
        for (JudgmentRow row : cap(rows)) {
            Long assessed = millis(row.assessedAt());
            sink.add(new RecordDto("JUDGMENT", row.assessmentId(), assessed,
                    EvidenceChainChecksum.fingerprintJudgment(assessed, row.conclusionCode()), "PRESENT",
                    new JudgmentSummary(row.targetId(), row.conclusionCode(), assessed, row.ruleVersionId())));
        }
    }

    private void addCommands(List<RecordDto> sink, List<CommandRow> rows, AccessDecision decision) {
        List<CommandRow> visible = new ArrayList<>();
        for (CommandRow row : rows) {
            if (subjects.subjectVisible("COMMAND", row.commandId(), decision)) visible.add(row);
        }
        for (CommandRow row : cap(visible)) {
            sink.add(new RecordDto("AUTHORIZATION", row.commandId(), row.createdAt(),
                    EvidenceChainChecksum.fingerprintAuthorization(row.status(), row.authorizationId()), "PRESENT",
                    new AuthorizationSummary(row.commandNo(), row.authorizationId(), row.status())));
        }
    }

    private void addVerifications(List<RecordDto> sink, List<VerificationRow> rows) {
        for (VerificationRow row : cap(rows)) {
            sink.add(new RecordDto("DISPOSAL", row.historyId(), millis(row.createdAt()),
                    EvidenceChainChecksum.fingerprintVerification(row.version(), row.conclusion()), "PRESENT",
                    new VerificationSummary(row.conclusion(), row.version(), row.resultingState())));
        }
    }

    private void addHandoffs(List<RecordDto> sink, List<HandoffRow> rows) {
        int remaining = EvidenceChainChecksum.TYPE_LIMIT - sink.size();
        if (remaining <= 0) return;
        List<HandoffRow> slice = rows.size() > remaining ? rows.subList(0, remaining) : rows;
        for (HandoffRow row : slice) {
            String delivery = row.deliveryStatus() == null ? "" : row.deliveryStatus();
            sink.add(new RecordDto("DISPOSAL", row.handoffId(), millis(row.createdAt()),
                    EvidenceChainChecksum.fingerprintHandoff(delivery, row.sourceVersion()), "PRESENT",
                    new HandoffSummary(row.handoffType(), row.deliveryStatus(), row.sourceVersion())));
        }
    }

    private void addAudits(List<RecordDto> sink, List<AuditRow> rows) {
        int remaining = EvidenceChainChecksum.TYPE_LIMIT - sink.size();
        if (remaining <= 0) return;
        List<AuditRow> slice = rows.size() > remaining ? rows.subList(0, remaining) : rows;
        for (AuditRow row : slice) {
            sink.add(new RecordDto("OPERATION", row.auditId(), row.occurredAt(),
                    EvidenceChainChecksum.fingerprintAudit(row.action(), row.occurredAt()), "PRESENT",
                    new OperationSummary(row.action(), row.result(), row.moduleCode())));
        }
    }

    private LineageSectionDto lineageSection(List<LineageRow> rows, boolean assessmentRead) {
        List<LineageOpDto> ops = new ArrayList<>();
        List<PreMergeJudgmentDto> judgments = new ArrayList<>();
        for (LineageRow row : cap(rows)) {
            List<String> members = strings(row.memberTargetIdsJson());
            ops.add(new LineageOpDto(row.lineageId(), row.op(), millis(row.occurredAt()),
                    row.survivorTargetId(), row.originTargetId(), members, parseJson(row.snapshotsJson())));
            if (!assessmentRead) continue;
            if (!"MERGE".equals(row.op()) && !"SPLIT".equals(row.op())) continue;
            LinkedHashSet<String> ids = new LinkedHashSet<>(members);
            if (row.originTargetId() != null) ids.add(row.originTargetId());
            List<JudgmentRow> found = repository.judgmentsAtOrBefore(ids, row.occurredAt());
            Map<String, JudgmentRow> latest = new LinkedHashMap<>();
            for (JudgmentRow judgment : found) {
                latest.putIfAbsent(judgment.targetId(), judgment);
            }
            for (String member : ids) {
                JudgmentRow hit = latest.get(member);
                if (hit == null) {
                    judgments.add(new PreMergeJudgmentDto(member, row.lineageId(), "ABSENT", null, null, null));
                } else {
                    judgments.add(new PreMergeJudgmentDto(member, row.lineageId(), "PRESENT", hit.assessmentId(),
                            hit.conclusionCode(), millis(hit.assessedAt())));
                }
            }
        }
        return new LineageSectionDto("PRESENT", List.copyOf(ops), List.copyOf(judgments));
    }

    private CoverageDto cover(String type, List<RecordDto> records, boolean extraAllowed) {
        if (!extraAllowed && records.isEmpty()) return new CoverageDto("FORBIDDEN", 0, null, null);
        boolean truncated = records.size() > EvidenceChainChecksum.TYPE_LIMIT;
        if (truncated) records.subList(EvidenceChainChecksum.TYPE_LIMIT, records.size()).clear();
        if (records.isEmpty()) return new CoverageDto("ABSENT", 0, null, null);
        Integer broken = null;
        if (hasFiles(type, records)) {
            int n = 0;
            for (RecordDto record : records) {
                if (record.summary() instanceof FileSummary file && EvidenceChainChecksum.fileBroken(file.status())) n++;
            }
            broken = n;
        }
        return new CoverageDto("PRESENT", records.size(), broken, truncated ? true : null);
    }

    private static boolean hasFiles(String type, List<RecordDto> records) {
        if ("VIDEO".equals(type) || "IMAGE".equals(type)) return true;
        for (RecordDto record : records) {
            if (record.summary() instanceof FileSummary) return true;
        }
        return false;
    }

    private <T> List<T> cap(List<T> rows) {
        if (rows.size() <= EvidenceChainChecksum.TYPE_LIMIT) return rows;
        return rows.subList(0, EvidenceChainChecksum.TYPE_LIMIT);
    }

    private List<String> strings(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) return List.of();
        try {
            var node = DB_JSON.readTree(raw);
            if (node != null && node.isTextual()) node = DB_JSON.readTree(node.textValue());
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (var item : node) if (item.isTextual()) values.add(item.textValue());
            return List.copyOf(values);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var node = DB_JSON.readTree(raw);
            if (node != null && node.isTextual()) node = DB_JSON.readTree(node.textValue());
            if (node == null || node.isNull()) return null;
            return DB_JSON.convertValue(node, Object.class);
        } catch (Exception ex) {
            return raw;
        }
    }

    private boolean probe(PermissionCode permission) {
        try {
            access.require(permission);
            return true;
        } catch (ApiException ex) {
            if (HttpStatus.FORBIDDEN.equals(ex.getStatus())) return false;
            throw ex;
        }
    }

    private boolean probeAudit() {
        try {
            menuAccess.require("audit.read");
            return true;
        } catch (ApiException ex) {
            if (HttpStatus.FORBIDDEN.equals(ex.getStatus())) return false;
            throw ex;
        }
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static String kind(String value) {
        String kind = value == null ? "" : value.trim();
        if (!ROOTS.contains(kind)) throw invalid("关联对象类型无效");
        return kind;
    }

    private static String id(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) throw invalid("ID 格式无效");
        return normalized;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
