package com.uav.lowaltitude.modules.device.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    @Transactional public DeviceDetail prepare(String planId,String key) {
        access.require("interfaces.op");access.require("devices.auth");
        var actor=AuthContext.require();
        if(!"ALL".equals(actor.scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"QA_GLOBAL_SCOPE_REQUIRED","本地测试设备准备需要全局管理范围");
        var plan=flights.flightPlan(planId);LocalInterfaceSimulatorService.requireSimulated(plan.sourceMode());
        if(plan.ownerOrgId()==null||plan.districtId()==null)throw new ApiException(HttpStatus.CONFLICT,"QA_SCOPE_REQUIRED","测试计划必须具有单位与区域");
        var existing=repo.existing(NO);
        if(!existing.isEmpty()){
            var configured=existing.stream()
                    .filter(row -> matchesConfigured(row,plan.ownerOrgId(),plan.districtId()))
                    .toList();
            if(configured.size()!=1)
                throw new ApiException(HttpStatus.CONFLICT,"QA_DEVICE_CONFLICT","已有同编号设备的来源、范围或本机连接配置不匹配");
            var row=configured.get(0); String deviceId=(String)row.get("device_id");
            if(matches(row,plan.ownerOrgId(),plan.districtId())) return devices.detail(deviceId);
            idempotency.claim(key,"local-qa-cm4:"+planId);
            long now=clock.nowMillis();
            Number deviceVersion=(Number)row.get("device_version");
            Number sourceVersion=(Number)row.get("source_version");
            if(deviceVersion==null||sourceVersion==null||!repo.restoreDevice(deviceId,deviceVersion.longValue(),now))
                throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","QA 模拟设备已被更新，请刷新后重试");
            if(!Boolean.TRUE.equals(row.get("source_enabled")))
                sources.activate((String)row.get("source_id"),sourceVersion.longValue(),"重新准备本机QA模拟设备");
            audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",deviceId,
                    "恢复既有 QA 模拟设备；仅127.0.0.1:10006，不创建反制授权",null);
            return devices.detail(deviceId);
        }
        idempotency.claim(key,"local-qa-cm4:"+planId);
        var source=sources.insertLive(new IntegrationSourceService.Mutation(NO,"本机四通道QA模拟器",DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0,null,null,CIDR));
        repo.markNewSourceSimulated(source.sourceId());
        var connection=new ConnectionProfile("TCP","127.0.0.1",10006,null,"BINARY","UTF-8",null,null,30,1000,null,false,true,3000,1,null,null,null,"NTP",null,"Asia/Shanghai",60);
        var protocol=new ProtocolConfiguration("DATA",null,false,false,1,"ASCII_HEX_SPACED",5000);
        var created=devices.create(new DeviceMutation(source.sourceId(),NO,NO,"本机四通道QA模拟器","countermeasure","反制","反制直连","QA-CM4","本机协议模拟",plan.ownerOrgName(),plan.districtName(),null,null,null,null,null,null,null,null,connection,protocol,CIDR));
        repo.bindScope(created.device().deviceId(),plan.ownerOrgId(),plan.districtId(),clock.nowMillis());
        sources.activate(source.sourceId(),source.version(),"显式准备本机QA模拟设备；不代表现场射频设备");
        audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",created.device().deviceId(),"仅127.0.0.1:10006；不创建反制授权",null);
        return devices.detail(created.device().deviceId());
    }
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
