package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RiskMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.VerificationMaterialDto;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;

/**
 * Stage5 交接固定夹具：两个 RISK_NOTICE 接收方、一份待投递样例、一份 source_mode=mock 的已送达历史样例。
 * 双门禁（!production & (local|test) + app.dev-seed.enabled）；生产不会注册，也不会自动插入任何接收方。
 * 所有写入都是 WHERE NOT EXISTS：重跑幂等，不覆盖人工修改，也不覆盖人工提交的交接。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn({"localUserSeeder", "localStage4RiskSeeder"})
// Runner 顺序：用户 10 → 阶段 3 计划 35 → 阶段 4 风险 45 → 阶段 5 设备映射 50 → 本种子 60；阶段 3/4 夹具已由前序 Runner 保证。
@Order(60)
public class LocalStage5HandoffSeeder implements ApplicationRunner {
    static final String RECIPIENT_POLICE = "seed-stage5-recipient-police";
    static final String RECIPIENT_AVIATION = "seed-stage5-recipient-aviation";
    static final String RISK_PENDING = "seed-stage5-risk-pending-delivery";
    static final String RISK_HISTORY = "seed-stage5-risk-delivered-history";
    static final String HANDOFF_PENDING = "seed-stage5-handoff-pending";
    static final String HANDOFF_DELIVERED = "seed-stage5-handoff-delivered";
    static final String DELIVERY_PENDING = "seed-stage5-delivery-pending";
    static final String DELIVERY_DELIVERED = "seed-stage5-delivery-delivered";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalStage5HandoffSeeder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc; this.objectMapper = objectMapper;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        List<String> admins = jdbc.queryForList("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        if (admins.isEmpty()) throw new IllegalStateException("stage 5 handoff seed requires the local admin1 user (LocalUserSeeder)");
        String submitter = admins.get(0);
        Instant base = Instant.parse("2026-09-05T01:00:00Z");
        recipient(RECIPIENT_POLICE, "公安机关（本地演示接收方）", base, false);
        // 全新库上迁移先跑、接收方还不存在，所以"标默认"这件事迁移替不了种子做：
        // 不在这里标，演示库起来后风险通知不传接收方就一律 400（决策 18-14 之后页面已经不传了）。
        recipient(RECIPIENT_AVIATION, "民航监管部门（本地演示接收方）", base, true);
        // 提交即已通知：待投递样例与已送达历史样例的源风险都是已通知；投递/回执在交接上单独看。
        risk(RISK_PENDING, "seed-stage5-verify-pending", "风险-0905-101", "HIGH", "ROUTE_DEVIATION", "已核验，材料已提交、渠道未接通", base, submitter);
        risk(RISK_HISTORY, "seed-stage5-verify-history", "风险-0905-102", "MEDIUM", "AIRSPACE_CONFLICT", "已核验，历史送达样例（mock）", base.plusSeconds(1), submitter);
        Instant submitted = base.plusSeconds(600);
        handoff(HANDOFF_PENDING, RISK_PENDING, RECIPIENT_POLICE, submitter, submitted, riskMaterial(RISK_PENDING, "风险-0905-101",
                "HIGH", "ROUTE_DEVIATION", "已核验，材料已提交、渠道未接通", base, submitter));
        delivery(DELIVERY_PENDING, HANDOFF_PENDING, HandoffRules.PENDING_DELIVERY, HandoffRules.NOT_EXPECTED,
                HandoffRules.CHANNEL_NOT_CONNECTED, submitted, null, null, null);
        Instant historySubmitted = base.plusSeconds(1200);
        handoff(HANDOFF_DELIVERED, RISK_HISTORY, RECIPIENT_AVIATION, submitter, historySubmitted, riskMaterial(RISK_HISTORY,
                "风险-0905-102", "MEDIUM", "AIRSPACE_CONFLICT", "已核验，历史送达样例（mock）", base.plusSeconds(1), submitter));
        // 已送达历史只在 local/test 存在且 source_mode=mock；没有任何接口能把生产交接写成这个状态。
        delivery(DELIVERY_DELIVERED, HANDOFF_DELIVERED, "DELIVERED", "ACKNOWLEDGED", null, historySubmitted,
                historySubmitted.plusSeconds(5), historySubmitted.plusSeconds(60), historySubmitted.plusSeconds(3600));
        // 已有库上这两条仍可能停在待通知（旧种子直插交接不推进状态）；只改待通知，不覆盖人工改过的行。
        jdbc.update("UPDATE flight_risk SET state_code='NOTIFIED', version=version+1 WHERE risk_id IN (?,?) AND state_code='PENDING_NOTIFICATION'",
                RISK_PENDING, RISK_HISTORY);
    }

    private void recipient(String id, String name, Instant at, boolean isDefault) {
        jdbc.update("INSERT INTO handoff_recipient (recipient_id,display_name,handoff_type,enabled,is_default,created_at,updated_at)"
                + " SELECT ?,?,'RISK_NOTICE',TRUE,?,?,? WHERE NOT EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=?)",
                id, name, isDefault, ts(at), ts(at), id);
    }

    private void risk(String id, String historyId, String sourceRisk, String severity, String reason, String text, Instant at, String actor) {
        Instant received = at.plusSeconds(5), verified = at.plusSeconds(300);
        jdbc.update("INSERT INTO flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,"
                + "reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " SELECT ?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','FLIGHT_OPERATION',?,'NOTIFIED',?,?,?,?,"
                + "'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,1 WHERE NOT EXISTS (SELECT 1 FROM flight_risk WHERE risk_id=?)",
                id, sourceRisk, severity, reason, text, ts(at), ts(received), ts(received), ts(verified), id);
        jdbc.update("UPDATE flight_risk SET source_risk_id=? WHERE risk_id=? AND source_risk_id<>?", sourceRisk, id, sourceRisk);
        jdbc.update("INSERT INTO flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at)"
                + " SELECT ?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED','本地演示：人工核验通过',?,?"
                + " WHERE NOT EXISTS (SELECT 1 FROM flight_risk_verification WHERE risk_id=? AND version=1)",
                historyId, id, actor, ts(verified), id);
    }

    private void handoff(String id, String riskId, String recipientId, String submitter, Instant at, MaterialDto material) {
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,"
                + "district_id,source_mode,submitted_by,created_at) SELECT ?,'RISK',?,?,NULL,'RISK_NOTICE',?,1,'seed-stage3-org','seed-stage3-district',"
                + "'mock',?,? WHERE NOT EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)", id, riskId, riskId, recipientId, submitter, ts(at), id);
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at) SELECT ?,?,CAST(? AS JSON),?"
                + " WHERE NOT EXISTS (SELECT 1 FROM handoff_material_snapshot WHERE handoff_id=?)",
                id, HandoffRules.SNAPSHOT_SCHEMA_VERSION, json(material), ts(at), id);
    }

    private void delivery(String id, String handoffId, String deliveryStatus, String receiptStatus, String blockedReason, Instant created,
            Instant submitted, Instant delivered, Instant acknowledged) {
        jdbc.update("INSERT INTO handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at,"
                + "submitted_at,delivered_at,acknowledged_at) SELECT ?,?,1,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM handoff_delivery WHERE handoff_id=? AND attempt_no=1)",
                id, handoffId, deliveryStatus, receiptStatus, blockedReason, ts(created), ts(submitted), ts(delivered), ts(acknowledged), handoffId);
    }

    private static MaterialDto riskMaterial(String riskId, String sourceRisk, String severity, String reason, String text, Instant at, String actor) {
        Instant received = at.plusSeconds(5), verified = at.plusSeconds(300);
        return new MaterialDto(HandoffRules.SNAPSHOT_SCHEMA_VERSION,
                new RiskMaterialDto(riskId, sourceRisk, "FLIGHT_OPERATION", severity, "PENDING_NOTIFICATION", reason, text,
                        at.toEpochMilli(), received.toEpochMilli(), 1),
                List.of(new VerificationMaterialDto("CONFIRMED", "本地演示：人工核验通过", "PENDING_NOTIFICATION", 1, verified.toEpochMilli(), actor)),
                new ReferenceMaterialDto("seed-stage3-plan-legal", "seed-stage3-rv-legal", null, null, null));
    }

    private String json(MaterialDto material) {
        try { return objectMapper.writeValueAsString(material); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("cannot serialize stage 5 seed material", ex); }
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
