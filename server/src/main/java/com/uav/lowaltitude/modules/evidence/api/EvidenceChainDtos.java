package com.uav.lowaltitude.modules.evidence.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class EvidenceChainDtos {
    private EvidenceChainDtos() { }

    public record ChainDto(String subjectKind, String subjectId, String subjectNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) String currentTargetId,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> historicalTargetIds,
            Map<String, CoverageDto> coverage, IntegrityDto integrity, List<RecordDto> records,
            LineageSectionDto lineage) { }

    public record CoverageDto(String status, int count,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer brokenCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean truncated) { }

    public record IntegrityDto(String algorithm, String checksum, int memberCount, long computedAt) { }

    public record RecordDto(String recordType, String recordId, Long occurredAt, String fingerprint,
            String availability, Object summary) { }

    public record TrackSummary(String layer, Long startedAt, Long endedAt, int pointCount) { }

    public record FileSummary(String evidenceNo, String kindCode, String originalName, String status,
            String sha256, Long sizeBytes) { }

    public record AlarmSummary(String severity, String alarmType, String sourceMode, Long receivedAt) { }

    public record JudgmentSummary(String targetId, String conclusionCode, Long assessedAt, String ruleVersionId) { }

    public record AuthorizationSummary(String commandNo, String authorizationId, String status) { }

    public record VerificationSummary(String conclusion, long version, String resultingState) { }

    public record HandoffSummary(String handoffType, String deliveryStatus, long sourceVersion) { }

    public record OperationSummary(String action, String result, String moduleCode) { }

    public record LineageSectionDto(String availability,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<LineageOpDto> ops,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<PreMergeJudgmentDto> preMergeJudgments) { }

    public record LineageOpDto(String lineageId, String op, long occurredAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String survivorTargetId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String originTargetId,
            List<String> memberTargetIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object snapshots) { }

    public record PreMergeJudgmentDto(String memberTargetId, String lineageId, String judgmentAvailability,
            @JsonInclude(JsonInclude.Include.NON_NULL) String assessmentId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String conclusionCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long assessedAt) { }
}
