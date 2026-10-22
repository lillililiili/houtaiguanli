package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.DeviceService.*;
import com.uav.lowaltitude.modules.device.infrastructure.LocalQaDeviceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthContext;

/** Explicit registration of one independent loopback TCP counterpart. No observations are seeded. */
@Service
@Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
public class LocalQaRadarService {
    public static final String DEVICE_NO="QA-LOCAL-RADAR-TCP", CIDR="127.0.0.1/32";
    public static final int PORT=15001;
    private final AccessService access;
    private final TcpDeviceScopeService scopes;
    private final IntegrationSourceService sources;
    private final DeviceService devices;
    private final LocalQaDeviceRepository repository;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;

    public LocalQaRadarService(AccessService access,TcpDeviceScopeService scopes,IntegrationSourceService sources,
            DeviceService devices,LocalQaDeviceRepository repository,IdempotencyGuard idempotency,AuditService audit) {
        this.access=access;this.scopes=scopes;this.sources=sources;this.devices=devices;
        this.repository=repository;this.idempotency=idempotency;this.audit=audit;
    }

    @Transactional
    public DeviceDetail prepare(String org,String district,BigDecimal longitude,BigDecimal latitude,String coordinateSystem,String key) {
        access.requireBackend();access.require("interfaces.op");access.require("devices.auth");access.require("devices.op");
        var actor=AuthContext.require();
        if(!"ALL".equals(actor.scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"QA_GLOBAL_SCOPE_REQUIRED","本机模拟雷达登记需要全局管理范围");
        var tuple=scopes.validateTuple(org,district);
        if(longitude==null||latitude==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请填写有效的 WGS-84 经度和纬度");
        try {
            com.uav.lowaltitude.platform.geo.Wgs84Coordinates.requirePoint(longitude,latitude,coordinateSystem);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",ex.getMessage());
        }
        // Locks an existing scope row, including the very first registration. DB uniqueness also guards other scopes.
        repository.lockScope(org);
        var rows=repository.existing(DEVICE_NO);
        if(!rows.isEmpty()) {
            if(rows.size()!=1)throw conflict();
            var row=rows.get(0);
            if(!DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(row.get("protocol_code"))
                    ||!Boolean.TRUE.equals(row.get("source_simulated"))||!Boolean.TRUE.equals(row.get("device_simulated"))
                    ||!"live".equals(row.get("source_mode"))||!"live".equals(row.get("device_source_mode"))
                    ||!DEVICE_NO.equals(row.get("device_no"))||!DEVICE_NO.equals(row.get("external_device_id"))
                    ||!CIDR.equals(row.get("allowed_cidrs"))||!"TCP".equals(row.get("transport"))
                    ||!"127.0.0.1".equals(row.get("host"))||!(row.get("port") instanceof Number port)||port.intValue()!=PORT
                    ||!Objects.equals(org,row.get("owner_org_id"))||!Objects.equals(district,row.get("district_id"))
                    ||!Boolean.TRUE.equals(row.get("source_enabled"))||!Boolean.TRUE.equals(row.get("device_enabled"))
                    ||row.get("deleted_at")!=null)throw conflict();
            // Existing position is authoritative; starting a simulator cannot silently move a registered device.
            return devices.detail((String)row.get("device_id"));
        }
        idempotency.claim(key,"local-qa-radar:"+org+":"+district+":"+longitude+":"+latitude);
        var source=sources.insertLive(new IntegrationSourceService.Mutation(DEVICE_NO,"本机原生 TCP 模拟雷达",
                DeviceProtocolCodes.RADAR_TCP_V3_0_0,null,null,CIDR));
        repository.markNewSourceSimulated(source.sourceId());
        var connection=new ConnectionProfile("TCP","127.0.0.1",PORT,null,"BINARY","UTF-8",null,null,30,1000,null,
                false,true,3000,1,null,null,null,"NTP",null,"Asia/Shanghai",60);
        var protocol=new ProtocolConfiguration("DATA",null,true,true,1,"AUTO",5000);
        var created=devices.create(new DeviceMutation(source.sourceId(),DEVICE_NO,DEVICE_NO,"模拟 独立 TCP 雷达",
                "radar","雷达","雷达直连","T02-TCP-SIM","本机原生协议模拟",(String)tuple.get("owner_name"),
                (String)tuple.get("region_name"),null,longitude,latitude,"WGS-84",null,null,null,null,
                connection,protocol,CIDR),org,district);
        sources.activate(source.sourceId(),source.version(),"登记独立 TCP 模拟雷达，仅本机 15001 端口");
        audit.record(actor.userId(),actor.account(),"local_qa_device_prepare","device",created.device().deviceId(),
                "原生雷达 TCP v3.0.0 模拟；127.0.0.1:15001；不代表真实雷达健康或现场探测",null);
        return devices.detail(created.device().deviceId());
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT,"QA_DEVICE_CONFLICT","已有 TCP 模拟雷达的来源、归属或连接配置不匹配，或设备已停用；请在设备管理核对，不能覆盖原配置");
    }
}
