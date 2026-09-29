package com.uav.lowaltitude.modules.evidence.api;

import java.util.List;
import java.util.Map;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.CountDto;

/** Read models for existing files, observed tracks and actual device commands. */
public final class EvidenceLedgerDtos {
    private EvidenceLedgerDtos() { }
    public record Entry(String sourceKind, String sourceId, String category, String evidenceNo,
            String originalName, String kindCode, String status, Long occurredAt, Long sizeBytes,
            Long retainUntil, String custody, String sourceMode, String layer, Long startedAt,
            Long endedAt, Long pointCount) { }
    public record Link(String subjectKind, String subjectId, String subjectNo) { }
    public record Detail(Entry entry, Object command, List<Entry> attachments, List<Link> links) { }
    public record Stats(long total, List<CountDto> byKind, List<CountDto> byStatus, List<CountDto> byCustody) { }
    public record Coverage(String status, long count, boolean truncated) { }
    public record Material(String recordType, String recordId, Long occurredAt, String availability, Entry summary) { }
    public record Materials(String subjectKind, String subjectId, Map<String, Coverage> coverage, List<Material> records) { }
}
