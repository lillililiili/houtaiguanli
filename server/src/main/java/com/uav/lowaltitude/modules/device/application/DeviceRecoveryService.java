package com.uav.lowaltitude.modules.device.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The single evidence predicate for device incidents and maintenance cases. No user/session dependency. */
@Service
public class DeviceRecoveryService {
    public static final String RULE_VERSION="ops-device-recovery-v2-auto-track";
    private final DeviceRepository repository;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final long freshnessMillis;
    private final long trackingReportMaxAgeMillis;

    public DeviceRecoveryService(DeviceRepository repository,AppClock clock,AuditService audit,ObjectMapper json,
            @Value("${app.device-maintenance.recovery-max-age-seconds:300}") long freshnessSeconds,
            @Value("${app.eo-edge.position-max-age-millis:15000}") long trackingReportMaxAgeMillis) {
        if(freshnessSeconds<1)throw new IllegalArgumentException("Recovery evidence freshness must be positive");
        if(trackingReportMaxAgeMillis<1)throw new IllegalArgumentException("Tracking report freshness must be positive");
        this.repository=repository;this.clock=clock;this.audit=audit;this.json=json;
        this.freshnessMillis=freshnessSeconds*1000;
        this.trackingReportMaxAgeMillis=trackingReportMaxAgeMillis;
    }

    /** Read-only. Transactional callers hold catalog/state locks before using the result to mutate workflow. */
    public Evaluation evaluate(String deviceId,String sourceMode,String sourceId,boolean simulated,long sinceMillis) {
        long now=clock.nowMillis();
        Map<String,Object> device=repository.findForRecovery(deviceId);
        return evaluateFacts(device,repository.openIncidentsForRecovery(deviceId,false),
                repository.hasIncidentSourceConflict(deviceId,sourceMode,sourceId,simulated),
                repository.hasActiveWork(deviceId),repository.hasRecoveryBlockingWork(deviceId,now-trackingReportMaxAgeMillis,now),
                sourceMode,sourceId,simulated,sinceMillis,now);
    }

    /** Transaction-local facts only: never cache across device transactions or scheduler ticks. */
    public Facts capture(String deviceId) {
        long now=clock.nowMillis();
        return new Facts(repository.findForRecovery(deviceId),repository.openIncidentsForRecovery(deviceId,false),
                repository.incidentRecoverySources(deviceId),repository.hasActiveWork(deviceId),
                repository.hasRecoveryBlockingWork(deviceId,now-trackingReportMaxAgeMillis,now));
    }

    /** Each case keeps its own frozen source and anomaly boundary; only the device reads are shared. */
    public Evaluation evaluate(Facts facts,String sourceMode,String sourceId,boolean simulated,long sinceMillis) {
        boolean conflict=facts.incidentSources().stream().anyMatch(source->
                !Objects.equals(sourceId,source.get("source_id"))||!Objects.equals(sourceMode,source.get("source_mode"))
                        ||!Objects.equals(simulated,source.get("simulated")));
        return evaluateFacts(facts.device(),facts.incidents(),conflict,facts.activeWork(),facts.blockingWork(),sourceMode,sourceId,
                simulated,sinceMillis,clock.nowMillis());
    }

    private Evaluation evaluateFacts(Map<String,Object> device,List<Map<String,Object>> openIncidents,
            boolean incidentSourceConflict,boolean active,boolean blockingWork,String sourceMode,String sourceId,boolean simulated,
            long sinceMillis,long now) {
        if(device==null)return new Evaluation("UNKNOWN","设备不存在或已删除",List.of("DEVICE_UNAVAILABLE"),Map.of(),now,RULE_VERSION);
        var codes=new ArrayList<String>();var reasons=new ArrayList<String>();
        if(!Boolean.TRUE.equals(device.get("enabled")))add(codes,reasons,"DEVICE_DISABLED","设备已停用");
        if(!Objects.equals(sourceMode,device.get("source_mode"))||!Objects.equals(sourceId,device.get("source_id"))
                ||!Objects.equals(simulated,device.get("simulated")))
            add(codes,reasons,"SOURCE_CHANGED","设备来源已变化，不能用其他来源证明本次恢复");
        if(Boolean.FALSE.equals(device.get("source_enabled")))add(codes,reasons,"SOURCE_DISABLED","设备接入来源已停用");
        if(incidentSourceConflict)
            add(codes,reasons,"INCIDENT_SOURCE_MISMATCH","存在其他来源处置单的未恢复异常，不能使用本次来源证据关闭");
        long since=sinceMillis;
        for(var incident:openIncidents) {
            if(!Objects.equals(simulated,incident.get("simulated"))) {
                add(codes,reasons,"INCIDENT_SOURCE_MISMATCH","存在来源不一致的未恢复异常，需要核查原始记录");
            } else {
                Long at=number(incident,"detected_at");
                if(at!=null)since=Math.max(since,at);
            }
        }
        String connection=text(device,"connectivity");
        if(connection==null||"UNKNOWN".equals(connection))
            add(codes,reasons,"CONNECTIVITY_UNKNOWN","设备连接状态未知");
        else if(!"ONLINE".equals(connection))add(codes,reasons,"CONNECTIVITY_NOT_ONLINE",
                "OFFLINE".equals(connection)?"设备仍离线":"设备连接状态尚未正常");
        long heartbeatWindow=Boolean.TRUE.equals(device.get("mqtt_heartbeat"))
                ?Math.min(freshnessMillis,30_000):freshnessMillis;
        checkTime(device,"last_heartbeat_at",since,now,heartbeatWindow,"HEARTBEAT",codes,reasons,"在线心跳");
        checkTime(device,"health_observed_at",since,now,freshnessMillis,"HEALTH",codes,reasons,"健康上报");
        checkTime(device,"alarm_observed_at",since,now,freshnessMillis,"ALARM",codes,reasons,"告警状态上报");
        if(device.get("health_code")==null||"UNKNOWN".equals(device.get("health_code")))
            add(codes,reasons,"HEALTH_UNKNOWN","设备健康状态未知");
        else if(!"GOOD".equals(device.get("health_code")))add(codes,reasons,"HEALTH_NOT_GOOD","设备仍有健康异常");
        if(device.get("has_alarm")==null)add(codes,reasons,"ALARM_UNKNOWN","尚未取得明确的无告警状态");
        else if(!Boolean.FALSE.equals(device.get("has_alarm")))add(codes,reasons,"ALARM_NOT_CLEARED","设备仍有告警");
        checkSource(device,"health",sourceMode,sourceId,simulated,codes,reasons,"健康上报");
        checkSource(device,"alarm",sourceMode,sourceId,simulated,codes,reasons,"告警状态上报");
        if(blockingWork)add(codes,reasons,"ACTIVE_DEVICE_WORK","仍有影响恢复的设备操作、异常追踪或停止待确认，请核对操作结果");
        Map<String,Object> current=new LinkedHashMap<>();
        for(String key:List.of("device_id","enabled","connectivity","health_code","has_alarm","source_id","source_mode","simulated",
                "observed_at","received_at","last_heartbeat_at","health_observed_at","health_source_id","health_source_mode","health_simulated",
                "alarm_observed_at","alarm_source_id","alarm_source_mode","alarm_simulated","state_version"))current.put(key,device.get(key));
        current.put("active_work",active);current.put("recovery_blocking_work",blockingWork);current.put("evidence_after",since);
        boolean unknown=codes.stream().anyMatch(c->c.endsWith("_MISSING")||c.endsWith("_UNKNOWN")||c.endsWith("_STALE")
                ||c.endsWith("_FUTURE")||c.endsWith("_BEFORE_ANOMALY")||c.equals("SOURCE_CHANGED")||c.endsWith("SOURCE_MISMATCH"));
        String result=codes.isEmpty()?"PASS":unknown?"UNKNOWN":"FAIL";
        return new Evaluation(result,codes.isEmpty()?"设备已恢复：在线、健康正常、告警清除且无阻碍恢复的操作":String.join("；",reasons),
                List.copyOf(codes),Collections.unmodifiableMap(current),now,RULE_VERSION);
    }

    /** Caller must hold catalog/state locks. Closing device incidents is distinct from human case completion. */
    @Transactional(propagation=Propagation.MANDATORY)
    public List<String> recoverIncidents(String deviceId,String sourceMode,String sourceId,boolean simulated,long sinceMillis) {
        Evaluation check=evaluate(deviceId,sourceMode,sourceId,simulated,sinceMillis);
        if(!check.passed())throw new ApiException(HttpStatus.CONFLICT,"MAINTENANCE_RECOVERY_BLOCKED",check.reason());
        List<String> recovered=new ArrayList<>();
        for(var incident:repository.openIncidentsForRecovery(deviceId,true)) {
            String id=text(incident,"incident_id");
            if(repository.closeIncident(id,text(incident,"stage"),check.checkedAt())!=1)
                throw new IllegalStateException("DEVICE_INCIDENT_CHANGED_DURING_RECOVERY");
            repository.insertSystemRecoveryCheck(UUID.randomUUID().toString(),id,check.checkedAt(),RULE_VERSION,
                    encode(check.currentState()),check.reason());
            audit.record(null,"SYSTEM","device_incident_auto_recovered","device_incident",id,check.reason(),null);
            recovered.add(id);
        }
        return List.copyOf(recovered);
    }

    private static void checkTime(Map<String,Object> state,String key,long since,long now,long window,String prefix,
                                  List<String> codes,List<String> reasons,String label) {
        Long at=number(state,key);
        if(at==null)add(codes,reasons,prefix+"_MISSING","缺少新的"+label);
        else if(at>now)add(codes,reasons,prefix+"_FUTURE",label+"时间晚于当前时间，待核查");
        else if(at<=since)add(codes,reasons,prefix+"_BEFORE_ANOMALY","尚未收到本次异常之后的"+label);
        else if(now-at>window)add(codes,reasons,prefix+"_STALE",label+"已过期，等待设备重新上报");
    }
    private static void checkSource(Map<String,Object> state,String prefix,String mode,String source,boolean simulated,
                                    List<String> codes,List<String> reasons,String label) {
        if(!Objects.equals(source,state.get(prefix+"_source_id"))||!Objects.equals(mode,state.get(prefix+"_source_mode"))
                ||!Objects.equals(simulated,state.get(prefix+"_simulated")))
            add(codes,reasons,prefix.toUpperCase()+"_SOURCE_MISMATCH",label+"缺少同源的明确证据");
    }
    private static void add(List<String> codes,List<String> reasons,String code,String reason) {
        if(!codes.contains(code)){codes.add(code);reasons.add(reason);}
    }
    private static Long number(Map<String,Object> row,String key){return row.get(key) instanceof Number value?value.longValue():null;}
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?null:String.valueOf(row.get(key));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode recovery evidence",e);}}
    public record Evaluation(String result,String reason,List<String> reasonCodes,Map<String,Object> currentState,
                             long checkedAt,String ruleVersion) {
        public boolean passed(){return "PASS".equals(result);}
    }
    public record Facts(Map<String,Object> device,List<Map<String,Object>> incidents,
                        List<Map<String,Object>> incidentSources,boolean activeWork,boolean blockingWork) { }
}
