package com.uav.lowaltitude.integration.mqtt;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import org.springframework.beans.factory.annotation.Value;
import com.uav.lowaltitude.modules.device.application.EoEdgeIngressService;
import com.uav.lowaltitude.modules.device.application.LingyunControlService;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.MqttIngressService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Broker;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** A broker owns one persistent client and one database lease. Never shares the TCP lease table. */
@Component
@ConditionalOnProperty(name="app.mqtt.enabled",havingValue="true",matchIfMissing=true)
public class MqttSessionSupervisor {
    private final MqttRepository repository;
    private final MqttIngressService ingress;
    private final MqttConfigurationService configuration;
    private final MqttNetworkPolicy network;
    private final EnvironmentCredentialResolver credentials;
    private final AppClock clock;
    private final EoEdgeIngressService eoIngress;
    private final EoEdgeRepository eoEdges;
    private final LingyunControlService control;
    private final long heartbeatTimeout;
    private final String owner=UUID.randomUUID().toString();
    private final Map<String,Session> sessions=new HashMap<>();
    private boolean stopped;

    public MqttSessionSupervisor(MqttRepository repository,MqttIngressService ingress,MqttConfigurationService configuration,
            MqttNetworkPolicy network,EnvironmentCredentialResolver credentials,AppClock clock) {
        this(repository,ingress,configuration,network,credentials,clock,null,null,3000L,null);
    }
    public MqttSessionSupervisor(MqttRepository repository,MqttIngressService ingress,MqttConfigurationService configuration,
            MqttNetworkPolicy network,EnvironmentCredentialResolver credentials,AppClock clock,
            EoEdgeIngressService eoIngress,EoEdgeRepository eoEdges, long heartbeatTimeout) {
        this(repository,ingress,configuration,network,credentials,clock,eoIngress,eoEdges,heartbeatTimeout,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MqttSessionSupervisor(MqttRepository repository,MqttIngressService ingress,MqttConfigurationService configuration,
            MqttNetworkPolicy network,EnvironmentCredentialResolver credentials,AppClock clock,
            EoEdgeIngressService eoIngress,EoEdgeRepository eoEdges,
            @Value("${app.eo-edge.heartbeat-timeout-millis:3000}") long heartbeatTimeout,
            LingyunControlService control) {
        this.repository=repository; this.ingress=ingress; this.configuration=configuration;
        this.network=network; this.credentials=credentials; this.clock=clock;
        this.eoIngress=eoIngress; this.eoEdges=eoEdges; this.heartbeatTimeout=heartbeatTimeout;
        this.control=control;
    }
    @Scheduled(fixedDelayString="${app.mqtt.reconcile-millis:1000}")
    public synchronized void reconcile() {
        if(stopped) return;
        try {
            repository.expire(clock.nowMillis());
            if (eoEdges != null) eoEdges.expire(clock.nowMillis(), heartbeatTimeout);
            Map<String,Broker> desired=new HashMap<>();
            for(Broker b:repository.brokers()) if(b.enabled()) desired.put(b.brokerId(),b);
            for(String id:Set.copyOf(sessions.keySet())) {
                if(!desired.containsKey(id)) { close(sessions.remove(id)); repository.release(id,owner,clock.nowMillis()); }
            }
            for(Broker broker:desired.values()) reconcile(broker);
        } catch(RuntimeException unavailable) {
            // Database failure also revokes the local ability to receive; callbacks fail closed.
            sessions.values().forEach(this::close); sessions.clear();
        }
    }
    private void reconcile(Broker broker) {
        String id=broker.brokerId();
        if(!repository.claim(id,owner,clock.nowMillis())) { close(sessions.remove(id)); return; }
        Session session=sessions.computeIfAbsent(id,ignored -> new Session());
        if(session.version!=broker.version()) {
            close(session); session.version=broker.version(); session.retryAt=0; session.failures=0;
        }
        if(clock.nowMillis()<session.retryAt) return;
        try {
            if(session.client==null || !session.client.isConnected()) {
                close(session);
                repository.connection(id,owner,"CONNECTING",null,clock.nowMillis());
                connect(broker,session);
            }
            refreshSubscriptions(broker,session);
            repository.connection(id,owner,"CONNECTED",null,clock.nowMillis());
            session.failures=0;
        } catch(Exception failed) {
            close(session);
            long delay=Math.min(60,1L << Math.min(session.failures++,6))*1000;
            session.retryAt=clock.nowMillis()+delay;
            String code=failed instanceof com.uav.lowaltitude.integration.device.ProtocolException p ? p.code() : "MQTT_CONNECT_OR_RECEIVE_FAILED";
            repository.connection(id,owner,"DISCONNECTED",code,clock.nowMillis());
            if (eoEdges != null) eoEdges.subscribed(id, false);
        }
    }
    private void connect(Broker broker,Session session) throws MqttException {
        configuration.validateConnection(broker);
        // Pin the validated resolution for this attempt; never let Paho resolve the supplied host again.
        InetAddress address=network.resolve(broker).get(0);
        String host=address.getHostAddress();
        String uri=(broker.tls()?"ssl://":"tcp://")+(host.contains(":")?"["+host+"]":host)+":"+broker.port();
        MqttAsyncClient client=new MqttAsyncClient(uri,broker.clientId(),new MemoryPersistence());
        session.client=client;
        client.setManualAcks(true);
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) { /* Scheduler handles retries; never log broker exceptions or secrets. */ }
            public void deliveryComplete(IMqttDeliveryToken token) { }
            public void messageArrived(String topic,MqttMessage message) throws Exception {
                long receivedAt=clock.nowMillis();
                if (topic.startsWith("iot-reporting/cmlc/edge/") && eoIngress != null)
                    eoIngress.receive(broker.brokerId(),owner,topic,message.getPayload(),message.getId(),message.getQos(),
                            message.isRetained(),message.isDuplicate(),receivedAt);
                else if (LingyunControlEnvelope.isControlRespTopic(topic) && control != null)
                    control.receive(broker.brokerId(),owner,topic,message.getPayload(),message.getQos(),
                            message.isRetained(),receivedAt);
                else
                    ingress.receive(broker.brokerId(),owner,topic,message.getPayload(),message.getId(),message.getQos(),
                            message.isRetained(),message.isDuplicate(),receivedAt);
                // Transaction interceptor has committed before receive returns. Throwing above leaves this packet unacknowledged.
                client.messageArrivedComplete(message.getId(),message.getQos());
            }
        });
        MqttConnectOptions options=new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(false); options.setAutomaticReconnect(false);
        options.setConnectionTimeout(5); options.setKeepAliveInterval(10);
        if(broker.username()!=null && !broker.username().isBlank()) options.setUserName(broker.username());
        String password=credentials.resolve(broker.credentialRef());
        char[] passwordChars=password==null?null:password.toCharArray();
        if(passwordChars!=null) options.setPassword(passwordChars);
        if(broker.tls()) {
            options.setHttpsHostnameVerificationEnabled(false);
            options.setSSLHostnameVerifier((ignored,sslSession) -> javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(broker.host(),sslSession));
        }
        try {
            IMqttToken token=client.connect(options); token.waitForCompletion(7000);
            ingress.sessionStarted(broker.brokerId(),owner,token.getSessionPresent());
        } finally { if(passwordChars!=null) Arrays.fill(passwordChars,'\0'); }
    }
    private void refreshSubscriptions(Broker broker,Session session) throws MqttException {
        Set<String> desired=new HashSet<>(),disabled=new HashSet<>();
        var bindings=repository.bindings(broker.brokerId());
        for(var b:bindings) {
            Set<String> target=b.enabled()?desired:disabled;
            target.add(b.topic(false)); target.add(b.topic(true));
            target.add(b.controlRespTopic());
        }
        if (eoEdges != null) {
            for (String topic : eoEdges.reportingTopics(broker.brokerId())) desired.add(topic);
            for (var b : eoEdges.bindings(broker.brokerId()))
                if (!b.enabled()) disabled.add(b.reportingTopic());
        }
        if(session.topics!=null && desired.equals(session.topics)) return;
        // Include disabled bindings after restarts: persistent broker sessions can retain old subscriptions.
        if(session.topics!=null) for(String topic:session.topics) if(!desired.contains(topic)) disabled.add(topic);
        if(!disabled.isEmpty()) session.client.unsubscribe(disabled.toArray(String[]::new)).waitForCompletion(5000);
        if(!desired.isEmpty()) {
            String[] topics=desired.toArray(String[]::new); int[] qos=new int[topics.length]; Arrays.fill(qos,1);
            IMqttToken subscribed=session.client.subscribe(topics,qos); subscribed.waitForCompletion(5000);
            if(Arrays.stream(subscribed.getGrantedQos()).anyMatch(granted -> granted!=1)) throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
        }
        for(var b:bindings) repository.subscribed(b.opsDeviceId(),b.enabled());
        if (eoEdges != null)
            for (var b : eoEdges.bindings(broker.brokerId()))
                eoEdges.subscribedDevice(b.opsDeviceId(), b.enabled() && desired.contains(b.reportingTopic()));
        session.topics=desired;
    }
    public synchronized void publish(String brokerId,String topic,byte[] payload) {
        Session session=sessions.get(brokerId);
        if(session==null || session.client==null || !session.client.isConnected())
            throw new IllegalStateException("MQTT_NOT_CONNECTED");
        try {
            MqttMessage message=new MqttMessage(payload);
            message.setQos(1); message.setRetained(false);
            session.client.publish(topic,message).waitForCompletion(5000);
        } catch(MqttException ex) { throw new IllegalStateException("MQTT_PUBLISH_FAILED", ex); }
    }
    private void close(Session session) {
        if(session==null) return;
        MqttAsyncClient client=session.client; session.client=null; session.topics=null;
        if(client==null) return;
        try { client.disconnectForcibly(0,1000,false); } catch(Exception ignored) { }
        try { client.close(true); } catch(Exception ignored) { }
    }
    @PreDestroy
    public synchronized void shutdown() {
        stopped=true;
        for(var entry:sessions.entrySet()) {
            close(entry.getValue());
            try { repository.release(entry.getKey(),owner,clock.nowMillis()); } catch(RuntimeException ignored) { }
        }
        sessions.clear();
    }
    private static final class Session {
        MqttAsyncClient client;
        Set<String> topics;
        long version=-1,retryAt;
        int failures;
    }
}
