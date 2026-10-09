package com.uav.lowaltitude.modules.device.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.DeviceService.*;
import com.uav.lowaltitude.modules.device.infrastructure.LocalQaDeviceRepository;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/** Fixed loopback protocol counterpart registration, independent of dev-seed and disposal grants. */
@Service
@Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
public class LocalQaDeviceService {
    private static final String NO="QA-LOCAL-CM4", CIDR="127.0.0.1/32";
    private final AccessService access; private final FlightReadService flights;
    private final IntegrationSourceService sources; private final DeviceService devices;
    private final LocalQaDeviceRepository repo; private final IdempotencyGuard idempotency;
    private final AuditService audit; private final AppClock clock;
    public LocalQaDeviceService(AccessService access,FlightReadService flights,IntegrationSourceService sources,DeviceService devices,
            LocalQaDeviceRepository repo,IdempotencyGuard idempotency,AuditService audit,AppClock clock) {
        this.access=access;this.flights=flights;this.sources=sources;this.devices=devices;this.repo=repo;
        this.idempotency=idempotency;this.audit=audit;this.clock=clock;
    }
    /** 反制设备长期部署在某个单位与区县，不跟飞行计划绑定（2026-10-05 业务确认）。
     *  位置只用于地图上画作用范围示意：新登记时带上；已登记但还没有位置的补上，已有位置的不改（新-4）。 */
    @Transactional public DeviceDetail prepare(String ownerOrgId,String districtId,String planId,BigDecimal longitude,BigDecimal latitude,String key) {
        access.require("interfaces.op");access.require("devices.auth");
        var actor=AuthContext.require();
        if(!"ALL".equals(actor.scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"QA_GLOBAL_SCOPE_REQUIRED","本地测试设备准备需要全局管理范围");
        Position position=position(longitude,latitude);
        Scope scope;
        if(!blank(ownerOrgId)||!blank(districtId)) {
            if(blank(ownerOrgId)||blank(districtId))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请同时选择反制设备所属单位和区县");
            var names=repo.scopeNames(ownerOrgId.trim(),districtId.trim());
            if(names==null)throw new ApiException(HttpStatus.CONFLICT,"QA_SCOPE_REQUIRED","所选单位或区县不存在或已停用");
            scope=new Scope(ownerOrgId.trim(),names[0],districtId.trim(),names[1]);
        } else if(!blank(planId)) {
            var flight=flights.flightPlan(planId);LocalInterfaceSimulatorService.requireSimulated(flight.sourceMode());
            if(flight.ownerOrgId()==null||flight.districtId()==null)throw new ApiException(HttpStatus.CONFLICT,"QA_SCOPE_REQUIRED","测试任务必须具有单位与区域");
            scope=new Scope(flight.ownerOrgId(),flight.ownerOrgName(),flight.districtId(),flight.districtName());
        } else throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请选择反制设备所属单位和区县");
        String scopeKey=scope.ownerOrgId()+":"+scope.districtId();
        var existing=repo.existing(NO);
        if(!existing.isEmpty()){
            var configured=existing.stream()
                    .filter(row -> matchesConfigured(row,scope.ownerOrgId(),scope.districtId()))
                    .toList();
            if(configured.size()!=1)
                throw new ApiException(HttpStatus.CONFLICT,"QA_DEVICE_CONFLICT","已有同编号设备的来源、范围或本机连接配置不匹配");
            var row=configured.get(0); String deviceId=(String)row.get("device_id");
            if(matches(row,scope.ownerOrgId(),scope.districtId())) {
                if(position!=null&&row.get("longitude")==null&&row.get("latitude")==null){
                    idempotency.claim(key,"local-qa-cm4-position:"+deviceId+":"+position);
                    place(actor,deviceId,position);
                }
                return devices.detail(deviceId);
            }
            idempotency.claim(key,"local-qa-cm4:"+scopeKey);
            long now=clock.nowMillis();
            Number deviceVersion=(Number)row.get("device_version");
            Number sourceVersion=(Number)row.get("source_version");
            if(deviceVersion==null||sourceVersion==null||!repo.restoreDevice(deviceId,deviceVersion.longValue(),now))
                throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","QA 模拟设备已被更新，请刷新后重试");
            if(!Boolean.TRUE.equals(row.get("source_enabled")))
                sources.activate((String)row.get("source_id"),sourceVersion.longValue(),"重新准备本机QA模拟设备");
            audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",deviceId,
                    "恢复既有 QA 模拟设备；仅127.0.0.1:10006，不创建反制授权",null);
            if(position!=null&&row.get("longitude")==null&&row.get("latitude")==null) place(actor,deviceId,position);
            return devices.detail(deviceId);
        }
        idempotency.claim(key,"local-qa-cm4:"+scopeKey);
        var source=sources.insertLive(new IntegrationSourceService.Mutation(NO,"本机四通道QA模拟器",DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0,null,null,CIDR));
        repo.markNewSourceSimulated(source.sourceId());
        var connection=new ConnectionProfile("TCP","127.0.0.1",10006,null,"BINARY","UTF-8",null,null,30,1000,null,false,true,3000,1,null,null,null,"NTP",null,"Asia/Shanghai",60);
        var protocol=new ProtocolConfiguration("DATA",null,false,false,1,"ASCII_HEX_SPACED",5000);
        var created=devices.create(new DeviceMutation(source.sourceId(),NO,NO,"本机四通道QA模拟器","countermeasure","反制","反制直连","QA-CM4","本机协议模拟",scope.ownerOrgName(),scope.districtName(),null,
                position==null?null:position.longitude(),position==null?null:position.latitude(),position==null?null:"WGS-84",null,null,null,null,connection,protocol,CIDR));
        repo.bindScope(created.device().deviceId(),scope.ownerOrgId(),scope.districtId(),clock.nowMillis());
        sources.activate(source.sourceId(),source.version(),"显式准备本机QA模拟设备；不代表现场射频设备");
        audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",created.device().deviceId(),
                "仅127.0.0.1:10006；不创建反制授权"+(position==null?"":"；位置 "+position.text()+"，只用于地图上画作用范围示意"),null);
        return devices.detail(created.device().deviceId());
    }
    private void place(AuthUser actor,String deviceId,Position position) {
        if(repo.fillPosition(deviceId,position.longitude(),position.latitude(),clock.nowMillis()))
            audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",deviceId,
                    "补上 QA 模拟设备位置 "+position.text()+"，只用于地图上画作用范围示意；已有位置的设备不改",null);
    }
    /** 经纬度要么都不传，要么都传且在 WGS-84 范围内；存到 7 位小数，和设备表一致。 */
    private static Position position(BigDecimal longitude,BigDecimal latitude) {
        if(longitude==null&&latitude==null)return null;
        if(longitude==null||latitude==null)throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","反制设备位置的经度和纬度要一起填");
        if(longitude.compareTo(BigDecimal.valueOf(-180))<0||longitude.compareTo(BigDecimal.valueOf(180))>0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","反制设备经度必须在 -180 到 180 之间");
        if(latitude.compareTo(BigDecimal.valueOf(-90))<0||latitude.compareTo(BigDecimal.valueOf(90))>0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","反制设备纬度必须在 -90 到 90 之间");
        return new Position(longitude.setScale(7,RoundingMode.HALF_UP),latitude.setScale(7,RoundingMode.HALF_UP));
    }
    private record Position(BigDecimal longitude,BigDecimal latitude) {
        String text() { return "经度 "+longitude.toPlainString()+"，纬度 "+latitude.toPlainString(); }
        @Override public String toString() { return longitude.toPlainString()+","+latitude.toPlainString(); }
    }
    private record Scope(String ownerOrgId,String ownerOrgName,String districtId,String districtName) { }
    private static boolean blank(String value) { return value==null||value.isBlank(); }
    private static boolean matchesConfigured(Map<String,Object> row,String org,String district) {
        return DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(row.get("protocol_code"))
                && "live".equals(row.get("source_mode"))
                && Boolean.TRUE.equals(row.get("source_simulated"))
                && CIDR.equals(row.get("allowed_cidrs"))
                && row.get("device_id")!=null
                && NO.equals(row.get("device_no"))
                && NO.equals(row.get("external_device_id"))
                && "live".equals(row.get("device_source_mode"))
                && Boolean.TRUE.equals(row.get("device_simulated"))
                && "TCP".equals(row.get("transport"))
                && "127.0.0.1".equals(row.get("host"))
                && row.get("port") instanceof Number port && port.intValue()==10006
                && Objects.equals(org,row.get("owner_org_id"))
                && Objects.equals(district,row.get("district_id"));
    }
    private static boolean matches(Map<String,Object> row,String org,String district) {
        return matchesConfigured(row,org,district)
                && Boolean.TRUE.equals(row.get("source_enabled"))
                && Boolean.TRUE.equals(row.get("device_enabled"))
                && row.get("deleted_at")==null;
    }
}
