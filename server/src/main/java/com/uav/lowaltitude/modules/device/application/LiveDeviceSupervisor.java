package com.uav.lowaltitude.modules.device.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.device.countermeasure.CountermeasureTcp4ChV20Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarTcpV300Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.PointBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.Rtk;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.modules.device.infrastructure.LiveDeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.time.AppClock;

@Component
@ConditionalOnProperty(prefix = "app.live-device", name = "enabled", havingValue = "true")
public class LiveDeviceSupervisor {

    private static final Logger log = LoggerFactory.getLogger(LiveDeviceSupervisor.class);

    private final LiveDeviceRepository live;
    private final ProtocolDataRepository protocolData;
    private final LiveRadarFrameIngestService radarIngest;
    private final RadarTcpV300Adapter radar;
    private final CountermeasureTcp4ChV20Adapter countermeasure;
    private final ObjectMapper mapper;
    private final AppClock clock;
    private final String ownerId;
    private final long leaseMillis;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "live-device-session");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    public LiveDeviceSupervisor(LiveDeviceRepository live, ProtocolDataRepository protocolData,
                                LiveRadarFrameIngestService radarIngest, RadarTcpV300Adapter radar,
                                CountermeasureTcp4ChV20Adapter countermeasure, ObjectMapper mapper,
                                AppClock clock, AppProperties properties) {
        this.live = live;
        this.protocolData = protocolData;
        this.radarIngest = radarIngest;
        this.radar = radar;
        this.countermeasure = countermeasure;
        this.mapper = mapper;
        this.clock = clock;
        String configured = properties.getLiveDevice().getInstanceId();
        this.ownerId = configured == null || configured.isBlank() ? UUID.randomUUID().toString() : configured.trim();
        this.leaseMillis = Math.max(10, properties.getLiveDevice().getLeaseSeconds()) * 1000L;
    }

    @Scheduled(fixedDelayString = "${app.live-device.reconcile-millis:2000}")
    public void reconcile() {
        Map<String, Map<String, Object>> desired = new HashMap<>();
        for (Map<String, Object> row : live.enabledDevices()) desired.put(text(row, "device_id"), row);
        running.forEach((id, session) -> {
            if (!desired.containsKey(id)) session.keepRunning().set(false);
            if (session.future().isDone()) running.remove(id, session);
        });
        for (Map.Entry<String, Map<String, Object>> entry : desired.entrySet()) {
            if (running.containsKey(entry.getKey())) continue;
            long now = clock.nowMillis();
            String token = UUID.randomUUID().toString();
            if (!live.claim(entry.getKey(), ownerId, token, now, now + leaseMillis)) continue;
            AtomicBoolean keep = new AtomicBoolean(true);
            Future<?> future = executor.submit(() -> run(entry.getValue(), token, keep));
            running.put(entry.getKey(), new Running(token, keep, future));
        }
        protocolData.expireTracks(clock.nowMillis() - 3000L, clock.nowMillis());
    }

    private void run(Map<String, Object> row, String token, AtomicBoolean keep) {
        String deviceId = text(row, "device_id"), protocol = text(row, "protocol_code");
        try {
            protocolData.markConnection(deviceId, protocol, "CONNECTING", null, null, clock.nowMillis(), true);
            String json = configuration(row);
            if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocol)) runRadar(row, json, token, keep);
            else if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(protocol)) runCounter(row, json, token, keep);
            else throw new IllegalStateException("PROTOCOL_UNSUPPORTED: " + protocol);
            live.release(deviceId, ownerId, token, clock.nowMillis());
            protocolData.markConnection(deviceId, protocol, "DISCONNECTED", null, "来源或设备已停用", clock.nowMillis(), false);
        } catch (Exception ex) {
            int failures = live.failureCount(deviceId) + 1;
            long delay = Math.min(60_000L, 1000L << Math.min(failures - 1, 6));
            String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            live.failed(deviceId, ownerId, token, clock.nowMillis() + delay, detail, clock.nowMillis());
            protocolData.markConnection(deviceId, protocol, "OFFLINE", null, detail, clock.nowMillis(), false);
            log.warn("live device session ended: device={}, protocol={}, retry={}ms", deviceId, protocol, delay);
        } finally {
            keep.set(false);
        }
    }

    private void runRadar(Map<String, Object> row, String json, String token, AtomicBoolean keep) throws Exception {
        String deviceId = text(row, "device_id"), sourceId = text(row, "source_id"), deviceNo = text(row, "device_no");
        String sourceCode = text(row, "source_code");
        long[] nextRenew = { 0L };
        radar.monitor(json, () -> keep.get() && live.stillEnabled(deviceId),
                () -> renew(deviceId, token, nextRenew), new RadarTcpV300Adapter.LiveFrameListener() {
                    @Override public void online() {
                        long now = clock.nowMillis();
                        live.success(deviceId, ownerId, token, now + leaseMillis, now);
                        protocolData.markConnection(deviceId, DeviceProtocolCodes.RADAR_TCP_V3_0_0,
                                "ONLINE", "LOGGED_IN", null, now, false);
                    }

                    @Override public void frame(RadarV300Codec.RadarFrame frame, byte[] raw, long receivedAt) {
                        handleRadarFrame(sourceId, deviceId, deviceNo, sourceCode, frame, raw, receivedAt);
                    }

                    @Override public void invalidFrames(long count) {
                        protocolData.addCrcErrors(deviceId, count, clock.nowMillis());
                    }
                });
    }

    private void handleRadarFrame(String sourceId, String deviceId, String deviceNo, String sourceCode,
                                  RadarV300Codec.RadarFrame frame, byte[] raw, long receivedAt) {
        String key = null;
        try {
            if (frame.command() == RadarV300Codec.COMMAND_UPLOAD_TRACK_V3) {
                TrackBatch batch = RadarV300PayloadDecoder.track(frame.payload());
                key = LiveRadarFrameIngestService.trackMessageKey(deviceId, batch);
                if (!radarIngest.ingestTrack(sourceId, deviceId, deviceNo, sourceCode, batch, raw, receivedAt)) return;
            } else if (frame.command() == RadarV300Codec.COMMAND_UPLOAD_TARGET_V3) {
                PointBatch batch = RadarV300PayloadDecoder.points(frame.payload());
                key = messageKey(deviceId, batch.radarBootMicros(), frame.command(), batch.payloadFrameId());
                if (!protocolData.insertInbox(sourceId, deviceId, key, raw, receivedAt)) return;
                protocolData.savePointSummary(deviceId, batch, receivedAt);
            } else if (frame.command() == RadarV300Codec.COMMAND_UPLOAD_RTK) {
                Rtk rtk = RadarV300PayloadDecoder.rtk(frame.payload());
                key = deviceId + ":rtk:" + frame.frameId();
                if (!protocolData.insertInbox(sourceId, deviceId, key, raw, receivedAt)) return;
                protocolData.saveRtk(deviceId, Long.toUnsignedString(frame.frameId()), rtk, receivedAt);
            } else return;
            protocolData.inboxProcessed(deviceId, key, receivedAt);
        } catch (Exception ex) {
            if (key == null) {
                key = deviceId + ":invalid:" + frame.command() + ":" + frame.frameId();
                protocolData.insertInbox(sourceId, deviceId, key, raw, receivedAt);
            }
            protocolData.inboxFailed(deviceId, key, ex.getMessage(), receivedAt);
        }
    }

    private void runCounter(Map<String, Object> row, String json, String token, AtomicBoolean keep) throws Exception {
        String deviceId = text(row, "device_id");
        int poll = number(row, "poll_interval_millis", 5000);
        long[] nextRenew = { 0L };
        String sessionConfiguration = json;
        boolean encodingFixed = false;
        while (keep.get() && live.stillEnabled(deviceId)) {
            CountermeasureTcp4ChV20Adapter.ProbeResult result = countermeasure.query(sessionConfiguration);
            if (!encodingFixed) {
                sessionConfiguration = withCounterEncoding(sessionConfiguration, result.encoding().name());
                encodingFixed = true;
            }
            protocolData.saveCountermeasureState(deviceId, result.encoding().name(),
                    result.state().rawStatusWord(), result.state().channels(), clock.nowMillis());
            renew(deviceId, token, nextRenew);
            live.success(deviceId, ownerId, token, clock.nowMillis() + leaseMillis, clock.nowMillis());
            long until = System.currentTimeMillis() + poll;
            while (keep.get() && System.currentTimeMillis() < until) Thread.sleep(Math.min(1000, until - System.currentTimeMillis()));
        }
    }

    private String withCounterEncoding(String json, String encoding) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(json);
            ((ObjectNode) root.path("protocol_configuration")).put("wire_encoding", encoding);
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("无法固定反制设备会话编码", ex);
        }
    }

    private void renew(String deviceId, String token, long[] nextRenew) {
        long now = clock.nowMillis();
        if (now < nextRenew[0]) return;
        if (!live.renew(deviceId, ownerId, token, now + leaseMillis, now))
            throw new IllegalStateException("设备连接租约已丢失");
        nextRenew[0] = now + leaseMillis / 3;
    }

    private String configuration(Map<String, Object> row) {
        try {
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("transport", text(row, "transport")); connection.put("host", text(row, "host"));
            connection.put("port", row.get("port")); connection.put("timeout_millis", row.get("timeout_millis"));
            connection.put("credential_ref", text(row, "credential_ref"));
            Map<String, Object> protocol = new LinkedHashMap<>();
            if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(text(row, "protocol_code"))) {
                protocol.put("login_role", text(row, "login_role"));
                protocol.put("recognition_code_ref", text(row, "recognition_code_ref"));
                protocol.put("rtk_enabled", bool(row, "rtk_enabled"));
                protocol.put("coordinate_transform_enabled", bool(row, "coordinate_transform_enabled"));
            } else {
                protocol.put("device_address", number(row, "device_address", 1));
                protocol.put("wire_encoding", text(row, "wire_encoding") == null ? "AUTO" : text(row, "wire_encoding"));
                protocol.put("poll_interval_millis", number(row, "poll_interval_millis", 5000));
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("connection", connection); root.put("protocol_configuration", protocol);
            root.put("allowed_cidrs", text(row, "allowed_cidrs"));
            root.put("credential_ref", text(row, "source_credential_ref"));
            return mapper.writeValueAsString(root);
        } catch (Exception ex) { throw new IllegalStateException("无法序列化 live 设备配置", ex); }
    }

    @PreDestroy
    public void close() {
        running.values().forEach(value -> value.keepRunning().set(false));
        executor.shutdownNow();
    }

    private static String messageKey(String deviceId, long boot, int command, String frameId) {
        return deviceId + ":" + Long.toUnsignedString(boot) + ":" + Integer.toUnsignedString(command) + ":" + frameId;
    }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static boolean bool(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value)); }
    private static int number(Map<String, Object> row, String key, int fallback) { Object value = row.get(key); return value instanceof Number n ? n.intValue() : fallback; }
    private record Running(String token, AtomicBoolean keepRunning, Future<?> future) { }
}
