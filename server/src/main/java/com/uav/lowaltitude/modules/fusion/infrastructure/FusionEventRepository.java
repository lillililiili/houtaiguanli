package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 多个目标某类型事件最近一次的发生时刻，一次取回（ZT-06）；"since 之后发过" 即 "最近一次不早于 since"，与 {@link #existsSince} 等价。
     * 每个目标单独取 MAX（相关子查询走 target_id 索引），没发过的目标不在结果里。
     */
    public Map<String, OffsetDateTime> latestOccurredAt(Collection<String> targetIds, String eventType) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) return out;
        jdbc.query("SELECT t.target_id,(SELECT MAX(e.occurred_at) FROM fusion_event e WHERE e.target_id=t.target_id AND e.event_type=:type) AS latest"
                + " FROM target t WHERE t.target_id IN (:targets)", Map.of("targets", List.copyOf(targetIds), "type", eventType), (ResultSet rs) -> {
                    OffsetDateTime latest = FusionConfigRepository.time(rs, "latest");
                    if (latest != null) out.put(rs.getString("target_id"), latest);
                });
        return out;
    }
}
