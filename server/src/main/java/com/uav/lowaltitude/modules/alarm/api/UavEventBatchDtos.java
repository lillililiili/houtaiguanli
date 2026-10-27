package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;

public final class UavEventBatchDtos {
    private UavEventBatchDtos() { }

    public record ErrorDto(String code, String message) { }
    public record CounterOptions(boolean canRequest, boolean canDirect, String blockReason) { }
    public record PrecheckItem(String eventId, String riskCaseId, Long caseVersion, String predictionId, String status, AlarmDtos.UavEventDto event,
            List<String> allowedConclusions, String verificationBlockReason, CounterOptions counter, ErrorDto error) {
        public PrecheckItem(String eventId,String status,AlarmDtos.UavEventDto event,List<String> allowedConclusions,String verificationBlockReason,CounterOptions counter,ErrorDto error){this(eventId,null,null,null,status,event,allowedConclusions,verificationBlockReason,counter,error);}
    }
    public record PrecheckResult(List<PrecheckItem> items) { }
    /** SUCCEEDED means the existing operation accepted; device completion still requires its receipt. */
    public record ItemResult(String eventId, String riskCaseId, String status, Object data, ErrorDto error) {
        public ItemResult(String eventId,String status,Object data,ErrorDto error){this(eventId,null,status,data,error);}
    }
    public record BatchResult(List<ItemResult> items, long succeeded, long failed, long unknown) { }
}
