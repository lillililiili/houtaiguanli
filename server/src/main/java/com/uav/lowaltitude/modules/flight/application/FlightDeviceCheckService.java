package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** 自动收集设备事实；没有发现飞机或设备异常，都不能证明未起飞。 */
@Service
public class FlightDeviceCheckService {
    private static final String LOCAL_SIMULATOR_PLAN_SOURCE_ID = "local-flight-plan-simulator";
    private static final Set<String> SENSORS = Set.of("RADAR","EO","OE","TDOA","FIVE_G_A","5GA","FUSION_BOX","AOA","DCD","RID");
    private final DeviceService devices;
    private final FlightReadRepository plans;
    private final AccessControlService access;
    private final SpatialFactPort spatial;
    private final AppClock clock;
    private final boolean localMqttDemo;
    private final boolean localSimulatorDeviceBridge;
    public FlightDeviceCheckService(DeviceService devices, FlightReadRepository plans, AccessControlService access,
            SpatialFactPort spatial, AppClock clock,
            Environment environment) {
        this.devices=devices;this.plans=plans;this.access=access;this.spatial=spatial;this.clock=clock;
        this.localMqttDemo=environment.acceptsProfiles(Profiles.of("!production & local"))
            && environment.getProperty("app.dev-seed.enabled",Boolean.class,false)
            && environment.getProperty("app.flight-device-check.mqtt-demo-enabled",Boolean.class,false);
        this.localSimulatorDeviceBridge=environment.acceptsProfiles(Profiles.of("!production & local"))
            && environment.getProperty("app.flight-device-check.simulator-device-bridge-enabled",Boolean.class,false);
    }
    public record DeviceRow(String deviceId,String name,boolean simulated,BigDecimal distanceM,String connectivity,
            String healthCode,Long lastHeartbeatAt,Long observedAt,boolean abnormal,boolean complete,List<Incident> incidents) { }
    public record Check(String planId,String conclusion,String message,long checkedAt,String selectionBasis,
            boolean complete,int uncheckedLocations,int uncheckedCoverage,List<DeviceRow> rows,boolean mqttSimulation) { }

    @Transactional(readOnly=true)
    public Check read(String planId) {
        var plan=plans.findPlan(FlightActualsService.identifier(planId),access.require(PermissionCode.FLIGHT_READ));
        if(plan==null)throw new ApiException(HttpStatus.NOT_FOUND,"FLIGHT_PLAN_NOT_FOUND","飞行任务不存在或不可见");
        return inspectPlan(plan,false);
    }

    /** Only background application services call this entry point; no synthetic login context. */
    public Check scheduled(FlightReadRepository.PlanRow plan) { return inspectPlan(plan,true); }

    private Check inspectPlan(FlightReadRepository.PlanRow plan,boolean scheduled) {
        String planId=plan.planId();
        boolean simulatorPlan=localSimulatorDeviceBridge
            && LOCAL_SIMULATOR_PLAN_SOURCE_ID.equals(plan.sourceId()) && "mock".equals(plan.sourceMode());
        boolean mqttSimulation=(localMqttDemo && "mock".equals(plan.sourceMode())) || simulatorPlan;
        var routeAccess=scheduled ? new com.uav.lowaltitude.modules.identity.domain.AccessDecision(null,
            com.uav.lowaltitude.modules.identity.domain.ScopeMode.ALL) : access.require(PermissionCode.ROUTE_READ);
        long now=clock.nowMillis();
        if(plan.routeVersionId()==null || plans.findRouteVersion(plan.routeVersionId(),routeAccess)==null)
            return unknown(planId,now,"缺少可用航线，无法查找附近设备。",mqttSimulation);
        if(plan.sourceMode()==null || plan.startAt()==null || plan.endAt()==null)
            return unknown(planId,now,"任务时段或数据来源不完整，暂不能检查。",mqttSimulation);
        if(!plan.endAt().isAfter(plan.startAt()))return unknown(planId,now,"任务时段不正确，暂不能检查。",mqttSimulation);
        boolean preflight=plan.startAt().toInstant().toEpochMilli()>now;
        long from=plan.startAt().toInstant().toEpochMilli(),to=Math.min(now,plan.endAt().toInstant().toEpochMilli());
        // 起飞前检查当前设备和仍未关闭的告警，不拿未来时段判断是否起飞。
        if(preflight)from=to=now;
        if(to<from)return unknown(planId,now,"任务时段不正确，暂不能检查。",mqttSimulation);
        // 不用区县文本作地理范围。普通演示与真实模式仍严格隔离；本地外部接口模拟器
        // 的 mock 计划有明确来源 ID，才允许检查同一模拟器产生的 replay 设备。
        boolean replaySimulation="replay".equals(plan.sourceMode());
        // The same tuple-bound fact projection serves both callers. Interactive reads additionally
        // require devices.read and retain the current user's device scope, not backend monitoring access.
        var inputs=devices.inspectPlanDevices(plan.ownerOrgId(),plan.districtId(),scheduled);
        boolean complete=true;
        List<DeviceRow> rows=new ArrayList<>();int unchecked=0,uncheckedCoverage=0;
        for(var detail:inputs) {
                var device=detail.device();
                boolean demoDevice="replay".equals(device.sourceMode())
                    && device.simulated() && device.deviceNo()!=null && device.deviceNo().startsWith("FP-CHECK-");
                boolean simulatorDevice=simulatorPlan && "replay".equals(device.sourceMode()) && device.simulated();
                boolean sourceCompatible=simulatorPlan?simulatorDevice:(mqttSimulation?demoDevice:plan.sourceMode().equals(device.sourceMode()));
                if(scheduled && "live".equals(plan.sourceMode()) && device.simulated())continue;
                // 回放计划和本地模拟器桥接都只检查当前启用的回放设备；历史批次停用设备不属于本次计划周边设备。
                if(!sourceCompatible || device.deviceTypeCode()==null
                        || !SENSORS.contains(device.deviceTypeCode().toUpperCase(Locale.ROOT))
                        || ((replaySimulation || simulatorPlan) && !device.enabled()))continue;
                if(!positionKnown(detail)){unchecked++;continue;}
                var distance=spatial.distanceToRoute(new TargetState(null,null,null,detail.longitude(),detail.latitude(),null,null,null,null,null,null,null),plan.routeVersionId());
                if(distance.distanceM()==null){uncheckedCoverage++;continue;}
                Boolean covered=coversRoute(detail,plan.routeVersionId(),distance.distanceM());
                if(covered==null){uncheckedCoverage++;continue;}
                if(!covered)continue;
                rows.add(inspect(detail,distance.distanceM(),from,to,preflight,true));
        }
        rows.sort((a,b)->Boolean.compare(b.abnormal() || !b.incidents().isEmpty(),a.abnormal() || !a.incidents().isEmpty()));
        complete=complete && unchecked==0 && uncheckedCoverage==0 && !rows.isEmpty() && rows.stream().allMatch(DeviceRow::complete);
        boolean abnormal=rows.stream().anyMatch(r->r.abnormal() || !r.incidents().isEmpty());
        String conclusion=abnormal?"AUTO_DEVICE_ABNORMAL":complete?"SUSPECTED_NOT_TAKEN_OFF":"CHECK_INCOMPLETE";
        String message=abnormal?"覆盖航线的设备有异常，是否起飞待报送单位确认。":complete?"覆盖航线的设备无异常，疑似未按任务起飞，待报送单位确认。"
            :rows.isEmpty()?"未查到扫描范围覆盖航线的设备，暂不能判断是否起飞。":"设备信息不完整，暂不能排除设备异常，是否起飞待确认。";
        if(preflight) {
            conclusion=abnormal?"PREFLIGHT_DEVICE_ABNORMAL":complete?"PREFLIGHT_DEVICE_NORMAL":"CHECK_INCOMPLETE";
            message=abnormal?"覆盖航线的设备有异常，请在起飞前检查并处理。":complete?"覆盖航线的设备当前正常，起飞前请再次检查。"
                :rows.isEmpty()?"未查到扫描范围覆盖航线的设备，请确认监测设备是否已接入。":"设备信息不完整，起飞前仍需核查。";
        }
        return new Check(planId,conclusion,message,now,"DEVICE_SCAN_COVERAGE",complete,unchecked,uncheckedCoverage,List.copyOf(rows),mqttSimulation);
    }
    /** 配置范围决定是否应监测航线；离线或故障只影响检查结果，不抹除配置范围。 */
    private Boolean coversRoute(PlanInspectionDevice input,String routeVersionId,BigDecimal distance) {
        Coverage coverage=input.device().coverage();
        if(coverage==null)return null;
        if("CIRCLE".equals(coverage.kind())) {
            return positive(coverage.radiusM()) ? distance.compareTo(coverage.radiusM())<=0 : null;
        }
        if(!"SECTOR".equals(coverage.kind()) || !positive(coverage.rangeM())
                || coverage.azimuthDeg()==null || coverage.azimuthDeg().signum()<0
                || coverage.azimuthDeg().compareTo(BigDecimal.valueOf(360))>=0
                || !positive(coverage.fovDeg()) || coverage.fovDeg().compareTo(BigDecimal.valueOf(360))>0)return null;
        if(distance.compareTo(coverage.rangeM())>0)return false;
        if(coverage.fovDeg().compareTo(BigDecimal.valueOf(360))==0)return true;
        return plans.routeIntersectsScanSector(routeVersionId,input.longitude(),input.latitude(),
            coverage.rangeM(),coverage.azimuthDeg(),coverage.fovDeg());
    }
    private static boolean positive(BigDecimal value){return value!=null && value.signum()>0;}

    private DeviceRow inspect(PlanInspectionDevice input,BigDecimal distance,long from,long to,boolean preflight,boolean historyComplete) {
        var device=input.device();var state=input.state();
        // 协议 A 的 2 明确表示异常；1 仅表示工作中，不能补足缺失的健康指标。
        // 协议 C 的 2 含义不同，不能共用。
        boolean lingyun="LINGYUN_MQTT_V8_6".equals(device.protocolCode());
        String health=state.healthCode();
        if(lingyun && "UNKNOWN".equals(health) && "2".equals(state.workStateCode()))health="BAD";
        boolean abnormal=!device.enabled() || Set.of("OFFLINE","ABNORMAL","DEGRADED").contains(state.connectivity())
            || Set.of("BAD","DEGRADED").contains(health) || state.hasAlarm();
        List<Incident> incidents=input.incidents().stream()
            .filter(item->item.detectedAt()<=to && (item.closedAt()==null || item.closedAt()>=from)).toList();
        boolean known=historyComplete && state.observedAt()!=null && (preflight || state.observedAt()>=from)
            && state.observedAt()<=clock.nowMillis() && state.lastHeartbeatAt()!=null
            && (abnormal || ("ONLINE".equals(state.connectivity()) && "GOOD".equals(health)));
        return new DeviceRow(device.deviceId(),device.name(),device.simulated(),distance,
            device.enabled()?state.connectivity():"DISABLED",health,state.lastHeartbeatAt(),state.observedAt(),abnormal,known,List.copyOf(incidents));
    }
    private boolean positionKnown(PlanInspectionDevice d) {
        return "WGS-84".equals(d.coordinateSystem()) && d.longitude()!=null && d.latitude()!=null
            && d.longitude().abs().compareTo(BigDecimal.valueOf(180))<=0 && d.latitude().abs().compareTo(BigDecimal.valueOf(90))<=0;
    }
    private Check unknown(String id,long now,String message,boolean mqttSimulation){return new Check(id,"CHECK_INCOMPLETE",message,now,"DEVICE_SCAN_COVERAGE",false,0,0,List.of(),mqttSimulation);}
}
