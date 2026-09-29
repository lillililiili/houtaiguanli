package com.uav.lowaltitude.modules.device.application;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.infrastructure.IntegrationSourceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.TcpDeviceScopeRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;

/** Runs inside the device mutation transaction; never infers ownership from display names. */
@Service
public class TcpDeviceScopeService {
    private final TcpDeviceScopeRepository repository;
    private final IntegrationSourceRepository sources;
    private final AccessService access;
    public TcpDeviceScopeService(TcpDeviceScopeRepository repository,IntegrationSourceRepository sources,AccessService access) {
        this.repository=repository; this.sources=sources; this.access=access;
    }

    public Map<String,Object> validateTuple(String requestedOrg,String requestedDistrict) {
        String org=blank(requestedOrg),district=blank(requestedDistrict);
        if(org==null || district==null)throw bad("请选择所属单位及区域");
        access.requireTuple(org,district);
        var tuples=repository.validTuple(org,district);
        if(tuples.isEmpty())throw bad("请选择有效且启用的所属单位及区域");
        return tuples.get(0);
    }

    public void bind(String id,String requestedOrg,String requestedDistrict,long now) {
        var previous=repository.scope(id);
        String org=blank(requestedOrg),district=blank(requestedDistrict);
        if(org==null && district==null && previous==null)return; // Existing low-level registration contract.
        var device=repository.device(id);
        String protocol=text(device,"protocol_code");
        boolean radar=DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocol);
        if(!radar && !DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(protocol)) {
            if(org==null && district==null)return;
            throw bad("该协议须使用对应的设备登记流程维护归属");
        }
        if(previous!=null) {
            if((org!=null && !org.equals(previous.get("owner_org_id")))
                    || (district!=null && !district.equals(previous.get("district_id"))))
                throw conflict("设备所属单位和区域已绑定，不能通过编辑迁移历史数据");
            org=text(previous,"owner_org_id"); district=text(previous,"district_id");
        }
        var tuple=validateTuple(org,district);
        if(!"live".equals(text(device,"source_mode")))throw bad("TCP 标准设备必须使用 live 协议来源");
        if(radar) {
            if(repository.devicesOnSource(text(device,"source_id"))!=1)
                throw conflict("雷达来源关联多台设备，无法确定统一目标归属，请使用独立接入来源");
            sources.ensureStandardLiveRadar(text(device,"source_code"),text(device,"name"),text(device,"protocol_version"),
                    Boolean.TRUE.equals(device.get("source_enabled")),now);
            String source=repository.standardSource(text(device,"source_code"));
            var standard=repository.standardDevice(id);
            if(standard!=null && (!Objects.equals(source,standard.get("source_id"))
                    || !Objects.equals(device.get("external_device_id"),standard.get("external_device_id"))
                    || !Objects.equals(device.get("device_no"),standard.get("device_no"))
                    || !Objects.equals(org,standard.get("owner_org_id"))
                    || !Objects.equals(district,standard.get("district_id"))))
                throw conflict("已绑定雷达的接入来源和设备身份不可更改");
            repository.saveRadar(device,source,org,district,now);
        }
        repository.saveScope(id,org,district,text(tuple,"owner_name"),text(tuple,"region_name"),now);
    }
    public void enabled(String id,boolean enabled,long now) { repository.enabled(id,enabled,now); }
    private static String text(Map<String,Object> value,String key) { Object v=value.get(key); return v==null?null:String.valueOf(v); }
    private static String blank(String value) { return value==null || value.isBlank()?null:value.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"DEVICE_IDENTITY_CONFLICT",message); }
}
