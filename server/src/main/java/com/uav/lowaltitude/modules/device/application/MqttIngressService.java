package com.uav.lowaltitude.modules.device.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class MqttIngressService {
    private final MqttRepository repository;
    private final AppClock clock;
    public MqttIngressService(MqttRepository repository,AppClock clock) { this.repository=repository; this.clock=clock; }

    /** Return only after the Spring transaction commits; the MQTT callback acknowledges afterwards. */
    @Transactional
    public void receive(String broker,String owner,String topic,byte[] bytes,int packet,int qos,boolean retained,boolean duplicate,long receivedAt) {
        if(!repository.fence(broker,owner,clock.nowMillis())) throw new IllegalStateException("MQTT_LEASE_LOST");
        var config=repository.broker(broker,false);
        if(config==null || !config.enabled()) throw new IllegalStateException("MQTT_DISABLED");
        Binding binding=null;
        String outcome="REJECTED",reason;
        try {
            LingyunEnvelope m=LingyunEnvelope.decode(topic,bytes);
            binding=repository.bindings(broker).stream().filter(b -> b.topic(m.sensing()).equals(topic)).findFirst().orElse(null);
            if(binding==null) throw new LingyunEnvelope.Rejected("DEVICE_NOT_REGISTERED");
            binding=repository.binding(binding.opsDeviceId(),true);
            if(!binding.enabled()) throw new LingyunEnvelope.Rejected("DEVICE_DISABLED");
            if(!binding.sourceMode().equals(config.sourceMode())) throw new LingyunEnvelope.Rejected("SOURCE_MODE_MISMATCH");
            if(retained) throw new LingyunEnvelope.Rejected("RETAINED_NOT_REALTIME");
            if(qos!=1) throw new LingyunEnvelope.Rejected("QOS1_REQUIRED");
            boolean repeated=repository.transportDuplicate(broker,packet,topic,m.hash(),duplicate);
            if(m.sensing()) {
                String key=m.ptTime()+":"+m.msgCnt();
                String existing=repository.existingHash(binding.source(),key);
                if(existing!=null) {
                    outcome=existing.equals(m.hash())?"DUPLICATE":"CONFLICT";
                    reason=existing.equals(m.hash())?"SAME_MESSAGE":"KEY_PAYLOAD_CONFLICT";
                } else if(!repository.inbox(binding,m,receivedAt)) {
                    existing=repository.existingHash(binding.source(),key);
                    outcome=existing!=null && existing.equals(m.hash())?"DUPLICATE":"CONFLICT";
                    reason=outcome.equals("DUPLICATE")?"SAME_MESSAGE":"KEY_PAYLOAD_CONFLICT";
                } else {
                    repository.sense(binding,m,receivedAt);
                    outcome="ACCEPTED"; reason="INBOX_RECEIVED";
                }
            } else if(repeated) { outcome="DUPLICATE"; reason="MQTT_REDELIVERY";
            } else if(m.ptTime()!=null && binding.lastStaticPtTime()!=null && m.ptTime()<=binding.lastStaticPtTime()) {
                outcome="IGNORED"; reason="STALE_STATIC";
            } else {
                repository.heartbeat(binding,m,receivedAt); outcome="ACCEPTED"; reason="STATIC_UPDATED";
            }
        } catch(LingyunEnvelope.Rejected ex) { reason=ex.getMessage(); }
        repository.diagnostic(broker,binding==null?null:binding.opsDeviceId(),topic,LingyunEnvelope.hash(bytes),receivedAt,outcome,reason);
    }
    @Transactional
    public void sessionStarted(String broker,String owner,boolean existingSession) {
        if(!repository.fence(broker,owner,clock.nowMillis())) throw new IllegalStateException("MQTT_LEASE_LOST");
        if(!existingSession) repository.resetReceipts(broker);
    }
}
