package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 领取白名单：前缀取自已注册的映射器，实测雷达受 live-promotion 开关控制。
 *
 * 开关关闭时必须**连领都不领**：领了就会解析、丢弃并置为已处理，`fusion_attempts` 被推到上限后
 * 由 failExhausted 永久判成毒帧——一个纯配置开关不该有不可逆的数据后果，开关打开后这些帧还得能重放。
 */
@SpringBootTest(properties = "app.dev-seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class FusionInboxClaimPrefixTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionInboxRepository inbox;
    @Autowired FusionProperties properties;
    @Autowired org.springframework.transaction.interceptor.TransactionAttributeSource attributes;

    @Test
    void liveRadarRowsAreLeftUntouchedWhileLivePromotionIsOff() {
        String sourceId = source();
        String liveRadar = row(sourceId, "live-radar:R-1");
        String lingyun = row(sourceId, "lingyun:tdoa:227");

        assertThat(properties.getLivePromotion().isEnabled()).isFalse();
        assertThat(inbox.claimablePrefixes()).doesNotContain("live-radar:").contains("lingyun:", "eo-edge:", "replay:");

        List<InboxRow> claimed = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
        assertThat(claimed).extracting(InboxRow::inboxId).contains(lingyun).doesNotContain(liveRadar);
        // 没被领就不该被改动：状态与领取次数都要原封不动，开关打开后还能重放。
        assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?", String.class, liveRadar)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select fusion_attempts from inbox_message where inbox_id=?", Integer.class, liveRadar)).isZero();
    }

    @Test
    void liveRadarRowsBecomeClaimableOnceLivePromotionIsOn() {
        String sourceId = source();
        String liveRadar = row(sourceId, "live-radar:R-2");
        properties.getLivePromotion().setEnabled(true);
        try {
            assertThat(inbox.claimablePrefixes()).contains("live-radar:");
            assertThat(inbox.claim(System.currentTimeMillis(), 50, 30_000L)).extracting(InboxRow::inboxId).contains(liveRadar);
        } finally {
            properties.getLivePromotion().setEnabled(false);
        }
    }

    @Test
    void opsDeviceRowsAreNeverClaimedByFusion() {
        String sourceId = source();
        String opsRow = row(sourceId, "live-device:D-1");
        // ops 的行由设备模块自己的 processing_status 处理，融合侧一概不碰。
        assertThat(inbox.claim(System.currentTimeMillis(), 50, 30_000L)).extracting(InboxRow::inboxId).doesNotContain(opsRow);
        assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?", String.class, opsRow)).isEqualTo("RECEIVED");
    }

    /**
     * 决策 15-40：两步领取全靠 `@Transactional` 把 SELECT ... FOR UPDATE 与随后的 UPDATE 关进同一个事务。
     * 注解一旦失效（比如有人把类改成 final、或换成自注入调用），两条语句各自 autocommit，
     * 第一步的行锁**立刻释放**——并发领取者又会选到同一批，退回 round13 那个"后到者领 0 行"的缺陷。
     *
     * 这个失效在 H2 单连接上完全静默：锁不锁都测不出差别，全量绿也说明不了什么。
     * 所以这里只钉一件能钉的事——bean 确实被代理，注解不是写着好看的。
     */
    @Test
    void claimIsTransactionalSoTheLocksSurviveUntilTheUpdate() {
        // 不能只断"是不是代理"：@Repository 本身就会被异常转换代理，那条断言去掉 @Transactional 照样绿
        // （我实测过，5/5 全绿），等于什么也没测。要直接查代理上挂没挂事务通知。
        assertThat(inbox).isInstanceOf(org.springframework.aop.framework.Advised.class);
        boolean transactional = java.util.Arrays.stream(((org.springframework.aop.framework.Advised) inbox).getAdvisors())
                .anyMatch(a -> a.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor);
        assertThat(transactional).as("FusionInboxRepository 的代理上必须挂着事务通知").isTrue();

        // 再逐个方法钉住。三参重载是 this 自调用四参版，Spring 代理对自调用不生效——
        // 四参上的注解管不到三参路径，而种子与并发用例走的正是三参。只断"类上有事务通知"漏得掉这一条。
        for (Class<?>[] signature : List.<Class<?>[]>of(
                new Class<?>[] { long.class, int.class, long.class },
                new Class<?>[] { long.class, int.class, long.class, int.class })) {
            java.lang.reflect.Method method;
            try {
                method = FusionInboxRepository.class.getMethod("claim", signature);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            assertThat(attributes.getTransactionAttribute(method, FusionInboxRepository.class))
                    .as("claim/%d 必须有事务属性", signature.length).isNotNull();
        }
    }

    /**
     * 决策 15-40：一次领取不得超过 batch。
     *
     * round13 给内层 SELECT 加 FOR UPDATE SKIP LOCKED 之后，PostgreSQL 把 `IN (子查询)` 计划成
     * Nested Loop Semi Join，子查询对每行外层记录重新执行，LockRows 又把本语句刚改成 PROCESSING 的行剔掉、
     * 再取"下一批"——batch=20 一次领走 40 行（真 PG 实测 UPDATE 40）。改成"先锁 id、再按 id 更新"两步。
     *
     * **如实说明这条用例在 H2 上的效力**：H2 对旧写法是把子查询一次性求值的，超领在 H2 上**根本不复现**
     * （我把旧 SQL 放回来实测过，仍然是 20，见 round14 记录）。所以它在这里只是把"一次不超过 batch"
     * 这个约定钉住、防止将来有人再写回子查询形态；**真正能证伪超领的证据只能来自真 PG**。
     */
    @Test
    void neverClaimsMoreThanTheRequestedBatch() {
        String sourceId = source();
        for (int i = 0; i < 40; i++) row(sourceId, "replay:batch-" + i);

        List<InboxRow> first = inbox.claim(System.currentTimeMillis(), 20, 30_000L);
        assertThat(first).as("第一次领取").hasSize(20);
        List<InboxRow> second = inbox.claim(System.currentTimeMillis(), 20, 30_000L);
        assertThat(second).as("第二次领取").hasSize(20);
        // 两批不能重叠：超领时第二次会把第一次那批再算一遍。
        assertThat(second).extracting(InboxRow::inboxId)
                .doesNotContainAnyElementsOf(first.stream().map(InboxRow::inboxId).toList());
    }

    private String source() {
        String sourceId = UUID.randomUUID().toString();
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version)"
                + " values (?,?,?,true,'replay','RADAR',current_timestamp,current_timestamp,0)",
                sourceId, "SRC-CLAIM-" + sourceId.substring(0, 8), "领取白名单测试来源");
        return sourceId;
    }

    private String row(String sourceId, String source) {
        String inboxId = UUID.randomUUID().toString();
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED',0)",
                inboxId, source, UUID.randomUUID().toString(), System.currentTimeMillis(), sourceId,
                "0".repeat(64), "{\"items\":[]}");
        return inboxId;
    }
}
