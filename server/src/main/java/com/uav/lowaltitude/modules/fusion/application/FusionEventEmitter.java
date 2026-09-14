package com.uav.lowaltitude.modules.fusion.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionEventRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合事件发射（决策 8-3）：写独立只增表 fusion_event，不进设备 outbox，不触发任何发送 Worker。
 * 事件与业务写在同一事务内提交；payload 只放安全摘要（目标 ID、状态、等级），不放原始观测。
 */
@Component
public class FusionEventEmitter {
    public static final String STATUS_STABLE = "STATUS_STABLE", MERGED = "MERGED", SPLIT = "SPLIT", UNDETERMINED = "UNDETERMINED", CLASS_REVISED = "CLASS_REVISED";
    private final FusionEventRepository repository;
    private final AppClock clock;
    private final ObjectMapper json;

    public FusionEventEmitter(FusionEventRepository repository, AppClock clock, ObjectMapper json) {
        this.repository = repository; this.clock = clock; this.json = json;
    }

    public void emit(String eventType, String targetId, OffsetDateTime occurredAt, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_type", eventType);
        body.put("target_id", targetId);
        body.putAll(payload);
        repository.insert(UUID.randomUUID().toString(), eventType, targetId, write(body), occurredAt, clock.now().atOffset(ZoneOffset.UTC));
    }

    /** 只在本轨迹段内首次转 STABLE 时发一次；重复帧不重复发。 */
    public void stableOnce(String targetId, OffsetDateTime occurredAt, OffsetDateTime trackStartedAt, Map<String, Object> payload) {
        if (stableAlreadyEmitted(targetId, occurredAt, trackStartedAt)) return;
        emit(STATUS_STABLE, targetId, occurredAt, payload);
    }

    /**
     * 本轨迹段内是否已经发过 STATUS_STABLE（阶段 10 只加）：摘要 payload 要查目标编号、告警与风险，
     * 调用方先问这一句再组装，避免 STABLE 之后的每一帧都白查三张表再被 stableOnce 丢掉。
     */
    public boolean stableAlreadyEmitted(String targetId, OffsetDateTime occurredAt, OffsetDateTime trackStartedAt) {
        return repository.existsSince(targetId, STATUS_STABLE, trackStartedAt == null ? occurredAt : trackStartedAt);
    }

    private String write(Map<String, Object> payload) {
        try { return json.writeValueAsString(payload); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("fusion event payload unserializable", ex); }
    }
}
