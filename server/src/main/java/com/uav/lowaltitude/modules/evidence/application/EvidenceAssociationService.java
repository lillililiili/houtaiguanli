package com.uav.lowaltitude.modules.evidence.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.AccessLogDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.CreatedLinkDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceDetailDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceSummaryDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.HoldDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.LinkDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.PageDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.VerifyDto;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceRetention;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.FileInsert;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.FileQuery;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.FileRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.HoldRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.LinkRow;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository.SubjectRef;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort.StoredObject;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EvidenceAssociationService {
    static final Set<String> KINDS = Set.of("EO_VIDEO", "EO_STILL", "TRACK_SNAPSHOT", "NOTICE_RECEIPT",
            "COMMISSION_REPORT", "COMMAND_LOG", "SCENE_PHOTO", "PENALTY_DOCUMENT");
    static final Set<String> STATUSES = Set.of("PENDING", "AVAILABLE", "MISSING", "CORRUPT", "DESTROYED");
    static final Set<String> SUBJECTS = Set.of("EVENT", "DEVICE", "TARGET", "PLAN", "COMMAND", "COMMISSION",
            "CASE", "AUTHORIZATION");
    static final Set<String> MODES = Set.of("mock", "replay", "live");
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "kind_code", "status", "custody",
            "subject_kind", "subject_id", "q");
    private static final Set<String> CUSTODIES = Set.of("KEPT", "NEARING", "DUE", "HELD");

    private final AccessControlService access;
    private final AccessService menuAccess;
    private final EvidenceRepository repository;
    private final ObjectStoragePort storage;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final AppProperties properties;

    public EvidenceAssociationService(AccessControlService access, AccessService menuAccess,
            EvidenceRepository repository, ObjectStoragePort storage, IdempotencyGuard idempotency,
            AuditService audit, AppClock clock, AppProperties properties) {
        this.access = access;
        this.menuAccess = menuAccess;
        this.repository = repository;
        this.storage = storage;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageDto<EvidenceSummaryDto> list(MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        boolean ingest = probe(PermissionCode.EVIDENCE_INGEST);
        Request request = Request.list(parameters);
        if (request.subjectKind() != null && (!canSeeKind(request.subjectKind())
                || !repository.subjectVisible(request.subjectKind(), request.subjectId(), decision))) {
            return new PageDto<>(List.of(), request.page(), request.size(), 0);
        }
        FileQuery query = request.query();
        long total = repository.count(query, decision, ingest);
        List<EvidenceSummaryDto> items = repository.list(query, decision, ingest, request.offset(), request.size())
                .stream().map(this::summary).toList();
        return new PageDto<>(items, request.page(), request.size(), total);
    }

    @Transactional
    public EvidenceDetailDto get(String evidenceId) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                "VIEW", "GRANTED", null, clock.now());
        return detail(file, decision);
    }

    @Transactional(readOnly = true)
    public List<EvidenceSummaryDto> listBySubject(String subjectKind, String subjectId) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        String kind = subject(subjectKind);
        String id = id(subjectId);
        if (!canSeeKind(kind) || !repository.subjectVisible(kind, id, decision)) return List.of();
        FileQuery query = new FileQuery(null, null, kind, id, null);
        boolean ingest = probe(PermissionCode.EVIDENCE_INGEST);
        return repository.list(query, decision, ingest, 0, 100).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public PageDto<AccessLogDto> accessLogs(String evidenceId, MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        Request request = Request.pageOnly(parameters);
        long total = repository.accessCount(file.evidenceId());
        List<AccessLogDto> items = repository.accessLogs(file.evidenceId(), request.offset(), request.size()).stream()
                .map(row -> new AccessLogDto(row.accessId(), row.action(), row.result(), row.reasonCode(),
                        millis(row.createdAt()), row.userId()))
                .toList();
        return new PageDto<>(items, request.page(), request.size(), total);
    }

    @Transactional
    public CsvExport exportCsv(MultiValueMap<String, String> parameters, String ip, String userAgent) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        boolean ingest = probe(PermissionCode.EVIDENCE_INGEST);
        Request request = Request.list(parameters);
        FileQuery query = request.query();
        List<FileRow> rows = repository.list(query, decision, ingest, 0, 100);
        StringBuilder body = new StringBuilder(
                "evidence_no,kind_code,original_name,status,size_bytes,sha256,captured_at,stored_at,retain_until,retain_label,custody\n");
        Instant now = clock.now();
        for (FileRow row : rows) {
            Instant until = retainUntilOf(row);
            boolean held = repository.hasActiveHold(row.evidenceId());
            body.append(csv(row.evidenceNo())).append(',').append(csv(row.kindCode())).append(',')
                    .append(csv(row.originalName())).append(',').append(csv(row.status())).append(',')
                    .append(row.sizeBytes() == null ? "" : row.sizeBytes()).append(',')
                    .append(csv(row.sha256())).append(',')
                    .append(row.capturedAt() == null ? "" : row.capturedAt().toEpochMilli()).append(',')
                    .append(row.storedAt() == null ? "" : row.storedAt().toEpochMilli()).append(',')
                    .append(until == null ? "" : until.toEpochMilli()).append(',')
                    .append(csv(EvidenceRetention.policy(row.kindCode()).label())).append(',')
                    .append(csv(EvidenceRetention.custody(until, now, held))).append('\n');
        }
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_exported",
                "evidence_file", null, "count=" + rows.size(), "SUCCESS", ip, userAgent == null ? "" : userAgent);
        return new CsvExport("evidence-files.csv", body.toString());
    }

    @Transactional
    public EvidenceDetailDto ingest(MultipartFile file, MultiValueMap<String, String> form, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_INGEST);
        IngestRequest request = IngestRequest.parse(file, form, properties.getSourceMode(), clock.now());
        SubjectRef subject = null;
        if (request.subjectKind() != null) {
            subject = requireVisibleSubject(request.subjectKind(), request.subjectId(), decision);
        }
        String org = request.ownerOrgId();
        String district = request.districtId();
        if (subject != null) {
            if (org != null && (!org.equals(subject.ownerOrgId()) || !district.equals(subject.districtId()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "归属必须与关联对象一致");
            }
            org = subject.ownerOrgId();
            district = subject.districtId();
        }
        if (org == null || district == null) throw invalid("必须指定归属组织与区域，或关联一个业务对象");
        if (!repository.catalogEnabled(org, district)) throw invalid("组织或区域无效");
        menuAccess.requireTuple(org, district);
        idempotency.claim(idempotencyKey, request.kindCode() + "|" + request.originalName() + "|" + request.size()
                + "|" + org + "|" + district + "|" + nullToEmpty(request.subjectKind()) + "|" + nullToEmpty(request.subjectId()));
        Instant now = clock.now();
        String evidenceId = UUID.randomUUID().toString();
        String objectKey = now.toString().substring(0, 10) + "/" + evidenceId + "/" + request.storedName();
        Instant retainUntil = EvidenceRetention.until(request.kindCode(), request.capturedAt(), now);
        repository.insertFile(new FileInsert(evidenceId, evidenceNo(now, evidenceId), request.kindCode(),
                request.originalName(), request.contentType(), "local", objectKey, null, null,
                request.capturedAt(), null, retainUntil, "PENDING", request.sourceMode(), org, district, now, now, 0));
        StoredObject stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.putNew(objectKey, in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (stored.sizeBytes() == 0) throw invalid("空文件不能入库");
        if (stored.sizeBytes() > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "文件超过 32 MiB");
        repository.updateAvailable(evidenceId, stored.sizeBytes(), stored.sha256(), now, now, 1);
        if (subject != null) {
            repository.insertLink(UUID.randomUUID().toString(), evidenceId, request.subjectKind(), request.subjectId(), now);
        }
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_ingested",
                "evidence_file", evidenceId, "kind=" + request.kindCode(), "SUCCESS", "", "");
        return detail(repository.find(evidenceId), decision);
    }

    @Transactional
    public CreatedLinkDto link(String evidenceId, String subjectKind, String subjectId, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_LINK);
        access.require(PermissionCode.EVIDENCE_READ);
        String kind = subject(subjectKind);
        String sid = id(subjectId);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        SubjectRef subject = requireVisibleSubject(kind, sid, decision);
        if (!file.ownerOrgId().equals(subject.ownerOrgId()) || !file.districtId().equals(subject.districtId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
        }
        idempotency.claim(idempotencyKey, "link|" + file.evidenceId() + "|" + kind + "|" + sid);
        if (repository.linkExists(file.evidenceId(), kind, sid)) {
            throw new ApiException(HttpStatus.CONFLICT, "LINK_EXISTS", "该对象已关联此证据");
        }
        String linkId = UUID.randomUUID().toString();
        try {
            repository.insertLink(linkId, file.evidenceId(), kind, sid, clock.now());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "LINK_EXISTS", "该对象已关联此证据");
        }
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_linked",
                "evidence_file", file.evidenceId(), "kind=" + kind, "SUCCESS", "", "");
        return new CreatedLinkDto(linkId, file.evidenceId(), kind, sid);
    }

    @Transactional
    public VerifyDto verify(String evidenceId, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_READ);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        idempotency.claim(idempotencyKey, "verify|" + file.evidenceId() + "|" + file.version());
        if ("DESTROYED".equals(file.status())) {
            repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                    "VERIFY", "DENIED", "DESTROYED", clock.now());
            return new VerifyDto(file.evidenceId(), file.status(), file.sha256(), false);
        }
        boolean exists = storage.exists(file.objectKey());
        String digest = exists ? digest(file.objectKey()) : null;
        boolean matches = exists && file.sha256() != null && file.sha256().equals(digest);
        String status = !exists ? "MISSING" : matches ? "AVAILABLE" : "CORRUPT";
        if (!status.equals(file.status())) {
            int updated = repository.updateStatus(file.evidenceId(), status, clock.now(), file.version());
            if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被其他操作更新");
        }
        repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                "VERIFY", "GRANTED", status, clock.now());
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_verified",
                "evidence_file", file.evidenceId(), "status=" + status, "SUCCESS", "", "");
        return new VerifyDto(file.evidenceId(), status, file.sha256(), matches);
    }

    @Transactional
    public HoldDto hold(String evidenceId, String reason, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_HOLD);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        String text = reason == null ? "" : reason.trim();
        if (text.length() < 1 || text.length() > 500) throw invalid("冻结原因长度须为 1 至 500 字");
        idempotency.claim(idempotencyKey, "hold|" + file.evidenceId() + "|" + text);
        if ("DESTROYED".equals(file.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_DESTROYED", "文件已销毁，仅保留元数据");
        }
        if (repository.hasActiveHold(file.evidenceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_ACTIVE", "该证据已处于冻结中");
        }
        String holdId = UUID.randomUUID().toString();
        Instant now = clock.now();
        repository.insertHold(holdId, file.evidenceId(), decision.userId(), text, now);
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_hold_placed",
                "evidence_file", file.evidenceId(), "hold_id=" + holdId, "SUCCESS", "", "");
        return new HoldDto(holdId, text, decision.userId(), millis(now), null, null);
    }

    @Transactional
    public HoldDto release(String evidenceId, String holdId, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_HOLD);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        String hid = id(holdId);
        HoldRow hold = repository.findHold(hid);
        if (hold == null || !file.evidenceId().equals(hold.evidenceId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "冻结记录不存在");
        }
        idempotency.claim(idempotencyKey, "release|" + hid);
        if (hold.releasedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "HOLD_RELEASED", "该冻结已解除");
        Instant now = clock.now();
        if (repository.releaseHold(hid, decision.userId(), now) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_RELEASED", "该冻结已解除");
        }
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_hold_released",
                "evidence_file", file.evidenceId(), "hold_id=" + hid, "SUCCESS", "", "");
        return new HoldDto(hid, hold.reason(), hold.heldBy(), millis(hold.createdAt()), millis(now), decision.userId());
    }

    @Transactional
    public EvidenceDetailDto destroy(String evidenceId, String reason, String approvalNo, String idempotencyKey) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_DESTROY);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        String text = reason == null ? "" : reason.trim();
        if (text.length() < 1 || text.length() > 500) throw invalid("销毁原因长度须为 1 至 500 字");
        String approval = approvalNo == null || approvalNo.isBlank() ? null : approvalNo.trim();
        if (approval != null && approval.length() > 64) throw invalid("审批号长度须为 1 至 64 字");
        if ("DESTROYED".equals(file.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_DESTROYED", "文件已销毁，仅保留元数据");
        }
        if (repository.hasActiveHold(file.evidenceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_ACTIVE", "冻结中的证据不能销毁");
        }
        Instant until = retainUntilOf(file);
        Instant now = clock.now();
        if (until == null || until.isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "DESTROY_NOT_DUE", "留存期未届满，不能销毁");
        }
        idempotency.claim(idempotencyKey, "destroy|" + file.evidenceId() + "|" + text + "|" + nullToEmpty(approval));
        int updated = repository.markDestroyed(file.evidenceId(), decision.userId(), text, approval, now, file.version());
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被其他操作更新");
        storage.deleteIfPresent(file.objectKey());
        repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                "DESTROY", "GRANTED", null, now);
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_destroyed",
                "evidence_file", file.evidenceId(), "reason=" + text, "SUCCESS", "", "");
        return detail(repository.find(file.evidenceId()), decision);
    }

    @Transactional
    public Download openDownload(String evidenceId) {
        AccessDecision decision = access.require(PermissionCode.EVIDENCE_DOWNLOAD);
        FileRow file = visible(id(evidenceId), decision, probe(PermissionCode.EVIDENCE_INGEST));
        if ("PENDING".equals(file.status())) {
            deny(file, decision, "NOT_READY");
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_NOT_READY", "文件尚未完成入库");
        }
        if ("DESTROYED".equals(file.status())) {
            deny(file, decision, "DESTROYED");
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_DESTROYED", "文件已销毁，仅保留元数据");
        }
        if (!"AVAILABLE".equals(file.status()) || !storage.exists(file.objectKey())) {
            deny(file, decision, "UNAVAILABLE");
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_UNAVAILABLE", "文件缺失或校验失败，不能下载");
        }
        repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                "DOWNLOAD", "GRANTED", null, clock.now());
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "evidence", "evidence_downloaded",
                "evidence_file", file.evidenceId(), file.originalName(), "SUCCESS", "", "");
        InputStream stream = storage.open(file.objectKey())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "EVIDENCE_UNAVAILABLE", "文件缺失或校验失败，不能下载"));
        return new Download(file.originalName(), file.contentType(), file.sizeBytes(), stream);
    }

    private void deny(FileRow file, AccessDecision decision, String reason) {
        repository.insertAccess(UUID.randomUUID().toString(), file.evidenceId(), decision.userId(),
                "DOWNLOAD", "DENIED", reason, clock.now());
    }

    private FileRow visible(String evidenceId, AccessDecision decision, boolean ingest) {
        FileRow file = repository.find(evidenceId);
        if (file == null) throw notFound();
        if (!inScope(file, decision)) throw notFound();
        if (!ingest && repository.linkCount(file.evidenceId()) == 0) throw notFound();
        return file;
    }

    private boolean inScope(FileRow file, AccessDecision decision) {
        if (file.ownerOrgId() == null || file.districtId() == null) return false;
        if (!repository.catalogEnabled(file.ownerOrgId(), file.districtId())) return false;
        if (decision.scopeMode() == com.uav.lowaltitude.modules.identity.domain.ScopeMode.ASSIGNED) {
            try {
                menuAccess.requireTuple(file.ownerOrgId(), file.districtId());
            } catch (ApiException ex) {
                return false;
            }
        }
        return true;
    }

    private SubjectRef requireVisibleSubject(String kind, String subjectId, AccessDecision decision) {
        if (!canSeeKind(kind) || !repository.subjectVisible(kind, subjectId, decision)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
        }
        return repository.findSubject(kind, subjectId);
    }

    /** 案件/授权除数据范围外还要有对应模块读权限，缺权按不存在处理，不泄露编号。 */
    private boolean canSeeKind(String kind) {
        if ("CASE".equals(kind)) return probe(PermissionCode.PUNISHMENT_READ);
        if ("AUTHORIZATION".equals(kind)) return probe(PermissionCode.DISPOSAL_READ);
        return true;
    }

    private EvidenceSummaryDto summary(FileRow row) {
        Instant until = retainUntilOf(row);
        boolean held = repository.hasActiveHold(row.evidenceId());
        return new EvidenceSummaryDto(row.evidenceId(), row.evidenceNo(), row.kindCode(), row.originalName(),
                row.contentType(), row.sizeBytes(), row.status(), millis(row.capturedAt()), millis(row.storedAt()),
                held, repository.linkCount(row.evidenceId()), millis(until),
                EvidenceRetention.policy(row.kindCode()).label(),
                EvidenceRetention.custody(until, clock.now(), held));
    }

    private EvidenceDetailDto detail(FileRow row, AccessDecision decision) {
        List<LinkDto> links = new ArrayList<>();
        for (LinkRow link : repository.links(row.evidenceId())) {
            if (!canSeeKind(link.subjectKind()) || !repository.subjectVisible(link.subjectKind(), link.subjectId(), decision)) continue;
            SubjectRef subject = repository.findSubject(link.subjectKind(), link.subjectId());
            links.add(new LinkDto(link.linkId(), link.subjectKind(), link.subjectId(),
                    subject == null ? null : subject.no()));
        }
        List<HoldDto> holds = repository.holds(row.evidenceId()).stream()
                .map(h -> new HoldDto(h.holdId(), h.reason(), h.heldBy(), millis(h.createdAt()),
                        millis(h.releasedAt()), h.releasedBy()))
                .toList();
        Instant until = retainUntilOf(row);
        boolean held = repository.hasActiveHold(row.evidenceId());
        EvidenceRetention.Policy policy = EvidenceRetention.policy(row.kindCode());
        return new EvidenceDetailDto(row.evidenceId(), row.evidenceNo(), row.kindCode(), row.originalName(),
                row.contentType(), row.sizeBytes(), row.sha256(), row.status(), millis(row.capturedAt()),
                millis(row.storedAt()), millis(until), policy.label(),
                EvidenceRetention.custody(until, clock.now(), held), policy.note(), held,
                row.sourceMode(), row.ownerOrgId(), row.districtId(), row.version(), millis(row.createdAt()),
                millis(row.updatedAt()), List.copyOf(links), holds, millis(row.destroyedAt()), row.destroyedBy(),
                row.destroyReason(), row.destroyApproval());
    }

    private static Instant retainUntilOf(FileRow row) {
        return EvidenceRetention.effectiveUntil(row.retainUntil(), row.kindCode(), row.capturedAt(), row.storedAt());
    }

    private String digest(String objectKey) {
        try (InputStream in = storage.open(objectKey).orElse(null)) {
            if (in == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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

    private static String evidenceNo(Instant now, String id) {
        return "EV-" + now.toString().substring(0, 10).replace("-", "") + "-" + id.substring(0, 8).toUpperCase();
    }

    static String id(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) throw invalid("ID 格式无效");
        return normalized;
    }

    static String subject(String value) {
        String kind = value == null ? "" : value.trim();
        if (!SUBJECTS.contains(kind)) throw invalid("关联对象类型无效");
        return kind;
    }

    private static Long millis(Instant instant) { return instant == null ? null : instant.toEpochMilli(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "证据不存在");
    }

    public record Download(String filename, String contentType, Long sizeBytes, InputStream stream) { }
    public record CsvExport(String filename, String body) { }

    private static final class Request {
        private final MultiValueMap<String, String> values;
        private Request(MultiValueMap<String, String> values, Set<String> allowed) {
            this.values = values;
            values.keySet().stream().filter(key -> !allowed.contains(key)).findFirst()
                    .ifPresent(key -> { throw invalid("参数无效"); });
        }
        static Request list(MultiValueMap<String, String> values) { return new Request(values, LIST_PARAMS); }
        static Request pageOnly(MultiValueMap<String, String> values) { return new Request(values, Set.of("page", "size")); }
        int page() { return integer("page", 1); }
        int size() { return integer("size", 20); }
        int offset() {
            try { return Math.multiplyExact(page() - 1, size()); }
            catch (ArithmeticException ex) { throw invalid("分页参数无效"); }
        }
        String kindCode() { return optionalEnum("kind_code", KINDS); }
        String status() { return optionalEnum("status", STATUSES); }
        String q() { return optional("q", 128); }
        String subjectKind() {
            boolean hasKind = values.containsKey("subject_kind");
            boolean hasId = values.containsKey("subject_id");
            if (hasKind != hasId) throw invalid("subject_kind 与 subject_id 必须成对");
            return hasKind ? subject(single("subject_kind")) : null;
        }
        String subjectId() { return values.containsKey("subject_id") ? id(single("subject_id")) : null; }
        FileQuery query() { return new FileQuery(kindCode(), status(), subjectKind(), subjectId(), q(), optionalEnum("custody", CUSTODIES)); }
        private int integer(String name, int fallback) {
            if (!values.containsKey(name)) return fallback;
            try {
                int value = Integer.parseInt(single(name));
                if ("page".equals(name) && value < 1) throw invalid("分页参数无效");
                if ("size".equals(name) && (value < 1 || value > 100)) throw invalid("分页参数无效");
                return value;
            } catch (NumberFormatException ex) { throw invalid("分页参数无效"); }
        }
        private String optional(String name, int max) {
            if (!values.containsKey(name)) return null;
            String value = single(name);
            if (value.length() > max) throw invalid(name + " 参数无效");
            return value;
        }
        private String optionalEnum(String name, Set<String> allowed) {
            if (!values.containsKey(name)) return null;
            String value = single(name);
            if (!allowed.contains(value)) throw invalid(name + " 参数无效");
            return value;
        }
        private String single(String name) {
            List<String> found = values.get(name);
            if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) {
                throw invalid(name + " 参数无效");
            }
            return found.get(0).trim();
        }
    }

    private static final class IngestRequest {
        private final String kindCode, originalName, storedName, contentType, sourceMode, ownerOrgId, districtId,
                subjectKind, subjectId;
        private final Instant capturedAt;
        private final long size;
        private IngestRequest(String kindCode, String originalName, String storedName, String contentType,
                String sourceMode, String ownerOrgId, String districtId, String subjectKind, String subjectId,
                Instant capturedAt, long size) {
            this.kindCode = kindCode; this.originalName = originalName; this.storedName = storedName;
            this.contentType = contentType; this.sourceMode = sourceMode; this.ownerOrgId = ownerOrgId;
            this.districtId = districtId; this.subjectKind = subjectKind; this.subjectId = subjectId;
            this.capturedAt = capturedAt; this.size = size;
        }
        static IngestRequest parse(MultipartFile file, MultiValueMap<String, String> form, String defaultMode, Instant now) {
            if (file == null || file.isEmpty()) throw invalid("必须上传文件");
            if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "文件超过 32 MiB");
            Set<String> allowed = Set.of("kind_code", "owner_org_id", "district_id", "captured_at",
                    "subject_kind", "subject_id", "source_mode");
            form.keySet().stream().filter(key -> !allowed.contains(key) && !"file".equals(key)).findFirst()
                    .ifPresent(key -> { throw invalid("参数无效"); });
            String kind = one(form, "kind_code", true);
            if (!KINDS.contains(kind)) throw invalid("kind_code 参数无效");
            String name = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename().trim();
            if (name.isEmpty() || name.length() > 256) throw invalid("文件名无效");
            if (name.contains("..") || name.contains("/") || name.contains("\\")) throw invalid("文件名无效");
            String contentType = file.getContentType() == null || file.getContentType().isBlank()
                    ? "application/octet-stream" : file.getContentType();
            if (contentType.length() > 128) throw invalid("content_type 无效");
            String mode = one(form, "source_mode", false);
            if (mode == null) mode = defaultMode;
            if (!MODES.contains(mode)) throw invalid("source_mode 参数无效");
            String org = one(form, "owner_org_id", false);
            String district = one(form, "district_id", false);
            if ((org == null) != (district == null)) throw invalid("组织与区域必须成对");
            String subjectKind = one(form, "subject_kind", false);
            String subjectId = one(form, "subject_id", false);
            if ((subjectKind == null) != (subjectId == null)) throw invalid("subject_kind 与 subject_id 必须成对");
            if (subjectKind != null) {
                subjectKind = subject(subjectKind);
                subjectId = id(subjectId);
            }
            Instant captured = now;
            String capturedRaw = one(form, "captured_at", false);
            if (capturedRaw != null) {
                try { captured = Instant.ofEpochMilli(Long.parseLong(capturedRaw)); }
                catch (RuntimeException ex) { throw invalid("captured_at 参数无效"); }
                if (captured.isAfter(now)) throw invalid("取证时刻不能晚于当前时间");
            }
            String stored = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (stored.isBlank()) stored = "file.bin";
            return new IngestRequest(kind, name, stored, contentType, mode, org, district, subjectKind, subjectId,
                    captured, file.getSize());
        }
        private static String one(MultiValueMap<String, String> form, String name, boolean required) {
            if (!form.containsKey(name)) {
                if (required) throw invalid(name + " 参数无效");
                return null;
            }
            List<String> found = form.get(name);
            if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) {
                throw invalid(name + " 参数无效");
            }
            return found.get(0).trim();
        }
        String kindCode() { return kindCode; }
        String originalName() { return originalName; }
        String storedName() { return storedName; }
        String contentType() { return contentType; }
        String sourceMode() { return sourceMode; }
        String ownerOrgId() { return ownerOrgId; }
        String districtId() { return districtId; }
        String subjectKind() { return subjectKind; }
        String subjectId() { return subjectId; }
        Instant capturedAt() { return capturedAt; }
        long size() { return size; }
    }
}
