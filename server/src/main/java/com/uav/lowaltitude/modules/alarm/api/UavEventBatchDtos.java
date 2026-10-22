package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;

public final class UavEventBatchDtos {
    private UavEventBatchDtos() { }

    public record ErrorDto(String code, String message) { }
    public record CounterOptions(boolean canRequest, boolean canDirect, String blockReason) { }
    public record PrecheckItem(String eventId, String status, AlarmDtos.UavEventDto event,
            List<String> allowedConclusions, String verificationBlockReason, CounterOptions counter, ErrorDto error) { }
    public record PrecheckResult(List<PrecheckItem> items) { }
    /** SUCCEEDED means the existing operation accepted; device completion still requires its receipt. */
    public record ItemResult(String eventId, String status, Object data, ErrorDto error) { }
    public record BatchResult(List<ItemResult> items, long succeeded, long failed, long unknown) { }
}
