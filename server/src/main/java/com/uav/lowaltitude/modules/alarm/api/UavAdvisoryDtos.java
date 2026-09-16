package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;

public final class UavAdvisoryDtos {
    private UavAdvisoryDtos() { }
    public record Record(String recordId, String kind, long createdAt, String actorName, String recipientName,
            String contactBasis, String content, String outcome, String danger, String note, boolean urgent,
            boolean simulated, String deliveryStatus) { }
    public record Recipient(String name, String contactHint, String basis) { }
    public record Overview(String eventId, long eventVersion, String smsMode, boolean canWrite,
            boolean canRequestCounter, boolean canHandoff, String counterBlockReason, List<Record> records, Recipient recipient) { }
    public record Action(Long expectedVersion, String kind, String recipientName, String contactBasis, String content,
            String outcome, String danger, String note, Boolean urgent) { }
}
