package com.uav.lowaltitude.modules.flight.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.flight.infrastructure.*;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.Verification;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class FlightScheduledCheckService {
    private final FlightReadRepository plans;
    private final FlightVerificationRepository records;
    private final FlightCheckScheduleRepository schedules;
    private final FlightDeviceCheckService checks;
    private final DeviceMaintenanceService maintenance;
    private final AppClock clock;
    private final ObjectMapper json;
    private final AuditService audit;
    public FlightScheduledCheckService(FlightReadRepository plans,FlightVerificationRepository records,FlightCheckScheduleRepository schedules,
            FlightDeviceCheckService checks,DeviceMaintenanceService maintenance,AppClock clock,ObjectMapper json,AuditService audit) {
        this.plans=plans;this.records=records;this.schedules=schedules;this.checks=checks;this.maintenance=maintenance;this.clock=clock;this.json=json;this.audit=audit;
    }
    @Transactional
    public void check(String id) {
        records.lockPlan(id);
        var plan=plans.findPlan(id,new AccessDecision(null,ScopeMode.ALL));long now=clock.nowMillis();
        if(!schedules.eligible(plan,now))return;
        String scope=hash(Arrays.asList(plan.startAt(),plan.endAt(),plan.routeVersionId(),plan.uavSn(),plan.sourceId(),plan.sourceMode(),plan.ownerOrgId(),plan.districtId()));
        schedules.ensure(plan,scope);var prior=schedules.state(id);
        boolean same=scope.equals(prior.scope());
        if(same && ("MATCHED".equals(prior.state()) || prior.next()>now)){schedules.version(plan);return;}
        if(schedules.matched(plan)) {
            schedules.stopMatched(plan,scope);return;
        }
        var check=checks.scheduled(plan);
        var facts=check.rows().stream().sorted(Comparator.comparing(FlightDeviceCheckService.DeviceRow::deviceId))
            .map(r->Arrays.asList(r.deviceId(),r.connectivity(),r.healthCode(),r.abnormal(),r.complete(),
                r.incidents().stream().filter(i->i.closedAt()==null).map(i->i.incidentId()).sorted().toList())).toList();
        String fingerprint=hash(Arrays.asList(check.conclusion(),check.complete(),check.selectionBasis(),check.uncheckedLocations(),check.uncheckedCoverage(),facts));
        String verification=same?prior.verification():null;
        if(!same || !fingerprint.equals(prior.fingerprint())) {
            var history=records.verifications(id);long revision=history.isEmpty()?1:history.get(0).revisionNo()+1;
            verification=UUID.randomUUID().toString();
            String evidence="系统自动检查；按设备扫描范围与航线相交检查；"+check.message();
            records.insert(new Verification(verification,id,revision,check.conclusion(),"UNKNOWN",evidence,check.message(),null,"系统自动检查",now));
            audit.record(null,"flight-device-check",null,"flight","plan_device_checked","flight_verification",verification,"系统自动检查；plan_id="+id,"SUCCESS","","");
        }
        for(var row:check.rows()) {
            var fault=schedules.fault(id,row.deviceId());
            var incidentIds=row.incidents().stream().filter(i->i.closedAt()==null).map(i->i.incidentId()).sorted().toList();
            boolean abnormal=row.abnormal()||!incidentIds.isEmpty();
            Set<String> previousIncidents=new HashSet<>();
            if(fault!=null&&fault.incidents()!=null)try{previousIncidents.addAll(json.readValue(fault.incidents(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));}
            catch(Exception invalid){throw new IllegalStateException("故障依据无法读取",invalid);}
            String incidentKey=encode(incidentIds);
            if(!abnormal) {
                // Only complete healthy evidence closes a fault episode; unknown is not recovery.
                if(fault!=null && row.complete())schedules.fault(id,row.deviceId(),fault.episode(),fault.incidents(),false,fault.task());
                continue;
            }
            boolean newEpisode=fault==null || !fault.active() || incidentIds.stream().anyMatch(i->!previousIncidents.contains(i));
            if(newEpisode) {
                String task=maintenance.createScheduled(plan,row);
                schedules.fault(id,row.deviceId(),UUID.randomUUID().toString(),incidentKey,true,task);
            }
        }
        schedules.save(plan,scope,now,now+60000,"CHECKED",fingerprint,encode(check),verification,null);
    }
    @Transactional
    public void failed(String id) {
        records.lockPlan(id);var plan=plans.findPlan(id,new AccessDecision(null,ScopeMode.ALL));
        if(plan==null)return;
        String scope=hash(Arrays.asList(plan.startAt(),plan.endAt(),plan.routeVersionId(),plan.uavSn(),plan.sourceId(),plan.sourceMode(),plan.ownerOrgId(),plan.districtId()));
        schedules.ensure(plan,scope);var prior=schedules.state(id);long now=clock.nowMillis();
        schedules.save(plan,scope,now,now+60000,"FAILED",prior.fingerprint(),null,prior.verification(),"CHECK_FAILED");
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("检查快照无法保存",e);}}
    private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
