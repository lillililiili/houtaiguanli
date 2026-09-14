package com.uav.lowaltitude.modules.disposal.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 处置授权到期任务（契约 §2 末段）：把超过 {@code valid_until} 的 {@code APPROVED} 授权置为 {@code EXPIRED}。
 *
 * <p><b>它不是时限的安全闸门</b>（决策 13-16）。真正拦住"过期还动手"的是执行路径自己：
 * {@code DisposalAuthorizationService} 在 execute 时调 {@code DisposalRules.requireWithinWindow}，
 * 过期直接 409 {@code AUTHORIZATION_EXPIRED}。本任务做的是**状态卫生**：
 * 让列表、KPI 与交接前提看到的是 EXPIRED 而不是一条早已失效却仍显示 APPROVED 的授权，
 * 并在只增事件流里留下"何时过期"这条事实。把它误当成安全闸门，会让人以为关掉它只是少刷新状态——
 * 其实两者的职责本来就不同：闸门在执行路径上，这里只负责让库里的状态别撒谎。
 *
 * <p><b>并发保护做在 SQL 里，而不是只靠租约。</b>状态翻转与事件记录绑在同一个条件更新的胜负上：
 * <pre>
 *   UPDATE disposal_authorization SET status='EXPIRED' ... WHERE authorization_id=? AND status='APPROVED' AND valid_until &lt; ?
 * </pre>
 * 只有真正把这一行从 APPROVED 改成 EXPIRED 的那个实例（{@code rowcount==1}）才写 EXPIRE 事件，且在同一个事务里写。
 * 于是即便十个实例同时跑，也不可能出现两条 EXPIRE 事件——赢家只有一个，输家什么都不写。
 * 租约保证的是"同一时刻只有一个实例在跑"，但租约会过期、会因时钟漂移而重叠；
 * 条件更新用的是数据库自己的行锁语义，重叠了也只有一个赢家。所以正确性由条件更新兜底，租约只是省点无用功。
 *
 * <p><b>绝不碰 EXECUTING。</b>{@code WHERE status='APPROVED'} 是硬条件。执行中的授权即便过了时限也不能由后台
 * 悄悄改状态——它对应着设备上正在发生的动作，只能由回执（COMPLETED/FAILED）或人工停止（STOPPED）来收尾。
 * 后台把它改成 EXPIRED，页面上就会出现"已过期"而设备还在动作的矛盾，那比不处理更危险。
 *
 * <p>默认关闭（{@code app.disposal.expiry.enabled=false}）：生产是否开启由部署决定，
 * 演示与本地开发在 local 配置里打开（配置改动见 13.3 报告的变更请求）。
 */
@Component
@ConditionalOnProperty(prefix = "app.disposal.expiry", name = "enabled", havingValue = "true")
public class DisposalExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(DisposalExpiryJob.class);
    /** 一轮最多处理多少条：到期是补偿动作，不该在一次心跳里长时间占着连接。
     *  分页写法用 {@code FETCH FIRST ... ROWS ONLY} 而不是 {@code LIMIT}：全仓 H2/PG 双跑的语句都用这一种（见 FusionInboxRepository）。 */
    private static final int BATCH = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppClock clock;
    private final TransactionTemplate perAuthorization;

    public DisposalExpiryJob(JdbcTemplate jdbcTemplate, AppClock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.clock = clock;
        this.perAuthorization = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.disposal.expiry.poll-millis:60000}")
    public void poll() {
        int expired = expireOnce();
        if (expired > 0) log.info("disposal authorizations expired: count={}", expired);
    }

    /**
     * 把当前所有已过期的 APPROVED 授权置为 EXPIRED；返回真正被本次调用改掉的条数。
     * 供测试与种子同步调用，不依赖调度节拍。
     */
    public int expireOnce() {
        Instant now = clock.now();
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT authorization_id, authorization_no, valid_until FROM disposal_authorization"
                        + " WHERE status='APPROVED' AND valid_until IS NOT NULL AND valid_until < :now"
                        + " ORDER BY valid_until ASC FETCH FIRST :batch ROWS ONLY",
                Map.of("now", Timestamp.from(now), "batch", BATCH));
        int expired = 0;
        for (Map<String, Object> row : due) {
            String authorizationId = (String) row.get("authorization_id");
            // 一条一个事务：某一条因并发被别人抢先（rowcount=0）或写事件失败，都不该拖累其余条目。
            Boolean done = perAuthorization.execute(status -> expire(authorizationId, now));
            if (Boolean.TRUE.equals(done)) expired++;
        }
        return expired;
    }

    /** @return true 表示本次调用赢得了这条授权的状态翻转并写下了 EXPIRE 事件；false 表示别人已经处理过。 */
    private boolean expire(String authorizationId, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", authorizationId);
        p.put("now", Timestamp.from(now));
        int updated = jdbc.update(
                "UPDATE disposal_authorization SET status='EXPIRED', updated_at=:now, version=version+1"
                        + " WHERE authorization_id=:id AND status='APPROVED' AND valid_until IS NOT NULL AND valid_until < :now", p);
        // 输家在这里返回，不写事件——"谁改的状态谁记事件"是这段代码唯一的去重依据。
        if (updated != 1) return false;

        Map<String, Object> event = new HashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("id", authorizationId);
        // actor_id 留空：到期不是人做的决定，硬塞一个系统用户会让审计里出现一个并不存在的操作者。
        event.put("note", "授权已超过有效期，系统置为已过期");
        event.put("snapshot", "{\"status\":\"EXPIRED\",\"reason\":\"VALID_UNTIL_PASSED\"}");
        event.put("now", Timestamp.from(now));
        jdbc.update("INSERT INTO disposal_authorization_event (event_id, authorization_id, event_kind, actor_id, note, snapshot, occurred_at)"
                + " VALUES (:event_id, :id, 'EXPIRE', NULL, :note, CAST(:snapshot AS JSON), :now)", event);
        return true;
    }
}
