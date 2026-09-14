package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 处置授权演示"体量"夹具：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 *
 * 目的：让处置授权列表与"最新一条授权的经过"在九种状态（REQUESTED/APPROVED/REJECTED/EXECUTING/
 * COMPLETED/FAILED/STOPPED/EXPIRED/CANCELLED）下都有可看的样例，四种动作（反制/干扰/驱离/诱骗）都出现，
 * 且每条授权的事件流都能读成一段连贯的经过。阶段 13 的固定夹具只有三条，留给回归用例计数；本类不改它。
 *
 * 写法与 {@link LocalStage13DisposalSeeder} 同一条路（JDBC + WHERE NOT EXISTS），但把服务层的规则搬过来当断言：
 * 两人规则（审批人 ≠ 申请人）、策略 requires_confirmed_event、TARGET 主体只许 DISPERSAL（13-24）、
 * 时限从 disposal_policy 读（13-2，代码里不出现 30/15 这种裸阈值）、审批四件套全有或全无、
 * 设备通道必须带 device_id（13-27：库里没有已启用设备时降为人工通道）、停止时设备侧结果按绑定表二分（13-11）、
 * 执行受阻本期恒为 PROTOCOL_NOT_OPENED（13-12/13-29）。违反这些断言直接抛：这是种子自己的编码错误，
 * 第一次启动就该炸出来，而不是灌出一批服务层永远造不出来的假历史。
 *
 * 主体全部复用既有种子：阶段 13 的已核实事件（反制/干扰/诱骗要求事件已核实）、阶段 4 的待核实事件（只能驱离）、
 * 阶段 7 的目标（只能驱离；请求时刻贴着 T0 的观测时刻，与 C03 新鲜度口径一致）。依赖的行不存在就跳过那一组并告警，
 * 不让整个上下文起不来。
 *
 * 编号：固定历史用 AUTH-20260908-93xx / AUTH-20260905-93xx，"进行中"的三条用当天日期 + 935x；
 * 服务自己的计数从 0001 起按日递增，9xxx 段与阶段 13 的 9001–9003 同一约定，不会撞。
 *
 * 只补缺行：授权已存在（不论谁推进过）就整条连事件一起跳过，绝不 UPDATE 授权、绝不追加事件（事件表只增）。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn({"localStage13DisposalSeeder", "localStage15DemoReviewerSeeder", "localStage7RuleEngineSeeder"})
@Order(125)
public class LocalDemoVolumeDisposalSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalDemoVolumeDisposalSeeder.class);

    static final String PREFIX = "seed-vol-disposal-";
    /** 固定历史的一天：2026-09-08 08:00 Asia/Shanghai。 */
    static final Instant DAY = Instant.parse("2026-09-08T00:00:00Z");
    /** 阶段 4 夹具的事件 ID 是那个种子的私有字面量：这里照抄，并在启动时核对存在，缺了就跳过那一组。 */
    static final String EVENT_PENDING = "seed-stage4-event-pending";
    static final String EVENT_EVIDENCE = "seed-stage4-event-evidence";
    static final String EVENT_SAME_TARGET_A = "seed-stage4-event-same-target-a";
    static final String EVENT_SAME_TARGET_B = "seed-stage4-event-same-target-b";

    private static final String UAV_EVENT = "UAV_EVENT", TARGET = "TARGET", CONFIRMED = "CONFIRMED";

    private final JdbcTemplate jdbc;
    private final DisposalPolicyRepository policies;
    private final AppClock clock;
    private final ObjectMapper json;

    public LocalDemoVolumeDisposalSeeder(JdbcTemplate jdbc, DisposalPolicyRepository policies, AppClock clock,
            ObjectMapper json) {
        this.jdbc = jdbc; this.policies = policies; this.clock = clock; this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String admin = userId("admin1");
        String reviewer = userId(LocalStage15DemoReviewerSeeder.ACCOUNT);
        if (admin == null || reviewer == null) {
            // 两人规则要两个真实账号：缺任何一个都造不出"申请人 ≠ 审批人"的历史，宁可不造。
            log.warn("demo volume disposal seed skipped: admin1 or reviewer1 is missing");
            return;
        }
        DisposalPolicy policy = policies.active();
        String device = deviceId();
        boolean bound = device != null && bound(device);
        int seeded = 0;
        seeded += confirmedEventHistory(policy, admin, reviewer, device, bound);
        seeded += pendingEventHistory(policy, admin, reviewer);
        seeded += targetHistory(policy, admin, reviewer, device);
        seeded += liveRows(policy, admin, reviewer, device);
        log.info("demo volume disposal seed: {} authorization(s) inserted, device={}, bound={}",
                seeded, device == null ? "none (MANUAL only)" : device, bound);
    }

    /* ---- 阶段 13 的已核实事件：四种动作都能发起 ---- */

    private int confirmedEventHistory(DisposalPolicy policy, String admin, String reviewer, String device, boolean bound) {
        Subject event = uavEvent(LocalStage13DisposalSeeder.EVENT);
        // 事件不在或已被人改成别的状态：反制/干扰/诱骗都发不出去（策略 requires_confirmed_event），整组跳过而不是炸上下文。
        if (event == null || !CONFIRMED.equals(event.state())) {
            log.warn("demo volume disposal seed: stage 13 event missing or not CONFIRMED, confirmed-event group skipped");
            return 0;
        }
        int n = 0;
        // 08:05 申请诱骗 → 08:12 申请人自己撤回（REQUESTED → CANCELLED）。
        n += draft("9301", DisposalRules.authorizationNo("20260908", 9301), DisposalRules.DECOY, event, null, false,
                "目标在管控区边缘盘旋，具备诱骗迫降条件，申请对其实施诱骗", admin, DAY.plus(5, ChronoUnit.MINUTES), policy)
                .cancel(admin, DAY.plus(12, ChronoUnit.MINUTES), "现场目视确认目标已自行返航离开管控区，撤回本次申请")
                .commit();
        // 08:10 申请干扰 → 08:15 批准 → 08:25 目标飞离，审批人撤销授权（APPROVED → STOPPED；设备通道下记急停结果）。
        n += draft("9302", DisposalRules.authorizationNo("20260908", 9302), DisposalRules.JAMMING, event, device, bound,
                "目标持续逼近机场净空保护区，申请对其链路实施干扰", admin, DAY.plus(10, ChronoUnit.MINUTES), policy)
                .approve(reviewer, DAY.plus(15, ChronoUnit.MINUTES), "事件已核实属实，同意实施干扰，注意避开民航频段")
                .stop(reviewer, DAY.plus(25, ChronoUnit.MINUTES), "目标已飞离净空保护区并远离，无继续干扰必要，撤销授权")
                .commit();
        // 08:20 申请反制 → 08:26 驳回（REQUESTED → REJECTED）。
        n += draft("9303", DisposalRules.authorizationNo("20260908", 9303), DisposalRules.COUNTERMEASURE, event, device, bound,
                "目标再次进入管控区并低空悬停，申请对其实施反制迫降", admin, DAY.plus(20, ChronoUnit.MINUTES), policy)
                .reject(reviewer, DAY.plus(26, ChronoUnit.MINUTES), "目标当前位于居民区上空，强制迫降有坠落伤人风险，不予批准；建议先干扰驱离")
                .commit();
        // 08:40 申请干扰 → 08:42 批准 → 08:45 下发受阻（指令码未开放）→ 09:12 到期失效（APPROVED → EXPIRED）。
        n += draft("9305", DisposalRules.authorizationNo("20260908", 9305), DisposalRules.JAMMING, event, device, bound,
                "按驳回意见改为干扰驱离，申请对目标链路实施干扰", admin, DAY.plus(40, ChronoUnit.MINUTES), policy)
                .approve(reviewer, DAY.plus(42, ChronoUnit.MINUTES), "同意干扰驱离，限时执行")
                .blocked(reviewer, DAY.plus(45, ChronoUnit.MINUTES))
                .expire()
                .commit();
        // 08:55 申请人工反制 → 09:00 批准 → 09:02 人工执行 → 09:20 登记成功（EXECUTING → COMPLETED）。
        n += draft("9306", DisposalRules.authorizationNo("20260908", 9306), DisposalRules.COUNTERMEASURE, event, null, false,
                "干扰指令无法下发，目标已移出居民区上空，申请由现场处置组使用手持反制设备迫降", admin,
                DAY.plus(55, ChronoUnit.MINUTES), policy)
                .approve(reviewer, DAY.plus(60, ChronoUnit.MINUTES), "目标已在空旷地带上空，同意人工反制迫降，落地后立即控制")
                .execute(reviewer, DAY.plus(62, ChronoUnit.MINUTES))
                .manualResult(admin, DAY.plus(80, ChronoUnit.MINUTES), true, "现场使用手持反制设备迫降成功，目标已落地并被扣押，飞手正在查找")
                .commit();
        return n;
    }

    /* ---- 阶段 4 的待核实事件：策略只允许驱离 ---- */

    private int pendingEventHistory(DisposalPolicy policy, String admin, String reviewer) {
        int n = 0;
        Subject sameB = uavEvent(EVENT_SAME_TARGET_B);
        if (sameB != null) {
            // 08:30 申请驱离 → 08:33 批准 → 08:35 人工执行 → 08:50 登记失败（EXECUTING → FAILED）。
            n += draft("9304", DisposalRules.authorizationNo("20260908", 9304), DisposalRules.DISPERSAL, sameB, null, false,
                    "目标在学校上空低速盘旋，申请现场喊话驱离", admin, DAY.plus(30, ChronoUnit.MINUTES), policy)
                    .approve(reviewer, DAY.plus(33, ChronoUnit.MINUTES), "同意驱离，先喊话警告，不得直接反制")
                    .execute(admin, DAY.plus(35, ChronoUnit.MINUTES))
                    .manualResult(admin, DAY.plus(50, ChronoUnit.MINUTES), false, "连续喊话十分钟无效，目标悬停不动，未能驱离，建议升级为干扰")
                    .commit();
        } else { log.warn("demo volume disposal seed: {} missing, FAILED sample skipped", EVENT_SAME_TARGET_B); }
        Subject sameA = uavEvent(EVENT_SAME_TARGET_A);
        if (sameA != null) {
            // 09:30 申请驱离 → 09:33 批准 → 09:35 人工执行 → 09:40 目标飞离，停止（EXECUTING → STOPPED，人工通道不尝试急停）。
            n += draft("9307", DisposalRules.authorizationNo("20260908", 9307), DisposalRules.DISPERSAL, sameA, null, false,
                    "同一目标再次出现在学校上空，申请现场驱离", admin, DAY.plus(90, ChronoUnit.MINUTES), policy)
                    .approve(reviewer, DAY.plus(93, ChronoUnit.MINUTES), "同意驱离")
                    .execute(admin, DAY.plus(95, ChronoUnit.MINUTES))
                    .stop(reviewer, DAY.plus(100, ChronoUnit.MINUTES), "目标已向东飞离并消失于探测范围，停止驱离")
                    .commit();
        } else { log.warn("demo volume disposal seed: {} missing, STOPPED (manual) sample skipped", EVENT_SAME_TARGET_A); }
        Subject evidence = uavEvent(EVENT_EVIDENCE);
        if (evidence != null) {
            // 09:45 申请驱离 → 09:48 批准 → 09:50 申请人撤回（APPROVED → CANCELLED：批了也能撤）。
            n += draft("9308", DisposalRules.authorizationNo("20260908", 9308), DisposalRules.DISPERSAL, evidence, null, false,
                    "取证期间目标仍在作业，申请驱离", admin, DAY.plus(105, ChronoUnit.MINUTES), policy)
                    .approve(reviewer, DAY.plus(108, ChronoUnit.MINUTES), "同意驱离，取证完成后执行")
                    .cancel(admin, DAY.plus(110, ChronoUnit.MINUTES), "补充取证后确认该目标为已报备的测绘作业，撤回驱离申请")
                    .commit();
        } else { log.warn("demo volume disposal seed: {} missing, CANCELLED (after approval) sample skipped", EVENT_EVIDENCE); }
        return n;
    }

    /* ---- 阶段 7 的目标：态势页"派发驱离"的形态 ---- */

    private int targetHistory(DisposalPolicy policy, String admin, String reviewer, String device) {
        Instant t0 = LocalStage7RuleEngineSeeder.T0;
        int n = 0;
        Subject noPlan = target(LocalStage7RuleEngineSeeder.targetId("no-plan"));
        if (noPlan != null) {
            n += draft("t9301", DisposalRules.authorizationNo("20260905", 9301), DisposalRules.DISPERSAL, noPlan, null, false,
                    "该目标无任何飞行计划，位于走廊之外，申请驱离", admin, t0.plusSeconds(30), policy)
                    .approve(reviewer, t0.plusSeconds(90), "无计划飞行，同意驱离")
                    .execute(admin, t0.plusSeconds(120))
                    .manualResult(admin, t0.plusSeconds(600), true, "喊话后目标返航离开，驱离完成")
                    .commit();
        }
        Subject legal = target(LocalStage7RuleEngineSeeder.targetId("legal"));
        if (legal != null) {
            n += draft("t9302", DisposalRules.authorizationNo("20260905", 9302), DisposalRules.DISPERSAL, legal, null, false,
                    "态势页发现目标，申请驱离", admin, t0.plusSeconds(40), policy)
                    .reject(reviewer, t0.plusSeconds(100), "该目标有有效飞行计划且在走廊内正常飞行，无需驱离，不予批准")
                    .commit();
        }
        Subject deviation = target(LocalStage7RuleEngineSeeder.targetId("deviation"));
        if (deviation != null) {
            // 设备通道下发受阻、人也没来得及处理，驱离授权（策略时限较短）到期失效。
            n += draft("t9303", DisposalRules.authorizationNo("20260905", 9303), DisposalRules.DISPERSAL, deviation, device,
                    device != null && bound(device), "目标偏离计划走廊 133 m，申请驱离回走廊", admin, t0.plusSeconds(60), policy)
                    .approve(reviewer, t0.plusSeconds(120), "偏航属实，同意驱离")
                    .blocked(admin, t0.plusSeconds(150))
                    .expire()
                    .commit();
        }
        if (n == 0) log.warn("demo volume disposal seed: no stage 7 targets found, TARGET group skipped");
        return n;
    }

    /* ---- 进行中的三条：时间贴着当前时钟，否则批准的那条会被到期任务立刻置为 EXPIRED ---- */

    private int liveRows(DisposalPolicy policy, String admin, String reviewer, String device) {
        Instant now = clock.now();
        String day = dayKey(now);
        int n = 0;
        Subject pending = uavEvent(EVENT_PENDING);
        if (pending != null) {
            // 人工驱离执行中：详情页可登记结果或停止。
            n += draft("9351", DisposalRules.authorizationNo(day, 9351), DisposalRules.DISPERSAL, pending, null, false,
                    "目标在待核实状态下持续逼近人群密集区，申请先行喊话驱离", admin, now.minus(20, ChronoUnit.MINUTES), policy)
                    .approve(reviewer, now.minus(15, ChronoUnit.MINUTES), "情况紧急，同意先行驱离，同步继续核实")
                    .execute(admin, now.minus(12, ChronoUnit.MINUTES))
                    .commit();
        }
        Subject confirmed = uavEvent(LocalStage13DisposalSeeder.EVENT);
        if (confirmed != null && CONFIRMED.equals(confirmed.state())) {
            // 已批准但下发受阻（指令码未开放）：详情页显示受阻原因，仍可停止/撤回；有效期从批准起按策略时限计。
            boolean bound = device != null && bound(device);
            n += draft("9352", DisposalRules.authorizationNo(day, 9352), DisposalRules.JAMMING, confirmed, device, bound,
                    "目标再次进入管控区，申请对其链路实施干扰", admin, now.minus(8, ChronoUnit.MINUTES), policy)
                    .approve(reviewer, now.minus(5, ChronoUnit.MINUTES), "同意实施干扰，限时执行")
                    .blocked(reviewer, now.minus(3, ChronoUnit.MINUTES))
                    .commit();
            // 待审批：给演示复核员留一条可以批/驳的申请。
            n += draft("9353", DisposalRules.authorizationNo(day, 9353), DisposalRules.DECOY, confirmed, device, bound,
                    "目标具备诱骗迫降条件，申请对其实施诱骗，迫降至预定回收点", admin, now.minus(10, ChronoUnit.MINUTES), policy)
                    .commit();
        }
        return n;
    }

    /* ---- 主体解析：授权的归属元组复制自主体，与服务层一致 ---- */

    private record Subject(String kind, String id, String targetId, String ownerOrgId, String districtId, String state) { }

    private Subject uavEvent(String eventId) {
        return jdbc.query("SELECT e.owner_org_id,e.district_id,e.state_code,a.target_id FROM uav_event e"
                + " JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?",
                rs -> rs.next() ? new Subject(UAV_EVENT, eventId, rs.getString(4), rs.getString(1), rs.getString(2),
                        rs.getString(3)) : null, eventId);
    }

    private Subject target(String targetId) {
        return jdbc.query("SELECT owner_org_id,district_id FROM target WHERE target_id=?",
                rs -> rs.next() ? new Subject(TARGET, targetId, targetId, rs.getString(1), rs.getString(2), null) : null,
                targetId);
    }

    private String userId(String account) {
        return jdbc.query("SELECT user_id FROM app_user WHERE account=?", rs -> rs.next() ? rs.getString(1) : null, account);
    }

    /** 与阶段 13 种子同一取法：任意一台已启用设备；没有就全部走人工通道（13-27）。 */
    private String deviceId() {
        return jdbc.query("SELECT device_id FROM ops_device WHERE enabled=TRUE ORDER BY device_id ASC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /** 与 DisposalExecutionGateway.bound 同一判据（mqtt_device_binding 有行即已绑定），停止时据此二分急停结果。 */
    private boolean bound(String deviceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mqtt_device_binding WHERE ops_device_id=?",
                Integer.class, deviceId);
        return count != null && count > 0;
    }

    private Draft draft(String key, String no, String action, Subject subject, String device, boolean bound,
                        String reason, String requestedBy, Instant requestedAt, DisposalPolicy policy) {
        return new Draft(PREFIX + key, no, action, subject, device, bound, reason, requestedBy, requestedAt, policy);
    }

    /* ---- 一条授权的草稿：按服务层的顺序推进状态，最后一次性落库 ---- */

    private record Event(String kind, String actorId, Instant at, String note, Map<String, Object> snapshot) { }

    private final class Draft {
        private final String id, no, action, channel, deviceId, reason, requestedBy;
        private final Subject subject;
        private final Instant requestedAt;
        private final boolean bound;
        private final DisposalPolicy policy;
        private final List<Event> events = new ArrayList<>();
        private String status = DisposalRules.REQUESTED;
        private long version = 0;
        private String approvedBy, decisionNote, resultCode, resultDetail;
        private Instant approvedAt, validFrom, validUntil, updatedAt;

        Draft(String id, String no, String action, Subject subject, String deviceId, boolean bound, String reason,
              String requestedBy, Instant requestedAt, DisposalPolicy policy) {
            // 服务层 resolveSubject 的两条前置：要求已核实的动作只能对 CONFIRMED 事件发；TARGET 主体只许驱离（13-24）。
            if (policy.requiresConfirmedEvent(action) && !(UAV_EVENT.equals(subject.kind()) && CONFIRMED.equals(subject.state())))
                throw new IllegalStateException(id + ": " + action + " requires a CONFIRMED UAV_EVENT subject");
            if (TARGET.equals(subject.kind()) && !DisposalRules.DISPERSAL.equals(action))
                throw new IllegalStateException(id + ": TARGET subjects only allow DISPERSAL");
            this.id = id; this.no = no; this.action = action; this.subject = subject;
            this.deviceId = deviceId; this.bound = bound;
            this.channel = deviceId == null ? DisposalRules.MANUAL : DisposalRules.LINGYUN_B;
            this.reason = reason; this.requestedBy = requestedBy; this.requestedAt = requestedAt; this.updatedAt = requestedAt;
            this.policy = policy;
            events.add(new Event("REQUEST", requestedBy, requestedAt, reason, snapshot("status", status,
                    "action_type", action, "channel", channel, "policy_version", policy.policyCode())));
        }

        Draft approve(String by, Instant at, String note) {
            transition(DisposalRules.APPROVE, DisposalRules.APPROVED, at);
            requireTwoPerson(by);
            approvedBy = by; approvedAt = at; decisionNote = note;
            validFrom = at; validUntil = at.plus(policy.timeLimitMinutes(action), ChronoUnit.MINUTES);
            events.add(new Event("APPROVE", by, at, note, snapshot("status", status,
                    "valid_from", validFrom.toEpochMilli(), "valid_until", validUntil.toEpochMilli())));
            return this;
        }

        /**
         * 驳回只留意见与事件里的审批人：ck_stage13_authorization_approval 要求审批四件套全有或全无，
         * 驳回没有有效期，所以 approved_by/approved_at 与阶段 13 的种子一样留空。
         */
        Draft reject(String by, Instant at, String note) {
            transition(DisposalRules.REJECT, DisposalRules.REJECTED, at);
            requireTwoPerson(by);
            decisionNote = note;
            events.add(new Event("REJECT", by, at, note, snapshot("status", status)));
            return this;
        }

        /** 人工执行：本期设备通道的四种指令码都未开放（13-12），经协议 B 的执行不可能发生，因此只允许 MANUAL。 */
        Draft execute(String by, Instant at) {
            if (!DisposalRules.MANUAL.equals(channel))
                throw new IllegalStateException(id + ": only MANUAL authorizations can reach EXECUTING in this phase");
            requireWithinWindow(at);
            transition(DisposalRules.EXECUTE, DisposalRules.EXECUTING, at);
            events.add(new Event("EXECUTE", by, at, "人工执行", snapshot("status", status, "channel", channel)));
            return this;
        }

        /** 设备通道的执行尝试：与 DisposalExecutionGateway 的自检顺序一致，第一条就是"指令码未开通"，状态不动。 */
        Draft blocked(String by, Instant at) {
            if (DisposalRules.MANUAL.equals(channel)) return this;   // 人工通道没有可受阻的下发，样例降为普通已批准。
            if (!DisposalRules.APPROVED.equals(status)) throw new IllegalStateException(id + ": blocked execute needs APPROVED");
            requireWithinWindow(at);
            int cmd = policy.command(action).operationCmd();
            events.add(new Event("PROTOCOL_NOT_OPENED", by, at,
                    "指令码 " + cmd + " 对应的设备类型缩写尚未确认，协议面未开通，不能下发",
                    snapshot("status", status, "channel", channel, "device_id", deviceId)));
            updatedAt = at;
            return this;
        }

        Draft manualResult(String by, Instant at, boolean succeeded, String detail) {
            if (!DisposalRules.MANUAL.equals(channel)) throw new IllegalStateException(id + ": manual result needs MANUAL channel");
            String result = succeeded ? "SUCCEEDED" : "FAILED";
            transition(DisposalRules.MANUAL_RESULT, succeeded ? DisposalRules.COMPLETED : DisposalRules.FAILED, at);
            resultCode = "MANUAL_" + result; resultDetail = detail;
            events.add(new Event("MANUAL_RESULT", by, at, detail, snapshot("status", status, "result", result)));
            return this;
        }

        /** 停止 = 撤销授权 + 尝试急停；设备通道按绑定表二分记事件，人工通道只记 STOP（13-4/13-11/13-23）。 */
        Draft stop(String by, Instant at, String note) {
            transition(DisposalRules.STOP, DisposalRules.STOPPED, at);
            resultCode = "STOPPED_BY_OPERATOR"; resultDetail = note;
            events.add(new Event("STOP", by, at, note, snapshot("status", status)));
            if (DisposalRules.LINGYUN_B.equals(channel)) {
                if (bound) {
                    events.add(new Event("DEVICE_STOP_UNAVAILABLE", by, at, "设备协议未提供急停，授权已撤销但设备可能仍在动作",
                            snapshot("device_id", deviceId)));
                } else {
                    events.add(new Event("DEVICE_NOT_BOUND", by, at, "该设备未登记凌云 MQTT，无法尝试急停",
                            snapshot("device_id", deviceId)));
                }
            }
            return this;
        }

        Draft cancel(String by, Instant at, String note) {
            transition(DisposalRules.CANCEL, DisposalRules.CANCELLED, at);
            resultCode = "CANCELLED_BY_REQUESTER"; resultDetail = note;
            events.add(new Event("CANCEL", by, at, note, snapshot("status", status)));
            return this;
        }

        /** 到期由系统置位：没有操作者，不写 result_code，与 DisposalExpiryJob 落的行一致。 */
        Draft expire() {
            if (!DisposalRules.APPROVED.equals(status) || validUntil == null)
                throw new IllegalStateException(id + ": only APPROVED authorizations expire");
            status = DisposalRules.EXPIRED; version++; updatedAt = validUntil;
            events.add(new Event("EXPIRE", null, validUntil, "授权已超过有效期，系统置为已过期",
                    snapshot("status", status, "reason", "VALID_UNTIL_PASSED")));
            return this;
        }

        /** @return 1 表示本次插入了这条授权及其事件；0 表示已存在，整条跳过（不追加过去的事件）。 */
        int commit() {
            int inserted = jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,"
                    + "subject_kind,subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,"
                    + "approved_at,decision_note,valid_from,valid_until,status,execution_command_id,result_code,result_detail,"
                    + "policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                    + " SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,'mock',?,?,?"
                    + " WHERE NOT EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=?)",
                    id, no, action, subject.kind(), subject.id(), subject.targetId(), deviceId, channel, reason,
                    requestedBy, ts(requestedAt), approvedBy, ts(approvedAt), decisionNote, ts(validFrom), ts(validUntil),
                    status, resultCode, resultDetail, policy.policyCode(), subject.ownerOrgId(), subject.districtId(),
                    version, ts(requestedAt), ts(updatedAt), id);
            if (inserted == 0) return 0;
            int seq = 0;
            for (Event event : events) {
                String eventId = id + "-e" + (++seq);
                jdbc.update("INSERT INTO disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,"
                        + "snapshot,occurred_at) SELECT ?,?,?,?,?,CAST(? AS JSON),?"
                        + " WHERE NOT EXISTS (SELECT 1 FROM disposal_authorization_event WHERE event_id=?)",
                        eventId, id, event.kind(), event.actorId(), event.note(), write(event.snapshot()), ts(event.at()), eventId);
            }
            return 1;
        }

        private void transition(String actionCode, String next, Instant at) {
            DisposalRules.requireTransition(actionCode, status);   // 非法迁移在这里 409 抛出：种子写错顺序第一次启动就炸。
            if (at.isBefore(updatedAt)) throw new IllegalStateException(id + ": events must not go backwards in time");
            status = next; version++; updatedAt = at;
        }

        private void requireTwoPerson(String approver) {
            DisposalRules.requireTwoPerson(policy, requestedBy, approver);
        }

        private void requireWithinWindow(Instant at) {
            DisposalRules.requireWithinWindow(at.toEpochMilli(), validFrom == null ? null : validFrom.toEpochMilli(),
                    validUntil == null ? null : validUntil.toEpochMilli());
        }
    }

    /* ---- 小工具 ---- */

    private static Map<String, Object> snapshot(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    private String write(Map<String, Object> snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize demo disposal seed snapshot", ex); }
    }

    private static String dayKey(Instant at) {
        var utc = at.atOffset(ZoneOffset.UTC);
        return String.format("%04d%02d%02d", utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth());
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
