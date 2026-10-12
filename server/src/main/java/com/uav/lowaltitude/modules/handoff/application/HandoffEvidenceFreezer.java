package com.uav.lowaltitude.modules.handoff.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Command;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Receipt;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceLedgerRepository.LedgerRow;
import com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler.EvidenceScope;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackPointDto;
import com.uav.lowaltitude.platform.api.ApiException;

/** 只消费已经按主体和权限筛选的台账条目；把可回放的点和指令回执冻结进同一材料。 */
@Component
public class HandoffEvidenceFreezer {
    private static final int MAX_POINTS = 50000;
    private final TargetReadRepository tracks;
    private final DeviceRepository devices;
    private final ObjectMapper json;
    public HandoffEvidenceFreezer(TargetReadRepository tracks, DeviceRepository devices, ObjectMapper json) {
        this.tracks=tracks; this.devices=devices; this.json=json;
    }
    public JsonNode content(LedgerRow row, EvidenceScope scope, long at) {
        if ("TRACK".equals(row.sourceKind())) {
            var points=tracks.listPoints(row.sourceId(), new TargetReadRepository.TimeQuery(null,
                    Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC), null), scope.decision(), 0, MAX_POINTS+1);
            if (points.size()>MAX_POINTS) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "HANDOFF_EVIDENCE_TOO_LARGE", "关联轨迹超过材料容量，未提交；请先分段归档后重试");
            var frozen=points.stream().map(p -> new TrackPointDto(p.pointId(),p.trackId(),p.pointSeq(),
                    (p.observedAt()==null?p.receivedAt():p.observedAt()).toInstant().toEpochMilli(),
                    p.observedAt()==null?"RECEIVED":"OBSERVED",p.receivedAt().toInstant().toEpochMilli(),
                    p.observedAt()==null?null:p.observedAt().toInstant().toEpochMilli(),
                    p.location()==null?null:new LocationDto(p.location().longitude(),p.location().latitude(),"WGS84"),
                    p.altitudeAmslM(),p.heightAglM(),p.pointKind(),p.positionAccuracyM(),null,p.sourceSwitched(),p.degradationLevel())).toList();
            return json.valueToTree(Map.of("points",frozen,"frozen_at",at,"source_mode",row.sourceMode()));
        }
        if (!"COMMAND".equals(row.sourceKind())) return null;
        Map<String,Object> c=devices.findCommand(row.sourceId());
        if (c==null) throw new ApiException(HttpStatus.CONFLICT,"HANDOFF_EVIDENCE_MISSING","关联指令已不可读取，未提交材料");
        List<Receipt> receipts=devices.commandReceipts(row.sourceId()).stream().map(r -> new Receipt(
                text(r,"receipt_id"),text(r,"receipt_kind"),text(r,"device_result_code"),number(r,"occurred_at"),number(r,"received_at"),payload(r.get("payload")))).toList();
        return json.valueToTree(new Command(row.sourceId(),text(c,"command_no"),text(c,"device_name"),text(c,"device_no"),
                text(c,"command_type"),text(c,"reason"),text(c,"status"),number(c,"created_at"),number(c,"issued_at"),number(c,"completed_at"),
                text(c,"result_detail"),receipts,Boolean.TRUE.equals(c.get("simulated"))));
    }
    private JsonNode payload(Object value) {
        if(value==null)return null;
        try { return json.readTree(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString()); }
        catch(java.io.IOException ex) { throw new ApiException(HttpStatus.CONFLICT,"INVALID_RECEIPT_PAYLOAD","指令回执无法解析，未提交材料"); }
    }
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
    private static Long number(Map<String,Object> row,String key){return row.get(key)==null?null:((Number)row.get(key)).longValue();}
}
