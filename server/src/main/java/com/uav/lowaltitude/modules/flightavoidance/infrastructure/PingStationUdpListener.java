package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.UnmatchedStation;
import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 收 pingStation3 推送的 UDP 航班数据（JSON）。
 *
 * 接收机在它的网页里把发送地址填成本服务的地址和 {@code app.adsb.pingstation.udp-port}。
 * 每条报文带机身识别码，对上登记的接收机才收；没登记的识别码记在「未匹配站点」里，登记页用来提示，不存数据。
 * 端口被占用时只记日志，不影响系统启动。
 *
 * 真实接收机 350 公里内一两百架航班、每架每秒一条，一条一个事务会让数据库每秒提交几百次。
 * 所以报文先攒在内存里（每架只留时间最新的一条），每 {@code app.adsb.pingstation.flush-millis}（默认 1 秒）
 * 按接收机合并成一个事务写库，「最近收到数据」也只在这时更新一次。写库最多晚 1 秒。
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
    private final long flushMillis;
    private final Map<String, UnmatchedStation> unmatched = new ConcurrentHashMap<>();
    /** 攒着还没写库的报文，按识别码分；只在 {@link #handle} 和 {@link #flush} 里持锁访问。 */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private volatile DatagramSocket socket;
    private volatile Thread thread;
    private volatile ScheduledExecutorService flusher;

    public PingStationUdpListener(AdsbReceiverService service, ObjectMapper json, AppClock clock,
            @Value("${app.adsb.pingstation.enabled:true}") boolean enabled,
            @Value("${app.adsb.pingstation.udp-port:30021}") int configuredPort,
            @Value("${app.adsb.pingstation.flush-millis:1000}") long flushMillis) {
        this.service = service; this.json = json; this.clock = clock; this.enabled = enabled; this.configuredPort = configuredPort;
        this.flushMillis = Math.max(100L, flushMillis);
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
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pingstation-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flushQuietly, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
        thread = new Thread(this::loop, "pingstation-udp");
        thread.setDaemon(true);
        thread.start();
        log.info("航班接收机 UDP 监听已开：端口 {}，每 {} 毫秒合并写一次", port(), flushMillis);
    }

    @Override public void stop() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
        thread = null;
        ScheduledExecutorService f = flusher;
        flusher = null;
        if (f != null) {
            // 等正在写的那一批写完再收尾，否则连接池先关，最后一秒的位置会丢。
            f.shutdown();
            try {
                if (!f.awaitTermination(2, TimeUnit.SECONDS)) f.shutdownNow();
            } catch (InterruptedException e) {
                f.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flushQuietly();
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

    /**
     * 一条报文：只解析并攒起来，由 {@link #flush} 写库；测试也直接调。
     * 返回攒下的航班数（状态报文为 0），不是 pingStation 格式为 -2。识别码登不登记要到写库时才知道。
     */
    public int handle(String payload, String from) throws IOException {
        long now = clock.nowMillis();
        PingStationMessage message = PingStationMessage.parse(json.readTree(payload), now);
        if (message == null) return -2;
        synchronized (pending) {
            Pending station = pending.computeIfAbsent(message.stationGuid(), guid -> new Pending());
            station.messages++;
            station.from = from;
            station.lastSeenAt = now;
            for (FlightCheck.Aircraft a : message.aircraft()) {
                FlightCheck.Aircraft old = station.aircraft.get(a.icao24());
                if (old == null || old.observedAt() <= a.observedAt()) station.aircraft.put(a.icao24(), a);
            }
        }
        return message.aircraft().size();
    }

    /**
     * 把攒下的报文写库：每台接收机一个事务，每架航班只写时间最新的一条。
     * 识别码没登记的记为未匹配站点。写库失败只记日志，这一秒的数据丢掉，下一秒的报文会把位置补回来。
     * 返回这次写库的接收机数。
     */
    public int flush() {
        Map<String, Pending> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return 0;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        long now = clock.nowMillis();
        int stations = 0;
        for (Map.Entry<String, Pending> entry : batch.entrySet()) {
            String guid = entry.getKey();
            Pending station = entry.getValue();
            try {
                boolean matched = station.aircraft.isEmpty() ? service.stationHeartbeat(guid, now)
                        : service.receiveFromStation(guid, new ArrayList<>(station.aircraft.values()), now) >= 0;
                if (matched) { unmatched.remove(guid); stations++; }
                else noteUnmatched(guid, station);
            } catch (RuntimeException e) {
                log.warn("航班接收机 {} 的 {} 条报文写库失败，这一批丢掉：{}", guid, station.messages, e.toString());
            }
        }
        return stations;
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("航班接收机报文写库失败：{}", e.toString());
        }
    }

    private void noteUnmatched(String guid, Pending station) {
        unmatched.compute(guid, (k, old) -> new UnmatchedStation(guid, station.from, station.lastSeenAt,
                (old == null ? 0 : old.messages()) + station.messages));
        if (unmatched.size() > MAX_UNMATCHED)
            unmatched.values().stream().min(Comparator.comparingLong(UnmatchedStation::lastSeenAt))
                    .ifPresent(oldest -> unmatched.remove(oldest.stationGuid()));
    }

    /** 一台接收机攒下的报文：每架航班只留时间最新的一条。 */
    private static final class Pending {
        final Map<String, FlightCheck.Aircraft> aircraft = new LinkedHashMap<>();
        int messages;
        String from;
        long lastSeenAt;
    }
}
