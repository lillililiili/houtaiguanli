package com.uav.lowaltitude.modules.evidence.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class EvidenceDtos {
    private EvidenceDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    public record EvidenceSummaryDto(String evidenceId, String evidenceNo, String kindCode, String originalName,
            String contentType, Long sizeBytes, String status, Long capturedAt, Long storedAt, boolean held,
            int linkCount, @JsonInclude(JsonInclude.Include.NON_NULL) Long retainUntil, String retainLabel,
            String custody) { }

    public record EvidenceDetailDto(String evidenceId, String evidenceNo, String kindCode, String originalName,
            String contentType, Long sizeBytes, String sha256, String status, Long capturedAt, Long storedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long retainUntil, String retainLabel, String custody,
            String retainNote, boolean held, String sourceMode, String ownerOrgId, String districtId, long version,
            long createdAt, long updatedAt, List<LinkDto> links, List<HoldDto> holds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long destroyedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String destroyedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String destroyReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String destroyApproval) { }

    public record LinkDto(String linkId, String subjectKind, String subjectId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String subjectNo) { }

    public record HoldDto(String holdId, String reason, String heldBy, long createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long releasedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String releasedBy) { }

    public record CreatedLinkDto(String linkId, String evidenceId, String subjectKind, String subjectId) { }

    public record VerifyDto(String evidenceId, String status, String sha256, boolean matches) { }

    public record HoldRequest(String reason) { }

    public record DestroyRequest(String reason, String approvalNo) { }

    public record LinkRequest(String subjectKind, String subjectId) { }

    public record AccessLogDto(String accessId, String action, String result,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode, long createdAt, String userId) { }
}
