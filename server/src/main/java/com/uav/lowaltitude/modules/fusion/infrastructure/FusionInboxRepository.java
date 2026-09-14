package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.ingest.InboxSourceRouter;

/**
 * 融合摄取的 inbox 视图：只领取我们真的能解释的来源前缀（阶段 8.5 起为 replay: / lingyun: / eo-edge: / live-radar:）
 * 且带 source_id/payload 的行；ops 的 live-device:* 行由设备模块自己的 processing_status/ops_lease_* 处理，这里一概不碰。
 * 白名单与 {@code InboxSourceRouter} 的映射器一一对应：领了却没有映射器的帧只会变成 FAILED，白占重试次数。
 * 领取是条件更新（RECEIVED → PROCESSING，租约到期的 PROCESSING 可被重新领取），外层 WHERE 再次校验状态，
 * PostgreSQL 会在行锁释放后按最新行版本重查，因此两个并发领取者不会拿到同一行。
 */
@Repository
public class FusionInboxRepository {
    public static final String REPLAY_SOURCE_PREFIX = "replay:";
    public static final String LIVE_RADAR_SOURCE_PREFIX = "live-radar:";

    private final NamedParameterJdbcTemplate jdbc;
    private final InboxSourceRouter router;
    private final FusionProperties properties;

    public FusionInboxRepository(JdbcTemplate jdbcTemplate, InboxSourceRouter router, FusionProperties properties) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.router = router;
        this.properties = properties;
    }

    /**
     * 能领取的来源前缀 = 已注册映射器的前缀，减去关着开关的实测雷达。
     * 清单取自 {@link InboxSourceRouter}，不再手工维护第二份：领了却没有映射器的帧只会白白耗掉重试次数。
     * 实测雷达提升默认关闭，关闭时**连领都不领**——领了就会解析、丢弃并置为已处理，
     * `fusion_attempts` 被推到上限后由 {@link #failExhausted} 永久判成毒帧；一个纯配置开关不该有不可逆的数据后果。
     */
    List<String> claimablePrefixes() {
        List<String> prefixes = new ArrayList<>();
        for (String prefix : router.prefixes()) {
            if (LIVE_RADAR_SOURCE_PREFIX.equals(prefix) && !properties.getLivePromotion().isEnabled()) continue;
            prefixes.add(prefix);
        }
        if (prefixes.isEmpty()) throw new IllegalStateException("没有可领取的来源前缀：至少要注册一个映射器");
        return List.copyOf(prefixes);
    }

    /** 展开成 SQL 的 (source LIKE :p0 OR source LIKE :p1 ...)：IN 用不了前缀匹配。 */
    private String prefixSql(List<String> prefixes) {
        StringBuilder sql = new StringBuilder("(");
        for (int i = 0; i < prefixes.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("source LIKE :prefix").append(i);
        }
        return sql.append(")").toString();
    }

    private void putPrefixes(Map<String, Object> parameters, List<String> prefixes) {
        for (int i = 0; i < prefixes.size(); i++) parameters.put("prefix" + i, prefixes.get(i) + "%");
    }

    public record InboxRow(String inboxId, String source, String sourceMsgId, String sourceId, long receivedAtMillis, String payloadJson) { }

    /**
     * 兼容旧签名：不限次数（仅测试/种子直调使用）。
     *
     * <b>这里的 @Transactional 不能省（决策 15-40）</b>：本方法是 this 自调用四参版，
     * Spring 的事务代理对自调用不生效——四参上的注解管不到这条路径。种子与并发用例走的都是三参，
     * 少了它这两步就各自 autocommit、第一步的行锁立刻释放，并发领取者又会选到同一批。
     * 实测过：只删掉这一个注解，`FusionPipelineReplayTest` 里 claim 就跑在事务外了。
     */
    @Transactional
    public List<InboxRow> claim(long nowMillis, int batch, long leaseMillis) {
        return claim(nowMillis, batch, leaseMillis, Integer.MAX_VALUE);
    }

    /**
     * 排序键是 (received_at, ingest_seq) 而不是 (received_at, inbox_id)：inbox_id 是随机 UUID，
     * 同一毫秒到达的多源帧会按随机顺序处理，同一份数据集重放得不到同一份结果（决策 8.5-29）。
     * ingest_seq 只保证"写入先后"可复现，不代表设备的真实到达先后——同毫秒的两台设备没有事实上的先后，
     * 因此管线的结论本就不应依赖同毫秒内的处理顺序，稳定排序是为了让回放可复现，不是为了给顺序赋予含义。
     *
     * 领取时把 fusion_attempts 加一，且只领 fusion_attempts < maxAttempts 的行：
     * 租约过期的 PROCESSING 行可以被重领，但同一毒帧最多重领 maxAttempts 次，之后由 {@link #failExhausted} 置 FAILED。
     *
     * **领取分两步，且必须在同一个事务里（决策 15-40）。**
     *
     * 第一步 `SELECT ... FOR UPDATE SKIP LOCKED` 把这一批 id 锁下来（决策 15-38：没有 SKIP LOCKED，
     * 两个领取者会选中同一批，后到的在行锁上等、等到了重检已不成立，于是领到 **0 行**而不是"跳过去领下一批"；
     * CI 慢机器上必现，本机单跑全看时序运气）。第二步只更新这批 id。
     *
     * **为什么不能把这个 SELECT 塞回 UPDATE 的子查询里**：PostgreSQL 会把它计划成 Nested Loop Semi Join，
     * 子查询对每一行外层记录**重新执行**，而 LockRows 会把本语句刚改成 PROCESSING 的行剔掉、再取"下 20 行"——
     * 循环下来 batch=20 一次领走 40 行（真 PG 上实测 UPDATE 40）。加 FOR UPDATE 之前子查询是一次性求值，
     * 所以那时不会超领。也就是说"后到者领 0 行"与"超领一倍"是同一处 SQL 的两种病，不是修一个引出另一个。
     * CTE 物化（`WITH picked AS MATERIALIZED ... UPDATE ... FROM picked`）在 PG 上验证可行，
     * 但 H2 没有 `UPDATE ... FROM`，也未必认 `AS MATERIALIZED`——两步法两边都能跑，也更直白。
     *
     * 同一事务内锁一直持有到提交，所以第一步选中的行不会被别人抢走。
     * 第二步的 status/attempts 重检**保留**：锁挡得住并发领取者，挡不住"选完到更新之间被 failExhausted
     * 之类改掉"，两道防线管的不是同一件事。
     */
    @Transactional
    public List<InboxRow> claim(long nowMillis, int batch, long leaseMillis, int maxAttempts) {
        String token = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("token", token); p.put("until", nowMillis + leaseMillis); p.put("now", nowMillis); p.put("batch", batch);
        p.put("max", maxAttempts);
        List<String> prefixes = claimablePrefixes();
        putPrefixes(p, prefixes);
        List<String> ids = jdbc.queryForList("SELECT inbox_id FROM inbox_message"
                + " WHERE (status='RECEIVED' OR (status='PROCESSING' AND lease_until<:now))"
                + " AND fusion_attempts<:max AND " + prefixSql(prefixes) + " AND source_id IS NOT NULL AND payload IS NOT NULL"
                + " ORDER BY received_at ASC, ingest_seq ASC FETCH FIRST :batch ROWS ONLY FOR UPDATE SKIP LOCKED", p, String.class);
        if (ids.isEmpty()) return List.of();
        p.put("ids", ids);
        int claimed = jdbc.update("UPDATE inbox_message SET status='PROCESSING', lease_token=:token, lease_until=:until, fusion_attempts=fusion_attempts+1"
                + " WHERE inbox_id IN (:ids)"
                + " AND (status='RECEIVED' OR (status='PROCESSING' AND lease_until<:now)) AND fusion_attempts<:max", p);
        if (claimed == 0) return List.of();
        return jdbc.query("SELECT inbox_id,source,source_msg_id,source_id,received_at,CAST(payload AS VARCHAR) AS payload_text FROM inbox_message WHERE lease_token=:token ORDER BY received_at ASC, ingest_seq ASC",
                Map.of("token", token), (rs, i) -> new InboxRow(rs.getString("inbox_id"), rs.getString("source"), rs.getString("source_msg_id"), rs.getString("source_id"),
                        rs.getLong("received_at"), rs.getString("payload_text")));
    }

    /** 租约已过期且领取次数已耗尽的行统一置 FAILED（processed_at 必须同时写，见 ck_stage2_inbox_processed_at）。返回处理行数。 */
    public int failExhausted(long nowMillis, int maxAttempts) {
        Map<String, Object> p = new HashMap<>();
        p.put("now", nowMillis); p.put("max", maxAttempts);
        List<String> prefixes = claimablePrefixes();
        putPrefixes(p, prefixes);
        p.put("error", "超过最大领取次数 " + maxAttempts + "，帧已放弃");
        return jdbc.update("UPDATE inbox_message SET status='FAILED', processed_at=:now, last_error=:error, lease_token=NULL, lease_until=NULL"
                + " WHERE status='PROCESSING' AND lease_until<:now AND fusion_attempts>=:max AND " + prefixSql(prefixes), p);
    }

    public void done(String inboxId, long nowMillis) {
        jdbc.update("UPDATE inbox_message SET status='DONE', processed_at=:now, last_error=NULL, lease_token=NULL, lease_until=NULL WHERE inbox_id=:id",
                Map.of("id", inboxId, "now", nowMillis));
    }

    public void fail(String inboxId, long nowMillis, String error) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", inboxId); p.put("now", nowMillis); p.put("error", error);
        jdbc.update("UPDATE inbox_message SET status='FAILED', processed_at=:now, last_error=:error, lease_token=NULL, lease_until=NULL WHERE inbox_id=:id", p);
    }

    /** 回放信封入库：同键同哈希幂等（返回 false），同键不同哈希是来源冲突（SOURCE_MESSAGE_CONFLICT）。 */
    public boolean insertEnvelope(String source, String sourceMsgId, long receivedAtMillis, String sourceId, String payloadHash, String payloadJson) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", UUID.randomUUID().toString()); p.put("source", source); p.put("msg", sourceMsgId); p.put("received", receivedAtMillis);
        p.put("source_id", sourceId); p.put("hash", payloadHash); p.put("payload", payloadJson);
        // 条件插入而不是 catch 重复键：调用方（种子、批量导入）往往在同一事务里连续写多条，
        // 重复键异常会让 PostgreSQL 事务进入 aborted 状态，后面的查重 SELECT 只会拿到 25P02。
        // 用 WHERE NOT EXISTS 而不是 ON CONFLICT：后者在 H2 的 PostgreSQL 兼容模式下不解析，
        // 而摄取路径必须在 H2 单测与 PostgreSQL 生产库上跑同一条语句（唯一约束仍是最终保障）。
        int inserted = jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status)"
                + " SELECT :id,:source,:msg,:received,:source_id,:hash,CAST(:payload AS JSON),'RECEIVED'"
                + " WHERE NOT EXISTS (SELECT 1 FROM inbox_message WHERE source=:source AND source_msg_id=:msg)", p);
        if (inserted == 1) return true;
        String existing = jdbc.queryForObject("SELECT payload_hash FROM inbox_message WHERE source=:source AND source_msg_id=:msg", p, String.class);
        if (payloadHash.equals(existing)) return false;
        throw new IllegalStateException("SOURCE_MESSAGE_CONFLICT: " + source + "#" + sourceMsgId + " 已存在且哈希不同");
    }

    /** 某个来源已写入的条数：按数据集判断"灌过没有"要用它，前缀合计会把别的数据集也算进来。 */
    public long countBySource(String source) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=:source",
                Map.of("source", source), Long.class);
        return value == null ? 0 : value;
    }

    public long countBySourcePrefix(String sourcePrefix) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE :prefix", Map.of("prefix", sourcePrefix + "%"), Long.class);
        return count == null ? 0 : count;
    }

    public long countPending() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE status='RECEIVED' AND source LIKE :prefix AND source_id IS NOT NULL AND payload IS NOT NULL",
                Map.of("prefix", REPLAY_SOURCE_PREFIX + "%"), Long.class);
        return count == null ? 0 : count;
    }
}
