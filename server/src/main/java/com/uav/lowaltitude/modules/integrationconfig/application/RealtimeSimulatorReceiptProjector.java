package com.uav.lowaltitude.modules.integrationconfig.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsService;
import com.uav.lowaltitude.modules.alarm.application.AutoVoiceService;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.handoff.application.HandoffSimulatorReceiptService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightVerificationRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

/** Projects authenticated external simulator facts inside the transport receipt transaction. */
@Service
public class RealtimeSimulatorReceiptProjector {
    private final ObjectProvider<AutoSmsService> sms;
    private final ObjectProvider<AutoVoiceService> voice;
    private final HandoffSimulatorReceiptService handoffs;
    private final FlightVerificationRepository feedback;
    private final DeviceMaintenanceNoticeRepository maintenance;
    private final ObjectMapper json;
    private final AuditService audit;
    public RealtimeSimulatorReceiptProjector(ObjectProvider<AutoSmsService> sms,ObjectProvider<AutoVoiceService> voice,
            HandoffSimulatorReceiptService handoffs,FlightVerificationRepository feedback,
            DeviceMaintenanceNoticeRepository maintenance,ObjectMapper json,AuditService audit){
        this.sms=sms;this.voice=voice;this.handoffs=handoffs;this.feedback=feedback;this.maintenance=maintenance;this.json=json;this.audit=audit;
    }
    public boolean apply(String id,String kind,String subjectId,JsonNode payload,String outcome,Long deliveredAt,Long answeredAt,Long completedAt,Long acknowledgedAt){
        return apply(id,kind,subjectId,payload,outcome,deliveredAt,answeredAt,completedAt,acknowledgedAt,null);
    }
    public boolean apply(String id,String kind,String subjectId,JsonNode payload,String outcome,Long deliveredAt,Long answeredAt,Long completedAt,Long acknowledgedAt,String receiptResult){
        String marker="SIMULATOR_WAITING:"+id;
        boolean applied;
        if("ADVISORY_SMS".equals(kind)){
            applied=sms.getObject().completeSimulatorReceipt(subjectId,text(payload,"provider_key"),text(payload,"claim_token"),
                    value(payload,"recipient",RecipientSnapshot.class),outcome,deliveredAt,payload.path("requested_at").asLong(-1));
        }else if("ADVISORY_VOICE".equals(kind)){
            applied=voice.getObject().completeSimulatorReceipt(subjectId,text(payload,"provider_key"),text(payload,"claim_token"),
                    value(payload,"recipient",RecipientSnapshot.class),value(payload,"recording",Recording.class),id,outcome,answeredAt,completedAt,payload.path("requested_at").asLong(-1));
        }else{
            DeliveryOutcome receipt=delivery(marker,outcome,deliveredAt,acknowledgedAt,receiptResult);
            if(!subjectId.equals(text(payload,"handoff_id")))applied=false;
            else applied=switch(kind){
                case "RISK_NOTICE","UAV_PUNISHMENT" -> handoffs.complete(subjectId,kind,text(payload,"source_id"),value(payload,"at",OffsetDateTime.class),marker,receipt);
                case "PLAN_FEEDBACK" -> feedback.completeSimulatorReceipt(subjectId,marker,receipt);
                case "DEVICE_MAINTENANCE" -> maintenance.completeSimulatorReceipt(subjectId,text(payload,"source_id"),marker,receipt,
                        "TIMEOUT".equals(outcome)?"UNKNOWN":Set.of("DELIVERED","ACKNOWLEDGED","FAILED").contains(outcome)?"COMPLETED":"SUBMITTED");
                default -> throw new IllegalArgumentException("Unsupported simulator message kind");
            };
        }
        var actor=AuthContext.require();
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"integration","simulator_receipt_projection","simulator_message",id,
                "kind="+kind+"; outcome="+outcome+"; receipt_result="+receiptResult+"; projection="+(applied?"APPLIED":"IGNORED_STALE_ATTEMPT"),"SUCCESS","","");
        return applied;
    }
    public String processingResult(String kind,String subjectId){
        return "RISK_NOTICE".equals(kind)?handoffs.processingResult(subjectId):null;
    }
    private static DeliveryOutcome delivery(String marker,String outcome,Long delivered,Long acknowledged,String receiptResult){
        if(!Set.of("DELIVERED","ACKNOWLEDGED","FAILED","TIMEOUT").contains(outcome))throw new IllegalArgumentException("Unsupported notification receipt");
        return new DeliveryOutcome("FAILED".equals(outcome)?"FAILED":delivered!=null?"DELIVERED":"SUBMITTED",
                "ACKNOWLEDGED".equals(outcome)?"ACKNOWLEDGED":"FAILED".equals(outcome)?"NOT_EXPECTED":"TIMEOUT".equals(outcome)?"TIMEOUT":"PENDING",
                receiptResult,"ACKNOWLEDGED".equals(outcome)?null:"FAILED".equals(outcome)?"SIMULATOR_REJECTED":marker,null,time(delivered),time(acknowledged));
    }
    private <T>T value(JsonNode payload,String field,Class<T> type){var value=payload.get(field);return value==null||value.isNull()?null:json.convertValue(value,type);}
    private static String text(JsonNode payload,String field){var value=payload.get(field);return value==null||!value.isTextual()?null:value.asText();}
    private static OffsetDateTime time(Long epoch){return epoch==null?null:Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.UTC);}
}
