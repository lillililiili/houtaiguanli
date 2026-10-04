package com.uav.lowaltitude.platform.realtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 实时变化信号的 SSE 分发。只推“哪类数据变了”，不推业务数据；前端收到后经原有接口按权限重读。
 * 连接有上限和超时，过期后由前端重连并重新鉴权。
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AppClock clock;
    private final int maxConnections;
    private final long timeoutMillis;

    public RealtimeHub(AppClock clock,
            @Value("${app.realtime.max-connections:200}") int maxConnections,
            @Value("${app.realtime.stream-timeout-millis:600000}") long timeoutMillis) {
        this.clock = clock;
        this.maxConnections = maxConnections;
        this.timeoutMillis = timeoutMillis;
    }

    public SseEmitter open(boolean listening) {
        if (emitters.size() >= maxConnections) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REALTIME_BUSY", "实时连接已满，页面将按原方式刷新");
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> { emitters.remove(emitter); emitter.complete(); });
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("listening", listening);
        ready.put("at", clock.nowMillis());
        send(emitter, SseEmitter.event().name("ready").data(ready));
        return emitter;
    }

    public void publish(Set<String> topics) {
        if (topics.isEmpty() || emitters.isEmpty()) return;
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("topics", topics.stream().sorted().toList());
        change.put("at", clock.nowMillis());
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name("change").data(change));
        }
    }

    /** 心跳让代理和浏览器保持连接，也及时清理已断开的连接。 */
    @Scheduled(fixedDelayString = "${app.realtime.heartbeat-millis:20000}")
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().comment("ping"));
        }
    }

    int connectionCount() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            log.debug("realtime emitter dropped: {}", e.getMessage());
        }
    }
}
