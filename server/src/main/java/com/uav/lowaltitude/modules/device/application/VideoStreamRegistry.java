package com.uav.lowaltitude.modules.device.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Process-local QA leases. Restart deliberately invalidates all stream associations. */
@Component
public class VideoStreamRegistry {
    private static final long LEASE_MS = 60_000;
    private final Map<String, Stream> streams = new HashMap<>();
    private final Environment environment;
    private final AppClock clock;
    private final boolean enabled;

    public VideoStreamRegistry(Environment environment, AppClock clock,
                               @Value("${app.video.qa-enabled:false}") boolean enabled) {
        this.environment = environment; this.clock = clock; this.enabled = enabled;
    }
    public boolean enabled() {
        return enabled && environment.acceptsProfiles(Profiles.of("((local & qa) | test) & !prod & !production"));
    }
    public void requireEnabled() {
        if (!enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "VIDEO_QA_DISABLED", "测试视频接入未启用");
    }
    public synchronized Stream register(String taskId, String targetId, String deviceId, String sourceMode) {
        return register(taskId, targetId, deviceId, sourceMode, () -> { });
    }
    public synchronized Stream register(String taskId, String targetId, String deviceId, String sourceMode, Runnable beforeNewRegistration) {
        requireEnabled();
        long now = clock.nowMillis();
        streams.values().removeIf(s -> s.expiresAt() <= now);
        Stream previous = streams.get(taskId);
        long otherDevices = streams.values().stream().filter(s -> !s.deviceId().equals(deviceId)).count();
        if (previous == null && otherDevices >= 128)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VIDEO_STREAM_LIMIT", "测试视频数量已达上限");
        // Audit after validation, before publishing the new lease. Failure leaves the prior lease intact.
        if (previous == null) beforeNewRegistration.run();
        streams.values().removeIf(s -> s.deviceId().equals(deviceId) && !s.taskId().equals(taskId));
        String id = previous == null ? UUID.randomUUID().toString() : previous.streamId();
        Stream stream = new Stream(id, "qa/" + id, taskId, targetId, deviceId, sourceMode, now + LEASE_MS,
                previous != null && previous.observedReady());
        streams.put(taskId, stream);
        return stream;
    }
    public synchronized Stream find(String taskId) {
        if (!enabled()) return null;
        Stream stream = streams.get(taskId);
        if (stream != null && stream.expiresAt() <= clock.nowMillis()) { streams.remove(taskId); return null; }
        return stream;
    }
    public synchronized void remove(String taskId) { streams.remove(taskId); }
    public synchronized void observedReady(String taskId) {
        Stream s = find(taskId);
        if(s != null && !s.observedReady()) streams.put(taskId,new Stream(s.streamId(),s.streamPath(),s.taskId(),
                s.targetId(),s.deviceId(),s.sourceMode(),s.expiresAt(),true));
    }
    public record Stream(String streamId, String streamPath, String taskId, String targetId,
                         String deviceId, String sourceMode, long expiresAt, boolean observedReady) { }
}
