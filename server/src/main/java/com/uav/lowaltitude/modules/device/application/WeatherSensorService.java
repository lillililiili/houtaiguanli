package com.uav.lowaltitude.modules.device.application;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.WeatherSensorDtos.*;
import com.uav.lowaltitude.modules.device.infrastructure.WeatherSensorRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
@Service
public class WeatherSensorService {
    private final WeatherSensorRepository repository;
    private final DeviceRepository devices;
    private final DeviceAccessPolicy permissions;
    private final AccessService access;
    private final AppClock clock;
    private final AuditService audit;
    public WeatherSensorService(WeatherSensorRepository repository,DeviceRepository devices,DeviceAccessPolicy permissions,
            AccessService access,AppClock clock,AuditService audit) {
        this.repository=repository;this.devices=devices;this.permissions=permissions;this.access=access;this.clock=clock;this.audit=audit;
    }
    public Sensor get(String id) { permissions.requireDevicesRead(); return visible(id); }
    @Transactional
    public Sensor create(Input input) {
        var actor=permissions.requireDevicesOperate();
        Input p=normalized(input);
        access.requireTuple(p.ownerOrgId(),p.districtId());
        if(!repository.activeScope(p.ownerOrgId(),p.districtId())) throw bad("单位或区域已停用");
        String id=UUID.randomUUID().toString(); long now=clock.nowMillis();
        try { repository.insert(id,p,now); }
        catch(DataIntegrityViolationException e) { throw new ApiException(HttpStatus.CONFLICT,"DEVICE_NO_CONFLICT","设备编号已存在，请检查设备台账"); }
        devices.addEvent(UUID.randomUUID().toString(),id,"CATALOG_CREATED","INFO","天气传感器已登记，待接入",now,false);
        audit.record(actor.userId(),actor.account(),"weather_sensor_create","device",id,"登记待接入天气传感器",null);
        return visible(id);
    }
    @Transactional
    public Sensor update(String id,Input input) {
        var actor=permissions.requireDevicesOperate(); Sensor before=visible(id); Input p=normalized(input);
        if(p.version()==null) throw bad("版本必填");
        if(!before.deviceNo().equals(p.deviceNo()) || !before.ownerOrgId().equals(p.ownerOrgId()) || !before.districtId().equals(p.districtId()))
            throw bad("设备编号和所属范围不可在此修改");
        long now=clock.nowMillis();
        if(repository.update(id,p,now)!=1) throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","设备已被修改，请重新加载后保存");
        audit.record(actor.userId(),actor.account(),"weather_sensor_update","device",id,"更新天气传感器档案",null);
        return visible(id);
    }
    private Sensor visible(String id) {
        Sensor row=repository.find(id);
        if(row==null || !access.canAccessTuple(row.ownerOrgId(),row.districtId()))
            throw new ApiException(HttpStatus.NOT_FOUND,"DEVICE_NOT_FOUND","设备不存在或不在授权范围内");
        return row;
    }
    private static String clean(String s) { return s==null || s.isBlank()?null:s.trim(); }
    private static Input normalized(Input p) { return new Input(clean(p.deviceNo()),clean(p.name()),clean(p.vendor()),clean(p.model()),
        clean(p.address()),clean(p.ownerOrgId()),clean(p.districtId()),p.version()); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
}
