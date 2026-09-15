package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Set<String> SENSORS = Set.of("RADAR","EO","OE","TDOA","FIVE_G_A","5GA","FUSION_BOX","AOA","DCD","RID");
    private final DeviceService devices;
    private final FlightReadRepository plans;
    private final AccessControlService access;
    private final SpatialFactPort spatial;
    private final AppClock clock;
    private final BigDecimal nearbyMeters;
    private final boolean localMqttDemo;
    public FlightDeviceCheckService(DeviceService devices, FlightReadRepository plans, AccessControlService access,
            SpatialFactPort spatial, AppClock clock, @Value("${app.flight-device-check.nearby-meters:5000}") BigDecimal nearbyMeters,
            Environment environment) {
        this.devices=devices;this.plans=plans;this.access=access;this.spatial=spatial;this.clock=clock;
        if(nearbyMeters.signum()<=0)throw new IllegalArgumentException("设备附近范围必须大于零");
        this.nearbyMeters=nearbyMeters;
        this.localMqttDemo=environment.acceptsProfiles(Profiles.of("!production & local"))
            && environment.getProperty("app.dev-seed.enabled",Boolean.class,false)
            && environment.getProperty("app.flight-device-check.mqtt-demo-enabled",Boolean.class,false);
    }
    public record DeviceRow(String deviceId,String name,boolean simulated,BigDecimal distanceM,String connectivity,
            String healthCode,Long lastHeartbeatAt,Long observedAt,boolean abnormal,boolean complete,List<Incident> incidents) { }
    public record Check(String planId,String conclusion,String message,long checkedAt,BigDecimal nearbyMeters,
            boolean complete,int uncheckedLocations,List<DeviceRow> rows,boolean mqttSimulation) { }

    @Transactional(readOnly=true)
    public Check read(String planId) {
        var plan=plans.findPlan(FlightActualsService.identifier(planId),access.require(PermissionCode.FLIGHT_READ));
        if(plan==null)throw new ApiException(HttpStatus.NOT_FOUND,"FLIGHT_PLAN_NOT_FOUND","飞行计划不存在或不可见");
        boolean mqttSimulation=localMqttDemo && "mock".equals(plan.sourceMode());
        var routeAccess=access.require(PermissionCode.ROUTE_READ);
        long now=clock.nowMillis();
        if(plan.routeVersionId()==null || plans.findRouteVersion(plan.routeVersionId(),routeAccess)==null)
            return unknown(planId,now,"缺少可用航线，无法查找附近设备。",mqttSimulation);
        if(plan.sourceMode()==null || plan.startAt()==null || plan.endAt()==null)
            return unknown(planId,now,"计划时段或数据来源不完整，暂不能检查。",mqttSimulation);
        if(!plan.endAt().isAfter(plan.startAt()))return unknown(planId,now,"计划时段不正确，暂不能检查。",mqttSimulation);
        boolean preflight=plan.startAt().toInstant().toEpochMilli()>now;
        long from=plan.startAt().toInstant().toEpochMilli(),to=Math.min(now,plan.endAt().toInstant().toEpochMilli());
        // 起飞前检查当前设备和仍未关闭的告警，不拿未来时段判断是否起飞。
        if(preflight)from=to=now;
        if(to<from)return unknown(planId,now,"计划时段不正确，暂不能检查。",mqttSimulation);
        // 不用区县文本或来源单位作地理范围；演示与真实模式仍严格隔离。
        List<DeviceRow> rows=new ArrayList<>();boolean complete=false;int unchecked=0,seen=0;
        for(int page=1;page<=20;page++) {
            var listed=devices.list(new DeviceFilter(null,null,null,null,null,null,null),page,100,"device_no_asc");
            for(var device:listed.items()) {
                boolean demoDevice="replay".equals(device.sourceMode())
                    && device.simulated() && device.deviceNo()!=null && device.deviceNo().startsWith("FP-CHECK-");
                if((mqttSimulation?!demoDevice:!plan.sourceMode().equals(device.sourceMode())) || device.deviceTypeCode()==null
                        || !SENSORS.contains(device.deviceTypeCode().toUpperCase(Locale.ROOT)))continue;
                var detail=devices.detail(device.deviceId());
                if(!positionKnown(detail)){unchecked++;continue;}
                var distance=spatial.distanceToRoute(new TargetState(null,null,null,detail.longitude(),detail.latitude(),null,null,null,null,null,null,null),plan.routeVersionId());
                if(distance.distanceM()==null){unchecked++;continue;}
                if(distance.distanceM().compareTo(nearbyMeters)>0)continue;
                rows.add(inspect(device,distance.distanceM(),from,to,preflight));
            }
            seen+=listed.items().size();
            if(seen>=listed.total()){complete=true;break;}
            if(listed.items().isEmpty())break;
        }
        rows.sort((a,b)->Boolean.compare(b.abnormal() || !b.incidents().isEmpty(),a.abnormal() || !a.incidents().isEmpty()));
        complete=complete && unchecked==0 && !rows.isEmpty() && rows.stream().allMatch(DeviceRow::complete);
        boolean abnormal=rows.stream().anyMatch(r->r.abnormal() || !r.incidents().isEmpty());
        String conclusion=abnormal?"AUTO_DEVICE_ABNORMAL":complete?"SUSPECTED_NOT_TAKEN_OFF":"CHECK_INCOMPLETE";
        String message=abnormal?"附近设备有异常，是否起飞待报送单位确认。":complete?"附近无异常设备，疑似未按计划起飞，待报送单位确认。"
            :rows.isEmpty()?"附近没有查到可检查的设备，暂不能判断是否起飞。":"设备信息不完整，暂不能排除设备异常，是否起飞待确认。";
        if(preflight) {
            conclusion=abnormal?"PREFLIGHT_DEVICE_ABNORMAL":complete?"PREFLIGHT_DEVICE_NORMAL":"CHECK_INCOMPLETE";
            message=abnormal?"附近设备有异常，请在起飞前检查并处理。":complete?"本次检查的附近设备当前正常，起飞前请再次检查。"
                :rows.isEmpty()?"附近没有查到可检查的设备，请确认监测设备是否已接入。":"设备信息不完整，起飞前仍需核查。";
        }
        return new Check(planId,conclusion,message,now,nearbyMeters,complete,unchecked,List.copyOf(rows),mqttSimulation);
    }
    private DeviceRow inspect(DeviceSummary device,BigDecimal distance,long from,long to,boolean preflight) {
        var state=devices.state(device.deviceId());
        // 凌云协议 A：0 未工作、1 工作中、2 异常；协议 C 的 2 含义不同，不能共用。
        boolean lingyun="LINGYUN_MQTT_V8_6".equals(device.protocolCode());
        String health=state.healthCode();
        if(lingyun && "UNKNOWN".equals(health))health=switch(String.valueOf(state.workStateCode())) {
            case "1" -> "GOOD";case "2" -> "BAD";default -> "UNKNOWN";
        };
        boolean abnormal=!device.enabled() || Set.of("OFFLINE","ABNORMAL","DEGRADED").contains(state.connectivity())
            || Set.of("BAD","DEGRADED").contains(health) || state.hasAlarm();
        List<Incident> incidents=new ArrayList<>();boolean historyComplete=false;int seen=0;
        for(int page=1;page<=20;page++) {
            var history=devices.incidents(device.deviceId(),null,null,page,100);
            for(var item:history.items())if(item.detectedAt()<=to && (item.closedAt()==null || item.closedAt()>=from))incidents.add(item);
            seen+=history.items().size();
            if(seen>=history.total()){historyComplete=true;break;}
            if(history.items().isEmpty())break;
        }
        boolean known=historyComplete && state.observedAt()!=null && (preflight || state.observedAt()>=from)
            && state.observedAt()<=clock.nowMillis() && state.lastHeartbeatAt()!=null
            && (abnormal || ("ONLINE".equals(state.connectivity()) && "GOOD".equals(health)));
        return new DeviceRow(device.deviceId(),device.name(),device.simulated(),distance,
            device.enabled()?state.connectivity():"DISABLED",health,state.lastHeartbeatAt(),state.observedAt(),abnormal,known,List.copyOf(incidents));
    }
    private boolean positionKnown(DeviceDetail d) {
        return "WGS-84".equals(d.coordinateSystem()) && d.longitude()!=null && d.latitude()!=null
            && d.longitude().abs().compareTo(BigDecimal.valueOf(180))<=0 && d.latitude().abs().compareTo(BigDecimal.valueOf(90))<=0;
    }
    private Check unknown(String id,long now,String message,boolean mqttSimulation){return new Check(id,"CHECK_INCOMPLETE",message,now,nearbyMeters,false,0,List.of(),mqttSimulation);}
}
