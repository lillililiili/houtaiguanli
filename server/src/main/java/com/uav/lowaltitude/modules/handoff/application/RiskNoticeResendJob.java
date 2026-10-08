package com.uav.lowaltitude.modules.handoff.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.DeliveryInsert;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 通知上级没发出去的，接收端恢复后自动补发一次（2026-10-08 验收预跑，新-23）。
 *
 * 点“通知上级”那一刻数据模拟器接收端没连上：交接照常落库，第一次投递记“待投递”和原因，什么也没发出去；
 * 风险通知又没有人工重发入口（再点会提示已提交过），上级就一直收不到。这里每隔几秒看一次：接收端在线时，
 * 把最近 30 分钟里只尝试过一次、而且那一次确实没发出去（待投递、没有提交时刻）的通知上级，用原来冻结的材料
 * 经同一个模拟器通道补发，记为第 2 次投递。上级回执照常经接收端回来，风险变“已回执”。
 *
 * 已经发出去、只是还没回执的不补发，免得上级收到两份；补发过一次的不再补；风险已不在“已通知”的不补。
 * 写法照处罚通知“等待规则”的补发（{@link HandoffSubmissionService} 的 sendWaitingPunishment）：
 * 每条一个事务，先锁交接再复核，投递记录和接收端消息一起提交；接收端恰好又断开时整条回滚，下一轮再看。
 */
@Component
public class RiskNoticeResendJob {
    private static final Logger log = LoggerFactory.getLogger(RiskNoticeResendJob.class);
    private static final AccessDecision SYSTEM_SCOPE = new AccessDecision("system:risk-notice-resend", ScopeMode.ALL);
    private static final int BATCH = 20;

    private final HandoffRepository repository;
    private final RiskRepository risks;
    private final NotificationDirectoryService directory;
    private final HandoffChannelPort channel;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Duration window;
    /** 补发出错的同一条只记一次日志：后台每 5 秒一轮，不能刷屏。只留最近 1000 条。 */
    private final Set<String> reported = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 1000; }
            }));

    public RiskNoticeResendJob(HandoffRepository repository, RiskRepository risks, NotificationDirectoryService directory,
            HandoffChannelPort channel, AppClock clock, AuditService audit, ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${app.handoff.risk-notice-resend.enabled:true}") boolean enabled,
            @Value("${app.handoff.risk-notice-resend.window-minutes:30}") long windowMinutes) {
        this.repository = repository; this.risks = risks; this.directory = directory; this.channel = channel;
        this.clock = clock; this.audit = audit; this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    }

    /** 缺省打开；用例里关掉，由用例直接调 {@link #resend()}。 */
    @Scheduled(fixedDelayString = "${app.handoff.risk-notice-resend.poll-millis:5000}")
    public void scheduledResend() {
        if (enabled) resend();
    }

    /** 补发一轮，返回补发了几条。一条出错不耽误别的。 */
    public int resend() {
        OffsetDateTime since = clock.now().atOffset(ZoneOffset.UTC).minus(window);
        int resent = 0;
        for (String handoffId : repository.unsentRiskNotices(since, BATCH)) {
            try {
                if (Boolean.TRUE.equals(tx.execute(ignored -> resendOne(handoffId, since)))) resent++;
            } catch (RuntimeException ex) {
                if (reported.add(handoffId)) log.warn("risk notice {} was not resent: {}", handoffId, ex.getMessage());
            }
        }
        return resent;
    }

    private boolean resendOne(String handoffId, OffsetDateTime since) {
        var row = repository.lockNotification(handoffId, SYSTEM_SCOPE);
        if (row == null || !HandoffRules.TYPE_RISK_NOTICE.equals(row.handoffType()) || row.createdAt().isBefore(since)) return false;
        var first = repository.latestDelivery(handoffId);
        if (first == null || first.attemptNo() != 1 || !HandoffRules.PENDING_DELIVERY.equals(first.deliveryStatus())
                || first.submittedAt() != null) return false;
        var target = directory.forHandoff(row.handoffType(), row.recipientId());
        // 只补发到数据模拟器接收端；configured 已经包含“接收端在线、心跳有效”。
        if (!target.configured() || !"API".equals(target.channelType())
                || !RealtimeNotificationTransport.ENDPOINT.equals(target.endpointRef()) || !channel.simulated()) return false;
        var risk = risks.lock(row.sourceId(), SYSTEM_SCOPE);
        if (risk == null || !"NOTIFIED".equals(risk.state())) return false;
        var snapshot = repository.snapshot(handoffId);
        if (snapshot == null) return false;
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        DeliveryOutcome outcome = directory.deliver(target, row.sourceMode(), new HandoffDispatch(handoffId, row.sourceKind(),
                row.sourceId(), row.handoffType(), row.recipientId(), target.recipientName(), material(snapshot.json()), at));
        // 发送前的检查没过（数据来源或运行环境不允许模拟通道）：什么也没发出去，不占这一次补发。
        if (HandoffRules.PENDING_DELIVERY.equals(outcome.deliveryStatus())) return false;
        int attempt = first.attemptNo() + 1;
        String deliveryId = UUID.randomUUID().toString();
        repository.insertDelivery(new DeliveryInsert(deliveryId, handoffId, attempt, outcome.deliveryStatus(), outcome.receiptStatus(),
                outcome.blockedReason(), at, outcome.submittedAt(), outcome.deliveredAt(), outcome.acknowledgedAt()));
        repository.notificationRecipient(deliveryId, encode(target));
        audit.record(null, "AUTO_RESEND", "SYSTEM", "handoff", "handoff_notification_resent", "handoff", handoffId,
                "delivery_id=" + deliveryId + "; attempt_no=" + attempt + "; first_blocked_reason=" + first.blockedReason()
                        + "; delivery_status=" + outcome.deliveryStatus() + "; blocked_reason=" + outcome.blockedReason(),
                "SUCCESS", "", "");
        reported.remove(handoffId);
        return true;
    }

    /** H2 把冻结材料存成 JSON 字符串，PostgreSQL 存成对象；发出去的都是同一份对象。 */
    private String material(String raw) {
        try {
            var value = json.readTree(raw);
            if (value.isTextual()) value = json.readTree(value.textValue());
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("原交接材料无法读取", ex);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
