package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.UnmatchedStation;
import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 收 pingStation3 推送的 UDP 航班数据（JSON）。
 *
 * 接收机在它的网页里把发送地址填成本服务的地址和 {@code app.adsb.pingstation.udp-port}。
 * 每条报文带机身识别码，对上登记的接收机才收；没登记的识别码记在「未匹配站点」里，登记页用来提示，不存数据。
 * 端口被占用时只记日志，不影响系统启动。
 */
@Component
public class PingStationUdpListener implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PingStationUdpListener.class);
    private static final int MAX_UNMATCHED = 20;

    private final AdsbReceiverService service;
    private final ObjectMapper json;
    private final AppClock clock;
    private final boolean enabled;
    private final int configuredPort;
    private final Map<String, UnmatchedStation> unmatched = new ConcurrentHashMap<>();
    private volatile DatagramSocket socket;
    private volatile Thread thread;

    public PingStationUdpListener(AdsbReceiverService service, ObjectMapper json, AppClock clock,
            @Value("${app.adsb.pingstation.enabled:true}") boolean enabled,
            @Value("${app.adsb.pingstation.udp-port:30021}") int configuredPort) {
        this.service = service; this.json = json; this.clock = clock; this.enabled = enabled; this.configuredPort = configuredPort;
    }

    /** 实际监听的端口；没在监听时为 -1。 */
    public int port() { DatagramSocket s = socket; return s == null || s.isClosed() ? -1 : s.getLocalPort(); }

    /** 最近送来数据但识别码没登记的站点，最近的在前。 */
    public List<UnmatchedStation> unmatchedStations() {
        return unmatched.values().stream().sorted(Comparator.comparingLong(UnmatchedStation::lastSeenAt).reversed()).toList();
    }

    @Override public void start() {
        if (!enabled || thread != null) return;
        try {
            socket = new DatagramSocket(new InetSocketAddress(configuredPort));
        } catch (SocketException e) {
            log.warn("航班接收机 UDP 端口 {} 打不开，收不到 pingStation 数据：{}", configuredPort, e.getMessage());
            socket = null;
            return;
        }
        thread = new Thread(this::loop, "pingstation-udp");
        thread.setDaemon(true);
        thread.start();
        log.info("航班接收机 UDP 监听已开：端口 {}", port());
    }

    @Override public void stop() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
        thread = null;
    }

    @Override public boolean isRunning() { return thread != null; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    private void loop() {
        byte[] buffer = new byte[65_535];
        while (true) {
            DatagramSocket s = socket;
            if (s == null || s.isClosed()) return;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                s.receive(packet);
            } catch (IOException e) {
                if (socket == null) return;
                log.warn("航班接收机 UDP 读取失败：{}", e.getMessage());
                continue;
            }
            String from = packet.getAddress() == null ? "?" : packet.getAddress().getHostAddress();
            try {
                handle(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8), from);
            } catch (Exception e) {
                log.warn("航班接收机报文处理失败（来自 {}）：{}", from, e.toString());
            }
        }
    }

    /** 一条报文；测试也直接调。返回收下的航班数，识别码没登记为 -1，不是 pingStation 格式为 -2。 */
    public int handle(String payload, String from) throws IOException {
        long now = clock.nowMillis();
        PingStationMessage message = PingStationMessage.parse(json.readTree(payload), now);
        if (message == null) return -2;
        int accepted = message.status() ? (service.stationHeartbeat(message.stationGuid(), now) ? 0 : -1)
                : service.receiveFromStation(message.stationGuid(), message.aircraft(), now);
        if (accepted < 0) noteUnmatched(message.stationGuid(), from, now);
        else unmatched.remove(message.stationGuid());
        return accepted;
    }

    private void noteUnmatched(String guid, String from, long now) {
        unmatched.compute(guid, (k, old) -> new UnmatchedStation(guid, from, now, old == null ? 1 : old.messages() + 1));
        if (unmatched.size() > MAX_UNMATCHED)
            unmatched.values().stream().min(Comparator.comparingLong(UnmatchedStation::lastSeenAt))
                    .ifPresent(oldest -> unmatched.remove(oldest.stationGuid()));
    }
}
