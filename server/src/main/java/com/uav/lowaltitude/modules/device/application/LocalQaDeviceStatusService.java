package com.uav.lowaltitude.modules.device.application;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.LocalQaDeviceStatusDtos.*;
import com.uav.lowaltitude.modules.device.infrastructure.LocalQaDeviceStatusRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMonitoringEventRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Ingests explicit QA facts only. Incident recovery and maintenance completion stay in their existing workflows. */
@Service
@Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
public class LocalQaDeviceStatusService {
    private final LocalQaDeviceStatusRepository repository;
    private final DeviceMonitoringEventRepository monitoring;
    private final AccessService access;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    public LocalQaDeviceStatusService(LocalQaDeviceStatusRepository repository,DeviceMonitoringEventRepository monitoring,
            AccessService access,AppClock clock,AuditService audit,ObjectMapper json){
        this.repository=repository;this.monitoring=monitoring;this.access=access;this.clock=clock;this.audit=audit;this.json=json;
    }
    @Transactional public Receipt accept(Input input){
        access.require("interfaces.op");access.require("devices.auth");access.require("monitoring.op");
        var actor=AuthContext.require();
        if(!"ALL".equals(actor.scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"QA_GLOBAL_SCOPE_REQUIRED","受控模拟状态输入需要全局管理范围");
        var device=repository.lock(input.deviceId());
        if(device==null||!Objects.equals(device.sourceId(),input.sourceId())||!"replay".equals(device.sourceMode())
                ||!device.simulated()||!device.enabled()||!device.sourceEnabled())
            throw conflict("仅允许当前启用、来源匹配的回放模拟设备接收独立 QA 状态");
        long now=clock.nowMillis();
        byte[] raw;String hash;
        try{raw=json.writeValueAsBytes(input);hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
        catch(Exception error){throw new IllegalStateException("Cannot encode QA status",error);}
        String source="local-qa-status:"+input.deviceId();
        var previous=repository.previous(source,input.messageId());
        if(previous!=null){
            if(!hash.equals(previous.hash()))throw conflict("同一模拟状态消息不能更改内容");
            return new Receipt(input.messageId(),input.deviceId(),input.observedAt(),previous.receivedAt(),"LOCAL_QA_STATUS",true);
        }
        if(input.observedAt()>now||now-input.observedAt()>30000)throw conflict("模拟状态必须为最近30秒内的非未来观测");
        var before=monitoring.lockOrInitialize(input.deviceId(),now);
        Long current=repository.currentObserved(input.deviceId());
        if(current!=null&&input.observedAt()<=current)throw conflict("旧状态不能覆盖设备的更新观测");
        repository.save(input,source,hash,raw,now);
        monitoring.accepted(before,"STATUS",now);
        audit.record(actor.userId(),actor.account(),"local_qa_device_status","device",input.deviceId(),
                "独立模拟状态；非协议A健康推断；message_id="+input.messageId()+"; source_id="+input.sourceId()+"; health="+input.healthCode(),null);
        return new Receipt(input.messageId(),input.deviceId(),input.observedAt(),now,"LOCAL_QA_STATUS",true);
    }
    private static ApiException conflict(String text){return new ApiException(HttpStatus.CONFLICT,"QA_STATUS_CONFLICT",text);}
}
