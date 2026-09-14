package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunControlEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttNetworkPolicy;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class MqttConfigurationService {
    private final MqttRepository repository;
    private final EoEdgeRepository edges;
    private final DeviceAccessPolicy permissions;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final MqttNetworkPolicy network;
    private final EnvironmentCredentialResolver credentials;

    public MqttConfigurationService(MqttRepository repository, EoEdgeRepository edges, DeviceAccessPolicy permissions,
            AccessService access, IdempotencyGuard idempotency, AuditService audit, AppClock clock,
            MqttNetworkPolicy network, EnvironmentCredentialResolver credentials) {
        this.repository=repository; this.edges=edges; this.permissions=permissions; this.access=access;
        this.idempotency=idempotency; this.audit=audit; this.clock=clock; this.network=network; this.credentials=credentials;
    }
    public List<Broker> list() {
        permissions.requireInterfacesRead();
        return repository.brokers().stream().filter(b -> access.canAccessTuple(b.ownerOrgId(),b.districtId())).toList();
    }
    public List<BrokerOption> options() {
        permissions.requireDevicesOperate();
        return repository.brokers().stream().filter(b -> access.canAccessTuple(b.ownerOrgId(),b.districtId()))
                .map(b -> new BrokerOption(b.brokerId(),b.name(),b.sourceMode(),b.ownerOrgId(),b.districtId(),b.enabled())).toList();
    }
    public record BrokerOption(String brokerId,String name,String sourceMode,String ownerOrgId,String districtId,boolean enabled) { }
    public Broker get(String id) {
        permissions.requireInterfacesRead();
        return requiredBroker(id,false);
    }
    public List<Map<String,Object>> scopes() {
        permissions.requireDevicesOperate();
        return repository.scopeOptions().stream().filter(r -> access.canAccessTuple((String)r.get("org_id"),(String)r.get("district_id"))).toList();
    }
    @Transactional
    public Broker create(BrokerInput p,String key) {
        permissions.requireInterfacesOperate(); validate(p); scope(p.ownerOrgId(),p.districtId());
        idempotency.claim(key,"mqtt.create:"+p);
        String id=UUID.randomUUID().toString();
        repository.insertBroker(id,p,clock.nowMillis());
        Broker broker=requiredBroker(id,false);
        network.resolve(broker);
        record("mqtt_broker_create",id);
        return broker;
    }
    @Transactional
    public Broker update(String id,BrokerInput p,String key) {
        permissions.requireInterfacesOperate();
        Broker old=requiredBroker(id,true); validate(p);
        if(old.enabled()) throw conflict("BROKER_MUST_BE_DISABLED","请先停用 MQTT 连接，再修改配置");
        if(!old.sourceMode().equals(p.sourceMode()) || !old.ownerOrgId().equals(p.ownerOrgId()) || !old.districtId().equals(p.districtId()))
            throw bad("来源模式和所属组织区域不可更改，请建立独立连接");
        idempotency.claim(key,"mqtt.update:"+id+":"+p);
        if(p.version()==null || repository.updateBroker(id,p,clock.nowMillis())!=1) throw versionConflict();
        Broker updated=requiredBroker(id,false); network.resolve(updated);
        record("mqtt_broker_update",id);
        return updated;
    }
    @Transactional
    public Broker enable(String id,long version,boolean enabled,String key) {
        permissions.requireInterfacesOperate();
        Broker broker=requiredBroker(id,true);
        if(enabled) validateConnection(broker);
        idempotency.claim(key,"mqtt.enable:"+id+":"+version+":"+enabled);
        if(repository.enableBroker(id,version,enabled,clock.nowMillis())!=1) throw versionConflict();
        record(enabled?"mqtt_broker_enable":"mqtt_broker_disable",id);
        return requiredBroker(id,false);
    }
    public void validateConnection(Broker broker) {
        network.resolve(broker);
        if(broker.sourceMode().equals("live") && (broker.username()==null || broker.username().isBlank()
                || broker.credentialRef()==null || broker.credentialRef().isBlank()))
            throw bad("真实来源必须配置平台分配的用户名和凭据引用");
        credentials.resolve(broker.credentialRef());
    }
    @Transactional
    public String register(Registration p,String key) {
        permissions.requireDevicesOperate(); validate(p); scope(p.ownerOrgId(),p.districtId());
        Broker broker=requiredBroker(p.brokerId(),true);
        if(!broker.sourceMode().equals(p.sourceMode()) || !broker.ownerOrgId().equals(p.ownerOrgId()) || !broker.districtId().equals(p.districtId()))
            throw bad("设备来源模式、组织区域必须与所选连接一致");
        if(broker.sourceMode().equals("live")) validateConnection(broker);
        idempotency.claim(key,"mqtt.register:"+p);
        try {
            String id=eo(p)?edges.register(p,clock.nowMillis()):repository.register(p,clock.nowMillis());
            record("mqtt_device_register",id); return id;
        } catch(IllegalArgumentException ex) {
            if("EDGE_IDENTITY_CONFLICT".equals(ex.getMessage())) throw bad("该 edgeId 已绑定到其他 MQTT 连接或组织区域");
            throw ex;
        }
    }
    public boolean isMqtt(String id) { return repository.binding(id,false)!=null || edges.binding(id,false)!=null; }
    public boolean isEo(String id) { return edges.binding(id,false)!=null; }
    public Binding binding(String id) {
        permissions.requireDevicesRead();
        Binding b=repository.binding(id,false);
        if(b==null) throw new ApiException(HttpStatus.NOT_FOUND,"DEVICE_NOT_FOUND","MQTT 设备不存在");
        scope(b.ownerOrgId(),b.districtId()); return b;
    }
    @Transactional
    public void updateDevice(String id,Registration p,String key) {
        permissions.requireDevicesOperate(); validate(p);
        var eo=edges.binding(id,true);
        if(eo!=null) {
            requiredBroker(eo.brokerId(),true);
            if(!eo.brokerId().equals(p.brokerId()) || !eo.edgeId().equals(p.edgeId())
                    || !eo.externalDeviceId().equals(p.externalDeviceId()) || !eo.sourceMode().equals(p.sourceMode())
                    || !eo.ownerOrgId().equals(p.ownerOrgId()) || !eo.districtId().equals(p.districtId()))
                throw bad("接入身份不可更改；仅可编辑名称、厂家和型号");
            idempotency.claim(key,"mqtt.device.update:"+id+":"+p);
            if(p.version()==null || edges.updateDevice(eo,p,clock.nowMillis())!=1) throw versionConflict();
            record("mqtt_device_update",id); return;
        }
        Binding b=binding(id);
        requiredBroker(b.brokerId(),true); repository.binding(id,true);
        if(!b.brokerId().equals(p.brokerId()) || !b.providerCode().equals(p.providerCode())
                || !b.externalDeviceId().equals(p.externalDeviceId()) || !b.deviceTypeAbbr().equals(p.deviceTypeAbbr())
                || !b.sourceMode().equals(p.sourceMode()) || !b.ownerOrgId().equals(p.ownerOrgId()) || !b.districtId().equals(p.districtId()))
            throw bad("接入身份不可更改；仅可编辑名称、厂家和型号");
        idempotency.claim(key,"mqtt.device.update:"+id+":"+p);
        if(p.version()==null || repository.updateDevice(b,p,clock.nowMillis())!=1) throw versionConflict();
        record("mqtt_device_update",id);
    }
    @Transactional
    public void enableDevice(String id,long version,boolean enabled,String key) {
        permissions.requireDevicesOperate();
        var eo=edges.binding(id,true);
        if(eo!=null) {
            Broker broker=requiredBroker(eo.brokerId(),true);
            if(enabled && broker.sourceMode().equals("live")) validateConnection(broker);
            idempotency.claim(key,"mqtt.device.enable:"+id+":"+version+":"+enabled);
            if(edges.enableDevice(eo,version,enabled,clock.nowMillis())!=1) throw versionConflict();
            record(enabled?"mqtt_device_enable":"mqtt_device_disable",id); return;
        }
        Binding b=binding(id);
        Broker broker=requiredBroker(b.brokerId(),true); repository.binding(id,true);
        if(enabled && broker.sourceMode().equals("live")) validateConnection(broker);
        idempotency.claim(key,"mqtt.device.enable:"+id+":"+version+":"+enabled);
        if(repository.enableDevice(b,version,enabled,clock.nowMillis())!=1) throw versionConflict();
        record(enabled?"mqtt_device_enable":"mqtt_device_disable",id);
    }
    public Map<String,Object> status(String id) {
        var eo=edges.binding(id,false);
        if(eo!=null) {
            scope(eo.ownerOrgId(),eo.districtId());
            Map<String,Object> status=edges.status(id);
            if(((Number)status.get("lease_until")).longValue()<=clock.nowMillis() || !Boolean.TRUE.equals(status.get("broker_enabled"))) {
                status.put("connection_state","DISCONNECTED"); status.put("subscribed",false);
            }
            status.put("reporting_topic",eo.reportingTopic());
            status.put("dispatcher_topic",eo.dispatcherTopic());
            status.put("source",eo.source());
            status.put("source_label",eo.sourceMode().equals("replay")?"模拟回放":"真实来源，待联调");
            status.put("edge_id",eo.edgeId());
            status.put("external_device_id",eo.externalDeviceId());
            status.put("broker_id",eo.brokerId());
            return status;
        }
        Binding b=binding(id);
        Map<String,Object> status=repository.status(id);
        if(((Number)status.get("lease_until")).longValue()<=clock.nowMillis() || !Boolean.TRUE.equals(status.get("broker_enabled"))) {
            status.put("connection_state","DISCONNECTED"); status.put("subscribed",false);
        }
        status.put("static_topic",b.topic(false)); status.put("sense_topic",b.topic(true));
        status.put("control_topic",b.controlTopic()); status.put("control_resp_topic",b.controlRespTopic());
        status.put("control_enabled", LingyunControlEnvelope.controllable(b.deviceTypeAbbr()));
        status.put("emergency_stop","设备协议未提供");
        status.put("source_label",b.sourceMode().equals("replay")?"模拟回放":"真实来源，待联调");
        return status;
    }
    private Broker requiredBroker(String id,boolean lock) {
        Broker broker=repository.broker(id,lock);
        if(broker==null) throw new ApiException(HttpStatus.NOT_FOUND,"MQTT_BROKER_NOT_FOUND","MQTT 连接不存在");
        scope(broker.ownerOrgId(),broker.districtId()); return broker;
    }
    private void scope(String org,String district) {
        access.requireTuple(org,district);
        if(!repository.scopeExists(org,district)) throw bad("请选择有效的组织和区域 ID");
    }
    private void validate(BrokerInput p) {
        required(p.name(),128); required(p.host(),255); required(p.allowedCidrs(),2048);
        if(p.host().matches(".*[\\s/@?#].*") || p.port()==null || p.port()<1 || p.port()>65535 || p.tls()==null) throw bad("主机、端口或 TLS 配置无效");
        mode(p.sourceMode()); required(p.ownerOrgId(),36); required(p.districtId(),36);
        if(p.username()!=null && p.username().length()>128) throw bad("用户名过长");
        if(p.credentialRef()!=null && !p.credentialRef().isBlank() && !p.credentialRef().matches("env:[A-Za-z_][A-Za-z0-9_]{0,200}"))
            throw bad("凭据只能填写 env:环境变量名");
    }
    private void validate(Registration p) {
        required(p.deviceNo(),64); required(p.name(),128); required(p.brokerId(),36);
        required(p.ownerOrgId(),36); required(p.districtId(),36); mode(p.sourceMode());
        if((p.vendor()!=null && p.vendor().length()>128) || (p.model()!=null && p.model().length()>128)) throw bad("厂家或型号过长");
        if(eo(p)) { segment(p.edgeId(),64); segment(p.externalDeviceId(),32); return; }
        if(!LingyunEnvelope.PROTOCOL.equals(p.protocolCode()) || !LingyunControlEnvelope.registrable(p.deviceTypeAbbr()))
            throw bad("支持雷达、5G-A、TDOA、AOA、协议破解、RemoteID、诱骗、干扰、驱鸟炮、光电或光电边端");
        segment(p.providerCode(),64); segment(p.externalDeviceId(),128);
    }
    private static boolean eo(Registration p) { return EoEdgeEnvelope.PROTOCOL.equals(p.protocolCode()); }
    private void segment(String value,int max) {
        required(value,max); if(value.matches(".*[/+#\\s\\x00].*")) throw bad("提供方和外部设备编号不能含 Topic 分隔符、通配符或空白");
    }
    private void mode(String value) { if(!"live".equals(value) && !"replay".equals(value)) throw bad("来源须为 replay 或 live"); }
    private void required(String value,int max) { if(value==null || value.isBlank() || value.length()>max || !value.equals(value.trim())) throw bad("必填字段为空、超长或含首尾空格"); }
    private void record(String action,String id) { var actor=AuthContext.require(); audit.record(actor.userId(),actor.account(),action,"mqtt",id,"配置已更新",null); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"MQTT_CONFIG_INVALID",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    private static ApiException versionConflict() { return conflict("VERSION_CONFLICT","配置已变化，请刷新后重试"); }
}
