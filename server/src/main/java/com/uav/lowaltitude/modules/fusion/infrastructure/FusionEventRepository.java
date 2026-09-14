package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** fusion_event 只增：这里只有 INSERT 与用于判断“是否已发过”的读取，没有 UPDATE/DELETE 路径。 */
@Repository
public class FusionEventRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FusionEventRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public void insert(String eventId, String eventType, String targetId, String payloadJson, OffsetDateTime occurredAt, OffsetDateTime createdAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", eventId); p.put("type", eventType); p.put("target", targetId); p.put("payload", payloadJson); p.put("occurred", occurredAt); p.put("created", createdAt);
        jdbc.update("INSERT INTO fusion_event (event_id,event_type,target_id,payload,occurred_at,created_at) VALUES (:id,:type,:target,CAST(:payload AS JSON),:occurred,:created)", p);
    }

    /** 某类型事件在 since 之后是否已经对该目标发过：用于“状态转 STABLE 只发一次”。 */
    public boolean existsSince(String targetId, String eventType, OffsetDateTime since) {
        Map<String, Object> p = new HashMap<>();
        p.put("target", targetId); p.put("type", eventType); p.put("since", since);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM fusion_event WHERE target_id=:target AND event_type=:type AND occurred_at>=:since", p, Long.class);
        return count != null && count > 0;
    }
}
