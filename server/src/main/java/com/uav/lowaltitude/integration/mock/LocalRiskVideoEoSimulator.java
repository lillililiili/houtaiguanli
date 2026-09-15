package com.uav.lowaltitude.integration.mock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;

/** Local-only Protocol C device: it keeps one mock EO online and acknowledges tracking commands for the video demo. */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class LocalRiskVideoEoSimulator {
    private static final String URI = "tcp://127.0.0.1:1883";
    private static final Map<String,String> DEVICES = devices();
    private final Map<String,Integer> workStates = new java.util.concurrent.ConcurrentHashMap<>();
    private static Map<String,String> devices() {
        Map<String,String> values=new LinkedHashMap<>();
        values.put(LocalMqttSimSeeder.RISK_VIDEO_DEVICE_NO,LocalMqttSimSeeder.RISK_VIDEO_EDGE_ID);
        for(var camera:LocalAirspaceRiskDemoSeeder.CAMERAS)values.put(camera.device(),camera.edge());
        return Map.copyOf(values);
    }
    private static final Map<String, Object> CAMERA = Map.of("hfov", 5.6, "vfov", 3.2, "focalLen", 88,
            "detectDist", 164.4, "zoomIndex", 3, "streamMode", "LOCAL_SIMULATED");

    private final ObjectMapper json;
    private MqttAsyncClient client;

    public LocalRiskVideoEoSimulator(ObjectMapper json) { this.json = json; }

    @Scheduled(fixedDelay = 1000)
    public synchronized void keepOnline() {
        try {
            connect();
            for(String device:DEVICES.keySet())publish(device,"HeartBeat",heartbeat(device));
        } catch (Exception ignored) {
            close(); // The local broker may not be up yet; retry on the next tick.
        }
    }

    private void connect() throws Exception {
        if (client != null && client.isConnected()) return;
        close();
        client = new MqttAsyncClient(URI, "local-risk-video-eo-" + UUID.randomUUID(), new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            @Override public void messageArrived(String topic, MqttMessage message) {
                for(String device:DEVICES.keySet()) if (("iot-dispatcher/cmlc/edge/"+device).equals(topic)) reply(device,message.getPayload());
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true); options.setConnectionTimeout(3); options.setKeepAliveInterval(10);
        client.connect(options).waitForCompletion(4000);
        for(String device:DEVICES.keySet()) client.subscribe("iot-dispatcher/cmlc/edge/"+device, 1).waitForCompletion(4000);
    }

    private void reply(String device, byte[] payload) {
        try {
            Map<String, Object> root = json.readValue(payload, new TypeReference<>() { });
            Object event = root.get("event"), rawMetadata = root.get("metadata");
            if (!(event instanceof String type) || !(rawMetadata instanceof Map<?, ?>)) return;
            @SuppressWarnings("unchecked") Map<String, Object> metadata = new LinkedHashMap<>((Map<String, Object>) rawMetadata);
            if (!"BeginTracking".equals(type) && !"EndTracking".equals(type) && !"CameraStatus".equals(type)) return;
            metadata.put("deviceId", device);
            metadata.put("codeStatus", 200);
            if (!"CameraStatus".equals(type)) workStates.put(device,"EndTracking".equals(type) ? 0 : 1);
            metadata.put("workState",workStates.getOrDefault(device,0));
            metadata.put("cameraStatus", CAMERA);
            publish(device,type, metadata);
        } catch (Exception ignored) { }
    }

    private Map<String, Object> heartbeat(String device) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("deviceId", device);
        metadata.put("codeStatus", 200); metadata.put("workState", workStates.getOrDefault(device,0)); metadata.put("cameraStatus", CAMERA);
        return metadata;
    }

    private void publish(String device,String event, Map<String, Object> metadata) throws Exception {
        if (client == null || !client.isConnected()) return;
        client.publish("iot-reporting/cmlc/edge/"+DEVICES.get(device), EoEdgeEnvelope.encode(event, DEVICES.get(device),
                System.currentTimeMillis(), metadata), 1, false).waitForCompletion(4000);
    }

    @PreDestroy public synchronized void shutdown() { close(); }
    private void close() {
        if (client == null) return;
        try { client.disconnectForcibly(0, 1000, false); } catch (Exception ignored) { }
        try { client.close(true); } catch (Exception ignored) { }
        client = null;
    }
}
