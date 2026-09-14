package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 14 处罚案件固定夹具（决策 14-16）：一条处罚接收方、一条挂在阶段 13 种子事件上的处罚交接、
 * 一件 INVESTIGATING 案件 + 一版 DRAFT 裁量 + 一条未解决线索。不造决定书与复核——正面路径留给浏览器走。
 *
 * 双门禁（!production & (local|test) + app.dev-seed.enabled）；全部写入 WHERE NOT EXISTS，重跑幂等。
 * 依赖的阶段 13 种子事件不存在时**直接返回**而不是报错：种子一炸整个 Spring 上下文都起不来，
 * 表现是所有用例全红，排查方向会被带偏（阶段 13 已经吃过两次这个亏）。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn({"localStage13DisposalSeeder"})
@Order(110)
public class LocalStage14PunishmentSeeder implements ApplicationRunner {
    static final String RECIPIENT = "seed-stage14-recipient-punish";
    static final String HANDOFF = "seed-stage14-handoff-punish";
    static final String CASE_ID = "seed-stage14-case-investigating";
    static final String CASE_NO = "CASE-20260908-9001";
    static final String DISCRETION = "seed-stage14-discretion-draft";
    static final String LEAD = "seed-stage14-lead-open";

    private final JdbcTemplate jdbc;
    private final HandoffMaterialAssembler materials;
    private final ObjectMapper json;

    public LocalStage14PunishmentSeeder(JdbcTemplate jdbc, HandoffMaterialAssembler materials, ObjectMapper json) {
        this.jdbc = jdbc; this.materials = materials; this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant at = Instant.parse("2026-09-08T03:00:00Z");
        String eventId = LocalStage13DisposalSeeder.EVENT;
        Integer event = jdbc.queryForObject("select count(*) from uav_event where event_id=?", Integer.class, eventId);
        if (event == null || event == 0) return;   // 阶段 13 种子没装：跳过而不是崩。
        String actor = adminUserId();
        if (actor == null) return;                 // 没有种子管理员就不造案件：案件必须挂在真实的承办人上。

        recipient(at);
        // 核实历史：阶段 13 的种子直接把事件写成 CONFIRMED，没有留下核实记录。
        // 处罚材料要回答"谁在什么时候认定它属实"（决策 14-2），少了这段，快照里的 verifications 就是空的。
        verification(eventId, actor, at);
        handoff(eventId, actor, at);
        punishmentCase(eventId, actor, adminUserName(), at);
        discretion(actor, at);
        lead(actor, at);
    }

    private String adminUserId() {
        return jdbc.query("SELECT user_id FROM app_user WHERE account='admin1'", rs -> rs.next() ? rs.getString(1) : null);
    }

    /**
     * 承办人/立案人的**姓名**（决策 14-27）：取 app_user.name，不是账号。
     * account 是登录名，塞进卷宗与决定书的署名里读起来像个账号；姓名才是对外要交代的那个身份。
     * 服务路径已经改对了，种子直插绕过它——这种不一致比两边都错更难发现，因为看代码只会看到对的那条路。
     */
    private String adminUserName() {
        String name = jdbc.query("SELECT name FROM app_user WHERE account='admin1'",
                rs -> rs.next() ? rs.getString(1) : null);
        return name == null ? "admin1" : name;
    }

    private void recipient(Instant at) {
        jdbc.update("INSERT INTO handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " SELECT ?,'演示处罚接收方（公安）','UAV_PUNISHMENT',TRUE,?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=?)",
                RECIPIENT, ts(at), ts(at), RECIPIENT);
    }

    /**
     * 交接头 + 快照 v2 的最小形状：事件段齐全，其余段留空数组，页面各块都有东西可看。
     *
     * source_mode 从**告警**取（决策 14-22），与服务路径同源，而不是写死。
     * 种子直插绕过了服务层，写死 'live' 会让一条 mock 数据造出来的演示交接在库里冒充真实来源——
     * 服务路径改对了、种子没改，反而更难发现：库里看到的就是 live。
     */
    private void handoff(String eventId, String actor, Instant at) {
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,"
                + "source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " SELECT ?,'UAV_EVENT',?,NULL,?,'UAV_PUNISHMENT',?,1,e.owner_org_id,e.district_id,a.source_mode,?,?"
                + " FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)",
                HANDOFF, eventId, eventId, RECIPIENT, actor, ts(at), eventId, HANDOFF);
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at)"
                + " SELECT ?,2,CAST(? AS JSON),? WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_material_snapshot WHERE handoff_id=?)",
                HANDOFF, snapshot(eventId), ts(at), HANDOFF, HANDOFF);
        jdbc.update("INSERT INTO handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,"
                + "blocked_reason,created_at) SELECT ?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?"
                + " WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_delivery WHERE handoff_id=?)",
                "seed-stage14-delivery-punish", HANDOFF, ts(at), HANDOFF, HANDOFF);
    }

    /**
     * 快照走**服务层同一套组装器**（决策 14-28），不手写 JSON。
     *
     * 手写壳子看起来能跑，但库里落下的是空 disposals、空 verifications 的假材料——页面照样渲染，
     * 而"事实清楚、证据充分"这件事根本没被证明过。种子的价值恰恰在于让人看到真实形状。
     * 种子以系统身份运行、没有登录上下文，因此证据段直接纳入（includeEvidence=true）。
     */
    private String snapshot(String eventId) {
        try {
            return json.writeValueAsString(materials.assemble(eventId, true));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize stage 14 seed snapshot", ex);
        }
    }

    /** 补一条人工核实记录：与事件的 CONFIRMED 状态对应，让材料包里的核实历史不是空的。 */
    private void verification(String eventId, String actor, Instant at) {
        jdbc.update("INSERT INTO uav_event_verification (history_id,event_id,version,previous_state,resulting_state,"
                + "conclusion,note,actor_id,created_at)"
                + " SELECT 'seed-s14-verify',?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','本地演示：人工核实属实',?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM uav_event_verification WHERE event_id=?)",
                eventId, actor, ts(at), eventId);
    }

    private void punishmentCase(String eventId, String actor, String actorName, Instant at) {
        jdbc.update("INSERT INTO punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,party_name,"
                + "officer_id,officer_name,filed_by,filed_by_name,filed_at,owner_org_id,district_id,source_mode,"
                + "version,created_at,updated_at)"
                + " SELECT ?,?,?,?,'INVESTIGATING','PERSON','演示当事人',?,?,?,?,?,"
                + "e.owner_org_id,e.district_id,'mock',1,?,?"
                + " FROM uav_event e WHERE e.event_id=?"
                + " AND EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM punishment_case WHERE case_id=? OR event_id=?)",
                CASE_ID, CASE_NO, eventId, HANDOFF, actor, actorName, actor, actorName, ts(at), ts(at), ts(at),
                eventId, HANDOFF, CASE_ID, eventId);
        jdbc.update("INSERT INTO punishment_case_event (event_id,case_id,event_kind,actor_id,actor_name,note,"
                + "snapshot,occurred_at) SELECT 'seed-s14-ev-file',?,'FILE',?,?,'本地演示夹具',NULL,?"
                + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM punishment_case_event WHERE event_id='seed-s14-ev-file')",
                CASE_ID, actor, actorName, ts(at), CASE_ID);
    }

    private void discretion(String actor, Instant at) {
        jdbc.update("INSERT INTO penalty_discretion (discretion_id,case_id,version_no,status,violation_code,rule_code,"
                + "penalty_type,fine_amount,factors,basis_text,drafted_by,drafted_at)"
                + " SELECT ?,?,1,'DRAFT',r.violation_code,'PR-01','FINE',50000,NULL,'演示：初次违法，未造成后果',?,?"
                + " FROM penalty_rule r WHERE r.rule_code='PR-01'"
                + " AND EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM penalty_discretion WHERE discretion_id=?)",
                DISCRETION, CASE_ID, actor, ts(at), CASE_ID, DISCRETION);
    }

    private void lead(String actor, Instant at) {
        jdbc.update("INSERT INTO punishment_case_lead (lead_id,case_id,kind,description,resolved,created_by,created_at)"
                + " SELECT ?,?,'PARTY_IDENTITY','演示：当事人身份尚未认定',FALSE,?,?"
                + " WHERE EXISTS (SELECT 1 FROM punishment_case WHERE case_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM punishment_case_lead WHERE lead_id=?)",
                LEAD, CASE_ID, actor, ts(at), CASE_ID, LEAD);
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
