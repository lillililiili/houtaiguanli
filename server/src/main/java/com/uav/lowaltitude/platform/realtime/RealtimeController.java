package com.uav.lowaltitude.platform.realtime;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 已登录会话订阅“数据已变化”信号（SSE）。事件：ready（连接建立）、change（topics 为变化的数据类别，
 * "*" 表示全部重读）。不返回业务数据，鉴权由 BearerAuthFilter 完成。
 */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeController {

    private final RealtimeHub hub;
    private final DataChangeListener listener;

    public RealtimeController(RealtimeHub hub, DataChangeListener listener) {
        this.hub = hub;
        this.listener = listener;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.open(listener.isListening());
    }
}
