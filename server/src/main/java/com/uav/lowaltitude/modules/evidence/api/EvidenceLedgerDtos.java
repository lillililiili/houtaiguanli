package com.uav.lowaltitude.modules.evidence.api;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

public final class EvidenceLedgerDtos {
    private EvidenceLedgerDtos() { }
    public record Entry(String sourceKind, String sourceId, String category, String evidenceNo,
            String originalName, String kindCode, String status, Long capturedAt, Long storedAt,
            String sourceMode, String layer, Long startedAt, Long endedAt, Long sizeBytes,
            boolean held, String custody, int linkCount, Long retainUntil, Long pointCount) {
        @com.fasterxml.jackson.annotation.JsonProperty("occurred_at")
        public Long occurredAt() { return capturedAt == null ? storedAt : capturedAt; }
    }
    public record Detail(Entry entry, List<EvidenceDtos.LinkDto> links,
            Command command, List<Entry> attachments) { }
    public record Command(String commandId, String commandNo, String deviceName, String deviceNo,
            String commandType, String reason, String status, Long createdAt, Long issuedAt,
            Long completedAt, String resultDetail, List<Receipt> receipts) { }
    public record Receipt(String receiptId, String receiptKind, String deviceResultCode,
            Long occurredAt, Long receivedAt, JsonNode payload) { }
    public record Stats(long total, List<EvidenceDtos.CountDto> byKind,
            List<EvidenceDtos.CountDto> byStatus, List<EvidenceDtos.CountDto> byCustody) { }
}
