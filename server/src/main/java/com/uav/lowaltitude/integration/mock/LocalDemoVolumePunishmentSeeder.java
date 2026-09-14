package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler;
import com.uav.lowaltitude.modules.punishment.domain.DecisionDocumentRenderer;
import com.uav.lowaltitude.modules.punishment.domain.PunishmentRules;

/**
 * 处罚案件"体量"夹具：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 *
 * 目的：让处罚页在每个状态下都有案子可看——FILED / INVESTIGATING / UNDER_REVIEW / DECIDED / CLOSED / WITHDRAWN 各一件，
 * 裁量覆盖 DRAFT / CONFIRMED / SUPERSEDED，决定书一份 ISSUED、一份 REVOKED，复核三种结论各至少一条，
 * 待补线索既有已解决也有未解决的。阶段 14 的契约夹具（{@link LocalStage14PunishmentSeeder}）只造一件 INVESTIGATING，
 * 正面路径留给浏览器走；这里不改它，只在它之后补量。
 *
 * 每件案子的来源都走演示主线：告警 → 已核实事件（带一条人工核实记录）→ 已完成联动反制 → 已完成信号干扰
 * （人工通道、干扰 chained_from 反制，决策 13-34）→ UAV_PUNISHMENT 交接（快照 v2 走服务层同一个组装器，决策 14-28）→ 案件。
 * 一事件一案（14-5），所以六件案子各自挂在自建的六个事件上；机构/区域/来源只引用阶段 4/13 已有的行。
 * 告警页按钮不允许跳过反制/干扰直接移送；种子直插必须把这两步写全，否则流程条会显示「尚无授权」却已移送。
 *
 * 案件本身按阶段 14 的状态机（14-7）在内存里"走一遍"，再把**走完之后**的行落库：
 * 案件行的 status/version/officer/decided_at… 与事件流、裁量版本、复核、文书逐条对应——
 * version 与服务路径同口径（每次 transition 递增一次），装出来的案子在页面上点下一步时 expected_version 才对得上。
 * 复核人一律 reviewer1（{@link LocalStage15DemoReviewerSeeder}），承办人 admin1：承办人不能复核自己的案子（14-11/14-27）。
 * 决定书的 fields/rendered_sha256 用 {@link DecisionDocumentRenderer} 真算，下载时按 fields 重渲染能对上哈希。
 *
 * 全部写入 WHERE NOT EXISTS，重跑幂等；只补缺行，不 UPDATE 已存在的记录——演示时有人把某件案子往前推了，
 * 重启不会把它拉回去。前提（阶段 4/13 的机构、区域、来源，阶段 14 的罚则档位，admin1/reviewer1）缺任何一项都直接返回而不是报错：
 * 种子一炸整个 Spring 上下文都起不来，表现是所有页面全红，排查方向会被带偏。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn({"localStage14PunishmentSeeder", "localStage15DemoReviewerSeeder"})
@Order(130)
public class LocalDemoVolumePunishmentSeeder implements ApplicationRunner {
    static final String DAY_KEY = "20260908";
    /** 案号段 CASE-20260908-93NN：阶段 14 契约夹具用 9001，服务路径的计数器从 0001 起，两边都碰不到。 */
    static final int CASE_NO_BASE = 9300;
    static final String ALARM_NO_PREFIX = "告警-0908-4";

    private static final String ORG = LocalStage13DisposalSeeder.ORG;
    private static final String DISTRICT = LocalStage13DisposalSeeder.DISTRICT;
    private static final String SOURCE = LocalStage13DisposalSeeder.SOURCE;
    private static final String RECIPIENT = LocalStage14PunishmentSeeder.RECIPIENT;
    /** 与 PunishmentCaseService.ISSUER_ORG 同值：那边是私有常量，文书上的出具单位两边必须一致。 */
    private static final String ISSUER_ORG = "东营市低空安全管理平台";
    private static final Instant T0 = Instant.parse("2026-09-08T04:00:00Z");
    private static final Duration STEP = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final HandoffMaterialAssembler materials;
    private final ObjectMapper json;

    public LocalDemoVolumePunishmentSeeder(JdbcTemplate jdbc, HandoffMaterialAssembler materials, ObjectMapper json) {
        this.jdbc = jdbc; this.materials = materials; this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!prerequisitesPresent()) return;
        Person officer = user("admin1");
        Person reviewer = user(LocalStage15DemoReviewerSeeder.ACCOUNT);
        if (officer == null || reviewer == null || officer.id().equals(reviewer.id())) return;
        Rule pr01 = rule("PR-01"), pr02 = rule("PR-02"), pr03 = rule("PR-03"), pr08 = rule("PR-08");
        if (pr01 == null || pr02 == null || pr03 == null || pr08 == null) return;   // 阶段 14 迁移没装：没有档位就没有裁量。

        recipient(T0);

        // 1. 刚立案：没有承办人、没有裁量，页面上只有"指派"可点。
        CaseBuilder filed = newCase(1, "PERSON", "演示当事人·甲", true);
        source(filed, officer, reviewer);
        file(filed, officer, "体量夹具：刚从处罚交接立案，尚未指派承办人");
        persist(filed, officer);

        // 2. 调查中（复核退回）：确认过一版裁量，复核认为证据不足退回；承办人补齐了一条线索、另一条还挂着，并已重拟草稿。
        CaseBuilder investigating = newCase(2, "PERSON", "演示当事人·乙", false);
        source(investigating, officer, reviewer);
        file(investigating, officer, "体量夹具：复核退回后继续调查");
        assign(investigating, officer);
        Discretion first = draft(investigating, officer, pr08, PunishmentRules.FINE, reference(pr08),
                factors("MITIGATING", "初次违法，配合调查"), "演示：夜间飞行未符合要求，按参考值拟处罚款");
        confirm(investigating, officer, first);
        List<Lead> leads = review(investigating, reviewer, PunishmentRules.INSUFFICIENT,
                "演示复核：夜间飞行的时段证据只有告警记录，缺少设备侧的原始记录；当事人陈述也未固定",
                List.of(new String[]{"EVIDENCE", "补充探测设备的原始记录，证明飞行发生在夜航时段"},
                        new String[]{"FACT", "固定当事人对飞行时段的陈述"}));
        resolveLead(investigating, officer, leads.get(0), "已调取探测设备 21:03–21:17 的原始记录并入卷");
        draft(investigating, officer, pr08, PunishmentRules.FINE, reference(pr08),
                factors("MITIGATING", "初次违法，配合调查；证据已补齐"), "演示：补证后重拟，金额不变");
        persist(investigating, officer);

        // 3. 复核中：裁量已确认，等 reviewer1 来复核；这是浏览器里两人链路的起点。
        CaseBuilder underReview = newCase(3, "ORG", "演示当事单位·丙", true);
        source(underReview, officer, reviewer);
        file(underReview, officer, "体量夹具：待复核");
        assign(underReview, officer);
        Discretion pending = draft(underReview, officer, pr03, PunishmentRules.FINE, reference(pr03),
                factors("NONE", "无从重或从轻情节"), "演示：超出空域限高飞行，按参考值拟处罚款");
        confirm(underReview, officer, pending);
        persist(underReview, officer);

        // 4. 已决定：第一版裁量偏重被复核要求改正（REVISED），第二版维持（UPHELD）；出具过一份决定书又作废，等待重新出具。
        CaseBuilder decided = newCase(4, "PERSON", "演示当事人·丁", false);
        source(decided, officer, reviewer);
        file(decided, officer, "体量夹具：已决定，决定书作废待重出");
        assign(decided, officer);
        Discretion heavy = draft(decided, officer, pr01, PunishmentRules.WARNING_AND_FINE, pr01.fineMax(),
                factors("AGGRAVATING", "多次劝阻不听"), "演示：未经批准擅自飞行，按上限拟处");
        confirm(decided, officer, heavy);
        review(decided, reviewer, PunishmentRules.REVISED,
                "演示复核：多次劝阻的事实只有口头记录，不足以支持顶格处罚；请按参考值重新裁量", List.of());
        Discretion revised = draft(decided, officer, pr01, PunishmentRules.WARNING_AND_FINE, reference(pr01),
                factors("NONE", "劝阻记录不足以认定从重情节"), "演示：按复核意见改按参考值拟处");
        confirm(decided, officer, revised);
        review(decided, reviewer, PunishmentRules.UPHELD, "演示复核：事实清楚、证据充分，维持第二版裁量", List.of());
        Document revoked = issue(decided, officer, revised, pr01);
        revoke(decided, officer, revoked, "演示：文书中当事人名称录入有误，作废后重新出具");
        persist(decided, officer);

        // 5. 已结案：一版裁量、一次维持、一份有效决定书、结案。
        CaseBuilder closed = newCase(5, "ORG", "演示当事单位·戊", false);
        source(closed, officer, reviewer);
        file(closed, officer, "体量夹具：已结案");
        assign(closed, officer);
        Discretion upheld = draft(closed, officer, pr02, PunishmentRules.FINE, reference(pr02),
                factors("NONE", "无从重或从轻情节"), "演示：进入禁飞空域飞行，按参考值拟处罚款");
        confirm(closed, officer, upheld);
        review(closed, reviewer, PunishmentRules.UPHELD, "演示复核：禁飞区边界与航迹均有记录，维持", List.of());
        issue(closed, officer, upheld, pr02);
        close(closed, officer, "演示：决定书已出具，案件办结");
        persist(closed, officer);

        // 6. 撤案：当事人始终无法认定（party_type=UNKNOWN，名称必须为空，14-12），线索挂着就撤了。
        CaseBuilder withdrawn = newCase(6, PunishmentRules.PARTY_UNKNOWN, null, false);
        source(withdrawn, officer, reviewer);
        file(withdrawn, officer, "体量夹具：撤案");
        assign(withdrawn, officer);
        addLead(withdrawn, officer, "PARTY_IDENTITY", "现场未能控制无人机，也未查到实名登记信息");
        withdraw(withdrawn, officer, "演示：当事人无法认定，依法撤案");
        persist(withdrawn, officer);
    }

    /* ---- 前提 ---- */

    /** 只引用已有的机构/区域/来源（阶段 4/13 的种子），不自建；缺了就整体不装。 */
    private boolean prerequisitesPresent() {
        return exists("SELECT COUNT(*) FROM app_org WHERE org_id=? AND enabled=TRUE", ORG)
                && exists("SELECT COUNT(*) FROM app_district WHERE district_id=? AND enabled=TRUE", DISTRICT)
                && exists("SELECT COUNT(*) FROM integration_source WHERE source_id=?", SOURCE);
    }

    private boolean exists(String countSql, Object... args) {
        Integer count = jdbc.queryForObject(countSql, Integer.class, args);
        return count != null && count > 0;
    }

    /** 姓名取 app_user.name（决策 14-27）：卷宗与文书上署的是姓名，不是登录账号；取不到才退回账号。 */
    private Person user(String account) {
        return jdbc.query("SELECT user_id,name FROM app_user WHERE account=? AND status='ACTIVE'",
                rs -> rs.next() ? new Person(rs.getString(1), rs.getString(2) == null ? account : rs.getString(2)) : null,
                account);
    }

    private Rule rule(String ruleCode) {
        return jdbc.query("SELECT violation_code,title,legal_basis,fine_min,fine_max,fine_reference,penalty_types"
                + " FROM penalty_rule WHERE rule_code=? AND enabled=TRUE",
                rs -> rs.next() ? new Rule(ruleCode, rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getLong(5), rs.getObject(6) == null ? null : rs.getLong(6), rs.getString(7)) : null,
                ruleCode);
    }

    /** 金额一律从档位行取（参考值，没有参考值取下限），代码里不写任何数字——区间是 DEMO 占位，改了迁移这里照样落在区间内。 */
    private static long reference(Rule rule) {
        return rule.fineReference() != null ? rule.fineReference() : rule.fineMin();
    }

    /** 阶段 14 契约夹具在阶段 13 事件缺席时会整体跳过、连接收方也不建；这里补同一行，免得交接挂在一个不存在的接收方上。 */
    private void recipient(Instant at) {
        jdbc.update("INSERT INTO handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " SELECT ?,'演示处罚接收方（公安）','UAV_PUNISHMENT',TRUE,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=?)",
                RECIPIENT, ts(at), ts(at), RECIPIENT);
    }

    /* ---- 来源链：告警 → 已核实事件 → 处罚交接（快照 v2） ---- */

    private CaseBuilder newCase(int seq, String partyType, String partyName, boolean withTarget) {
        // 每件案子隔一小时；告警在立案前两小时发生，核实、交接依次在前。
        return new CaseBuilder(seq, partyType, partyName, withTarget, T0.plus(Duration.ofHours(seq)));
    }

    /**
     * 告警与事件的形状与 {@link LocalStage13DisposalSeeder#alarmAndConfirmedEvent} 一致（阶段 4 夹具的 CONFIRMED 变体）：
     * 事件直接写成 CONFIRMED、version=1，并补一条 version=1 的人工核实记录（决策 14-31）——
     * 材料包里的 verifications 才有"谁在什么时候认定属实"。
     * 处置授权按演示主线写全：已完成联动反制，再接一条 chained 的已完成信号干扰（决策 13-6 / 13-34）。
     * 审批人用 reviewer1，与申请人 admin1 分开（策略 two_person_rule）。
     */
    private void source(CaseBuilder c, Person submitter, Person approver) {
        Instant occurred = c.filedAt.minus(Duration.ofHours(2));
        Instant verified = occurred.plus(Duration.ofMinutes(30));
        Instant submitted = occurred.plus(Duration.ofHours(1));
        Instant cmAt = verified.plus(Duration.ofMinutes(2));
        Instant cmDone = verified.plus(Duration.ofMinutes(12));
        Instant jamAt = cmDone;
        Instant jamDone = verified.plus(Duration.ofMinutes(22));
        String alarmNo = ALARM_NO_PREFIX + String.format("%02d", c.seq);
        // 目标只引用阶段 4 已有的共享目标；不存在就落 NULL，不自建目标。
        String target = c.withTarget ? jdbc.query("SELECT target_id FROM target WHERE target_id='seed-stage4-target-shared'",
                rs -> rs.next() ? rs.getString(1) : null) : null;
        jdbc.update("INSERT INTO alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " SELECT ?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM alarm WHERE alarm_id=? OR (source_id=? AND source_alarm_id=?))",
                c.alarmId, target, SOURCE, alarmNo, ts(occurred), ts(occurred), ORG, DISTRICT, ts(occurred),
                c.alarmId, SOURCE, alarmNo);
        jdbc.update("INSERT INTO uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " SELECT ?,?,'CONFIRMED',?,?,?,?,1 WHERE EXISTS (SELECT 1 FROM alarm WHERE alarm_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM uav_event WHERE event_id=? OR alarm_id=?)",
                c.eventId, c.alarmId, ORG, DISTRICT, ts(occurred), ts(verified), c.alarmId, c.eventId, c.alarmId);
        jdbc.update("INSERT INTO uav_event_verification (history_id,event_id,version,previous_state,resulting_state,"
                + "conclusion,note,actor_id,created_at)"
                + " SELECT ?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','本地演示（体量）：人工核实属实',?,?"
                + " WHERE EXISTS (SELECT 1 FROM uav_event WHERE event_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM uav_event_verification WHERE event_id=?)",
                c.verificationId, c.eventId, submitter.id(), ts(verified), c.eventId, c.eventId);
        completedAuthorization(c.cmAuthId, c.cmNo, c.eventId, "COUNTERMEASURE", null, submitter, approver, cmAt, cmDone,
                "本地演示：人工反制完成，目标已受控");
        String cmId = completedAuthId(c.eventId, "COUNTERMEASURE");
        if (cmId != null) {
            completedAuthorization(c.jamAuthId, c.jamNo, c.eventId, "JAMMING", cmId, submitter, approver, jamAt, jamDone,
                    "本地演示：反制完成后自动干扰已完成");
        }
        // 交接：source_mode 从告警取（14-22），快照走服务层同一个组装器（14-28），投递停在"通道未接入"。
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,"
                + "source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " SELECT ?,'UAV_EVENT',?,NULL,?,'UAV_PUNISHMENT',?,1,e.owner_org_id,e.district_id,a.source_mode,?,?"
                + " FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?"
                + " AND EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=?)"
                + " AND EXISTS (SELECT 1 FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=e.event_id"
                + "   AND status='COMPLETED')"
                + " AND NOT EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?"
                + "   OR (source_kind='UAV_EVENT' AND source_id=? AND handoff_type='UAV_PUNISHMENT' AND recipient_id=?))",
                c.handoffId, c.eventId, c.eventId, RECIPIENT, submitter.id(), ts(submitted), c.eventId, RECIPIENT,
                c.handoffId, c.eventId, RECIPIENT);
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at)"
                + " SELECT ?,2,CAST(? AS JSON),? WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_material_snapshot WHERE handoff_id=?)",
                c.handoffId, materialSnapshot(c.eventId), ts(submitted), c.handoffId, c.handoffId);
        jdbc.update("INSERT INTO handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,"
                + "blocked_reason,created_at) SELECT ?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?"
                + " WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_delivery WHERE handoff_id=?)",
                c.deliveryId, c.handoffId, ts(submitted), c.handoffId, c.handoffId);
    }

    private String materialSnapshot(String eventId) {
        try {
            return json.writeValueAsString(materials.assemble(eventId, true));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize demo volume punishment snapshot for " + eventId, ex);
        }
    }

    /** 人工通道已完成授权。干扰行把 chained_from 指到反制，与服务路径 13-34 同形。 */
    private void completedAuthorization(String id, String no, String eventId, String actionType, String chainedFrom,
            Person requester, Person approver, Instant at, Instant done, String resultDetail) {
        Instant validUntil = at.plus(Duration.ofMinutes(30));
        String reason = "本地演示：处罚体量夹具按主线完成联动反制";
        if ("JAMMING".equals(actionType)) {
            String parentNo = chainedFrom == null ? null
                    : jdbc.query("SELECT authorization_no FROM disposal_authorization WHERE authorization_id=?",
                            rs -> rs.next() ? rs.getString(1) : null, chainedFrom);
            reason = parentNo == null ? "反制完成后自动发起信号干扰"
                    : "反制完成后自动发起信号干扰（来源 " + parentNo + "）";
        }
        String decision = "COUNTERMEASURE".equals(actionType) ? "本地演示：批准反制" : "反制完成后自动批准，不再二次审批";
        jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,decision_note,"
                + "valid_from,valid_until,status,execution_command_id,result_code,result_detail,policy_version,"
                + "owner_org_id,district_id,source_mode,chained_from_authorization_id,version,created_at,updated_at)"
                + " SELECT ?,?,?,'UAV_EVENT',?,NULL,NULL,'MANUAL',?,?,?,?,?,?,?,?,'COMPLETED',NULL,'MANUAL_SUCCEEDED',?,"
                + "'demo-v1',e.owner_org_id,e.district_id,'mock',?,1,?,?"
                + " FROM uav_event e WHERE e.event_id=? AND e.state_code='CONFIRMED'"
                + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=? OR authorization_no=?"
                + "   OR (subject_kind='UAV_EVENT' AND subject_id=? AND action_type=?))",
                id, no, actionType, eventId, reason, requester.id(), ts(at), approver.id(), ts(at), decision,
                ts(at), ts(validUntil), resultDetail, chainedFrom, ts(at), ts(done),
                eventId, id, no, eventId, actionType);
        authorizationEvent(id, "REQUEST", requester.id(), at);
        authorizationEvent(id, "APPROVE", approver.id(), at.plusSeconds(60));
        authorizationEvent(id, "EXECUTE", requester.id(), at.plusSeconds(120));
        authorizationEvent(id, "MANUAL_RESULT", requester.id(), done);
    }

    private String completedAuthId(String eventId, String actionType) {
        return jdbc.query("SELECT authorization_id FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=?"
                + " AND action_type=? AND status='COMPLETED' ORDER BY requested_at ASC",
                rs -> rs.next() ? rs.getString(1) : null, eventId, actionType);
    }

    private void authorizationEvent(String authorizationId, String kind, String actor, Instant at) {
        String kindKey = switch (kind) {
            case "REQUEST" -> "rq";
            case "APPROVE" -> "ap";
            case "EXECUTE" -> "ex";
            case "MANUAL_RESULT" -> "mr";
            default -> kind.toLowerCase();
        };
        // VARCHAR(36)：seed-vol-pcase-jam-01-mr = 23。
        String eventId = authorizationId + "-" + kindKey;
        jdbc.update("INSERT INTO disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at)"
                + " SELECT ?,?,?,?,'本地演示夹具',NULL,?"
                + " WHERE EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization_event WHERE event_id=?)",
                eventId, authorizationId, kind, actor, ts(at), authorizationId, eventId);
    }

    /* ---- 案件历史：与 PunishmentCaseService 的每个动作逐一对应 ---- */

    private void file(CaseBuilder c, Person filer, String note) {
        c.status = PunishmentRules.FILED;
        c.event("FILE", filer, note, snapshot("status", c.status, "case_no", c.caseNo, "handoff_id", c.handoffId));
    }

    private void assign(CaseBuilder c, Person officer) {
        c.step();
        c.officer = officer;
        c.status = PunishmentRules.INVESTIGATING;
        c.transition();
        c.event("ASSIGN", officer, null, snapshot("status", c.status, "officer_id", officer.id()));
    }

    private Lead addLead(CaseBuilder c, Person actor, String kind, String description) {
        c.step();
        Lead lead = c.newLead(kind, description, actor);
        c.transition();   // 线索不改状态但推进版本，与服务路径一致。
        c.event("LEAD_ADDED", actor, description, snapshot("lead_id", lead.id, "kind", kind));
        return lead;
    }

    private void resolveLead(CaseBuilder c, Person actor, Lead lead, String note) {
        c.step();
        lead.resolvedBy = actor; lead.resolvedAt = c.at; lead.resolvedNote = note;
        c.transition();
        c.event("LEAD_RESOLVED", actor, note, snapshot("lead_id", lead.id));
    }

    private Discretion draft(CaseBuilder c, Person actor, Rule rule, String penaltyType, long fineAmount,
                             String factorsJson, String basisText) {
        // 区间与允许种类都按档位行校验（14-9/14-24）；这是本文件自己的一致性，错了就该在启动时炸，而不是装出一份非法裁量。
        if (!rule.penaltyTypes().contains("\"" + penaltyType + "\""))
            throw new IllegalStateException("demo volume seed: " + rule.ruleCode() + " does not allow " + penaltyType);
        boolean valid = PunishmentRules.WARNING.equals(penaltyType) ? fineAmount == 0
                : fineAmount >= rule.fineMin() && fineAmount <= rule.fineMax();
        if (!valid) throw new IllegalStateException("demo volume seed: fine " + fineAmount + " outside " + rule.ruleCode());
        c.step();
        for (Discretion d : c.discretions) if (PunishmentRules.DRAFT.equals(d.status)) d.status = PunishmentRules.SUPERSEDED;
        Discretion d = c.newDiscretion(rule, penaltyType, fineAmount, factorsJson, basisText, actor);
        c.primaryViolation = rule.violationCode();
        c.transition();
        c.event("DISCRETION_DRAFTED", actor, null,
                snapshot("discretion_id", d.id, "version_no", d.versionNo, "rule_code", rule.ruleCode()));
        return d;
    }

    private void confirm(CaseBuilder c, Person actor, Discretion d) {
        c.step();
        d.status = PunishmentRules.CONFIRMED; d.decidedBy = actor; d.decidedAt = c.at;
        c.status = PunishmentRules.UNDER_REVIEW;   // 确认裁量即提请复核（14-19）。
        c.transition();
        c.event("DISCRETION_CONFIRMED", actor, null, snapshot("discretion_id", d.id));
        c.event("REVIEW_REQUESTED", actor, null, snapshot("status", c.status));
    }

    /** 复核人必须不是承办人（14-11）；这里两人固定为 reviewer1 / admin1，run() 入口已确认不是同一个人。 */
    private List<Lead> review(CaseBuilder c, Person reviewer, String conclusion, String note, List<String[]> missingLeads) {
        if (c.officer == null || c.officer.id().equals(reviewer.id()))
            throw new IllegalStateException("demo volume seed: reviewer must differ from the officer");
        c.step();
        List<Map<String, Object>> leadsJson = new ArrayList<>();
        for (String[] lead : missingLeads) leadsJson.add(snapshotMap("kind", lead[0], "description", lead[1]));
        c.reviews.add(new Review(c.reviewId(), reviewer, conclusion, note, leadsJson.isEmpty() ? null : write(leadsJson), c.at));
        List<Lead> created = new ArrayList<>();
        String next = PunishmentRules.statusAfterReview(conclusion);
        if (!PunishmentRules.UPHELD.equals(conclusion)) {
            // 退回调查：已确认裁量作废，待补线索挂到案件上（14-11）。
            for (Discretion d : c.discretions) if (PunishmentRules.CONFIRMED.equals(d.status)) d.status = PunishmentRules.SUPERSEDED;
            for (String[] lead : missingLeads) created.add(c.newLead(lead[0], lead[1], reviewer));
        } else {
            c.decidedAt = c.at;
        }
        c.status = next;
        c.transition();
        c.event("REVIEW_CONCLUDED", reviewer, note, snapshot("conclusion", conclusion, "status", next));
        return created;
    }

    private Document issue(CaseBuilder c, Person actor, Discretion d, Rule rule) {
        if (!PunishmentRules.DECIDED.equals(c.status) || !PunishmentRules.CONFIRMED.equals(d.status))
            throw new IllegalStateException("demo volume seed: document needs DECIDED case and CONFIRMED discretion");
        c.step();
        String documentNo = PunishmentRules.documentNo(c.caseNo, c.documents.size() + 1);
        Map<String, Object> fields = DecisionDocumentRenderer.fields(documentNo, c.caseNo, c.partyName, rule.title(),
                rule.legalBasis(), d.penaltyType, d.fineAmount, d.basisText, ISSUER_ORG, actor.name(), c.at);
        String sha = DecisionDocumentRenderer.sha256(DecisionDocumentRenderer.render(fields));
        Document doc = new Document(c.documentId(), documentNo, d, write(fields), sha, actor, c.at);
        c.documents.add(doc);
        c.transition();
        c.event("DOCUMENT_ISSUED", actor, null, snapshot("document_id", doc.id, "document_no", documentNo));
        return doc;
    }

    /** 作废只动文书（版本 +1），不推进案件版本——与 revokeDocument 一致。 */
    private void revoke(CaseBuilder c, Person actor, Document doc, String reason) {
        c.step();
        doc.revokedAt = c.at; doc.revokeReason = reason;
        c.event("DOCUMENT_REVOKED", actor, reason, snapshot("document_id", doc.id, "document_no", doc.documentNo));
    }

    private void close(CaseBuilder c, Person actor, String note) {
        boolean issued = c.documents.stream().anyMatch(doc -> doc.revokedAt == null);
        if (!PunishmentRules.DECIDED.equals(c.status) || !issued)
            throw new IllegalStateException("demo volume seed: close needs DECIDED case with an ISSUED document");
        c.step();
        c.status = PunishmentRules.CLOSED; c.closedAt = c.at; c.closeNote = note;
        c.transition();
        c.event("CLOSE", actor, note, snapshot("status", c.status));
    }

    private void withdraw(CaseBuilder c, Person actor, String reason) {
        c.step();
        c.status = PunishmentRules.WITHDRAWN; c.withdrawReason = reason;
        c.transition();
        c.event("WITHDRAW", actor, reason, snapshot("status", c.status));
    }

    /* ---- 落库 ---- */

    private void persist(CaseBuilder c, Person filer) {
        // 案件行：机构/区域随事件，source_mode 随交接（14-22）；一事件一案，event_id/case_no 撞上任何一个都不插。
        jdbc.update("INSERT INTO punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,party_name,"
                + "officer_id,officer_name,primary_violation_code,filed_by,filed_by_name,filed_at,decided_at,closed_at,"
                + "close_note,withdraw_reason,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,e.owner_org_id,e.district_id,h.source_mode,?,?,?"
                + " FROM uav_event e JOIN handoff h ON h.handoff_id=? WHERE e.event_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM punishment_case WHERE case_id=? OR event_id=? OR case_no=?)",
                c.caseId, c.caseNo, c.eventId, c.handoffId, c.status, c.partyType, c.partyName,
                c.officer == null ? null : c.officer.id(), c.officer == null ? null : c.officer.name(),
                c.primaryViolation, filer.id(), filer.name(), ts(c.filedAt), ts(c.decidedAt), ts(c.closedAt),
                c.closeNote, c.withdrawReason, c.version, ts(c.filedAt), ts(c.at),
                c.handoffId, c.eventId, c.caseId, c.eventId, c.caseNo);
        for (Discretion d : c.discretions) {
            jdbc.update("INSERT INTO penalty_discretion (discretion_id,case_id,version_no,status,violation_code,rule_code,"
                    + "penalty_type,fine_amount,factors,basis_text,drafted_by,drafted_at,decided_by,decided_at)"
                    + " SELECT ?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?,?"
                    + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                    + " AND EXISTS (SELECT 1 FROM penalty_rule WHERE rule_code=?)"
                    + " AND NOT EXISTS (SELECT 1 FROM penalty_discretion WHERE discretion_id=?)",
                    d.id, c.caseId, d.versionNo, d.status, d.rule.violationCode(), d.rule.ruleCode(), d.penaltyType,
                    d.fineAmount, d.factorsJson, d.basisText, d.draftedBy.id(), ts(d.draftedAt),
                    d.decidedBy == null ? null : d.decidedBy.id(), ts(d.decidedAt),
                    c.caseId, d.rule.ruleCode(), d.id);
        }
        for (Document doc : c.documents) {
            boolean revoked = doc.revokedAt != null;
            jdbc.update("INSERT INTO penalty_decision_document (document_id,document_no,case_id,discretion_id,"
                    + "template_version,status,fields,rendered_sha256,issued_by,issued_by_name,issued_at,revoked_at,"
                    + "revoke_reason,version,updated_at)"
                    + " SELECT ?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?,?,?,?,?"
                    + " WHERE EXISTS (SELECT 1 FROM penalty_discretion WHERE discretion_id=? AND case_id=?)"
                    + " AND NOT EXISTS (SELECT 1 FROM penalty_decision_document WHERE document_id=? OR document_no=?)",
                    doc.id, doc.documentNo, c.caseId, doc.discretion.id, DecisionDocumentRenderer.TEMPLATE_VERSION,
                    revoked ? PunishmentRules.REVOKED : PunishmentRules.ISSUED, doc.fieldsJson, doc.sha256,
                    doc.issuedBy.id(), doc.issuedBy.name(), ts(doc.issuedAt), ts(doc.revokedAt), doc.revokeReason,
                    revoked ? 1L : 0L, ts(revoked ? doc.revokedAt : doc.issuedAt),
                    doc.discretion.id, c.caseId, doc.id, doc.documentNo);
        }
        for (Review r : c.reviews) {
            jdbc.update("INSERT INTO punishment_review (review_id,case_id,reviewer_id,reviewer_name,conclusion,note,"
                    + "missing_leads,created_at) SELECT ?,?,?,?,?,?,CAST(? AS JSON),?"
                    + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                    + " AND NOT EXISTS (SELECT 1 FROM punishment_review WHERE review_id=?)",
                    r.id, c.caseId, r.reviewer.id(), r.reviewer.name(), r.conclusion, r.note, r.missingLeadsJson,
                    ts(r.at), c.caseId, r.id);
        }
        for (Lead lead : c.leads) {
            boolean resolved = lead.resolvedBy != null;
            jdbc.update("INSERT INTO punishment_case_lead (lead_id,case_id,kind,description,resolved,resolved_note,"
                    + "created_by,created_at,resolved_by,resolved_at) SELECT ?,?,?,?,?,?,?,?,?,?"
                    + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                    + " AND NOT EXISTS (SELECT 1 FROM punishment_case_lead WHERE lead_id=?)",
                    lead.id, c.caseId, lead.kind, lead.description, resolved, resolved ? lead.resolvedNote : null,
                    lead.createdBy.id(), ts(lead.createdAt), resolved ? lead.resolvedBy.id() : null, ts(lead.resolvedAt),
                    c.caseId, lead.id);
        }
        for (CaseEvent e : c.events) {
            jdbc.update("INSERT INTO punishment_case_event (event_id,case_id,event_kind,actor_id,actor_name,note,"
                    + "snapshot,occurred_at) SELECT ?,?,?,?,?,?,CAST(? AS JSON),?"
                    + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                    + " AND NOT EXISTS (SELECT 1 FROM punishment_case_event WHERE event_id=?)",
                    e.id, c.caseId, e.kind, e.actor.id(), e.actor.name(), e.note, e.snapshotJson, ts(e.at),
                    c.caseId, e.id);
        }
    }

    /* ---- JSON 与时间 ---- */

    private String factors(String code, String text) {
        return write(List.of(snapshotMap("code", code, "text", text)));
    }

    private String snapshot(Object... keyValues) { return write(snapshotMap(keyValues)); }

    private static Map<String, Object> snapshotMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return map;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize demo volume punishment payload", ex); }
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    /* ---- 内存里的案件：先走完状态机，再一次性落库 ---- */

    private record Person(String id, String name) { }

    private record Rule(String ruleCode, String violationCode, String title, String legalBasis, long fineMin,
                        long fineMax, Long fineReference, String penaltyTypes) { }

    private static final class CaseBuilder {
        final int seq;
        final String caseId, caseNo, alarmId, eventId, verificationId, handoffId, deliveryId;
        final String cmAuthId, jamAuthId, cmNo, jamNo;
        final String partyType, partyName;
        final boolean withTarget;
        final Instant filedAt;
        Instant at;
        long version;
        String status, primaryViolation, closeNote, withdrawReason;
        Person officer;
        Instant decidedAt, closedAt;
        final List<CaseEvent> events = new ArrayList<>();
        final List<Discretion> discretions = new ArrayList<>();
        final List<Document> documents = new ArrayList<>();
        final List<Review> reviews = new ArrayList<>();
        final List<Lead> leads = new ArrayList<>();

        CaseBuilder(int seq, String partyType, String partyName, boolean withTarget, Instant filedAt) {
            String nn = String.format("%02d", seq);
            this.seq = seq;
            // 所有 id 都留在 VARCHAR(36) 以内：种子阶段撞长度会让整个上下文起不来。
            this.caseId = "seed-vol-case-" + nn;
            this.caseNo = PunishmentRules.caseNo(DAY_KEY, CASE_NO_BASE + seq);
            this.alarmId = "seed-vol-pcase-alarm-" + nn;
            this.eventId = "seed-vol-pcase-event-" + nn;
            this.verificationId = "seed-vol-pcase-verify-" + nn;
            this.handoffId = "seed-vol-pcase-handoff-" + nn;
            this.deliveryId = "seed-vol-pcase-delivery-" + nn;
            this.cmAuthId = "seed-vol-pcase-cm-" + nn;
            this.jamAuthId = "seed-vol-pcase-jam-" + nn;
            this.cmNo = "AUTH-20260908-94" + nn;
            this.jamNo = "AUTH-20260908-95" + nn;
            this.partyType = partyType; this.partyName = partyName; this.withTarget = withTarget;
            this.filedAt = filedAt; this.at = filedAt;
        }

        void step() { at = at.plus(STEP); }
        void transition() { version++; }

        void event(String kind, Person actor, String note, String snapshotJson) {
            events.add(new CaseEvent(caseId + "-ev-" + String.format("%02d", events.size() + 1), kind, actor, note,
                    snapshotJson, at));
        }

        Discretion newDiscretion(Rule rule, String penaltyType, long fineAmount, String factorsJson, String basisText,
                                 Person draftedBy) {
            int versionNo = discretions.size() + 1;
            Discretion d = new Discretion(caseId + "-disc-" + versionNo, versionNo, rule, penaltyType, fineAmount,
                    factorsJson, basisText, draftedBy, at);
            discretions.add(d);
            return d;
        }

        Lead newLead(String kind, String description, Person createdBy) {
            Lead lead = new Lead(caseId + "-lead-" + (leads.size() + 1), kind, description, createdBy, at);
            leads.add(lead);
            return lead;
        }

        String reviewId() { return caseId + "-rev-" + (reviews.size() + 1); }
        String documentId() { return caseId + "-doc-" + (documents.size() + 1); }
    }

    private record CaseEvent(String id, String kind, Person actor, String note, String snapshotJson, Instant at) { }

    private static final class Discretion {
        final String id; final int versionNo; final Rule rule; final String penaltyType; final long fineAmount;
        final String factorsJson, basisText; final Person draftedBy; final Instant draftedAt;
        String status = PunishmentRules.DRAFT;
        Person decidedBy; Instant decidedAt;

        Discretion(String id, int versionNo, Rule rule, String penaltyType, long fineAmount, String factorsJson,
                   String basisText, Person draftedBy, Instant draftedAt) {
            this.id = id; this.versionNo = versionNo; this.rule = rule; this.penaltyType = penaltyType;
            this.fineAmount = fineAmount; this.factorsJson = factorsJson; this.basisText = basisText;
            this.draftedBy = draftedBy; this.draftedAt = draftedAt;
        }
    }

    private static final class Document {
        final String id, documentNo; final Discretion discretion; final String fieldsJson, sha256;
        final Person issuedBy; final Instant issuedAt;
        Instant revokedAt; String revokeReason;

        Document(String id, String documentNo, Discretion discretion, String fieldsJson, String sha256, Person issuedBy,
                 Instant issuedAt) {
            this.id = id; this.documentNo = documentNo; this.discretion = discretion; this.fieldsJson = fieldsJson;
            this.sha256 = sha256; this.issuedBy = issuedBy; this.issuedAt = issuedAt;
        }
    }

    private record Review(String id, Person reviewer, String conclusion, String note, String missingLeadsJson, Instant at) { }

    private static final class Lead {
        final String id, kind, description; final Person createdBy; final Instant createdAt;
        Person resolvedBy; Instant resolvedAt; String resolvedNote;

        Lead(String id, String kind, String description, Person createdBy, Instant createdAt) {
            this.id = id; this.kind = kind; this.description = description; this.createdBy = createdBy;
            this.createdAt = createdAt;
        }
    }
}
