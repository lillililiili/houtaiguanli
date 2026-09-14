package com.uav.lowaltitude.integration.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceRetention;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RiskMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.VerificationMaterialDto;
import com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;

/**
 * 交接与证据的演示"体量"夹具：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 *
 * 目的：让交接页把 delivery_status（待投递/已发送/已送达/发送失败）× receipt_status（不需回执/等待回执/已回执/回执超时）
 * 在风险通知（RISK_NOTICE）与处罚交接（UAV_PUNISHMENT）两种类型下都摆出来，含一份两次投递尝试的交接；
 * 让证据页每个 status（入库中/在库/文件缺失/哈希不符/已销毁）、多个种类、冻结/临近到期/已到期都有记录可看。
 *
 * 只复用既有夹具：阶段 5 的两个风险通知接收方、阶段 14 的处罚接收方、阶段 4/7 与体量种子的风险、
 * 体量告警种子的已确认事件、阶段 13 的已确认事件、体量目标与计划。不新建机构、区域、来源。
 * 处罚交接的前提是"该事件有已完成的处置授权"（决策 13-6），告警页流程条还要求能看见联动反制和信号干扰。
 * 因此每个用到的体量事件补齐人工通道的已完成反制，再接一条 chained 的已完成干扰（决策 13-34）；
 * 原先单条授权（驱离/仅干扰/仅反制）仍保留，作为处置列表的形态样例，不替代主线两步。
 * 材料快照 v1 按库内风险事实组装、v2 走服务层同一套 {@link HandoffMaterialAssembler}（决策 14-28），不手写壳子。
 *
 * 证据：AVAILABLE 的文件真实写进 app.evidence-dir，size/sha256 与文件一致，下载与校验都能走通；
 * MISSING 只有元数据没有文件；CORRUPT 有文件但库内 sha256 是另一份内容的哈希；PENDING 没有哈希也没有文件；
 * DESTROYED 只留元数据与销毁留痕。没有能用图片/视频真实内容的来源：图片种类用 1×1 PNG，录像种类只出现在
 * MISSING/CORRUPT/PENDING 三种"没有可播放内容"的状态里，文书/日志/快照用文本或 JSON 占位并在文件里注明演示。
 *
 * 全部写入都是 WHERE NOT EXISTS：重跑幂等，不覆盖人工改过的交接/证据行；行已存在时也不再重写存储文件
 * （否则人为删掉文件演示"缺失"后一重启又被补回）。依赖的种子行不存在时跳过对应记录，不让整个上下文起不来。
 * 唯一例外：{@link #alignSubmittedRisks()} 把「待通知且已有未失败交接」的源风险补成已通知，与提交接口口径一致。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(115)
@DependsOn({"localUserSeeder", "localStage5HandoffSeeder", "localStage7DemoVolumeSeeder", "localDemoVolumeAlarmSeeder",
        "localStage13DisposalSeeder", "localStage14PunishmentSeeder"})
public class LocalDemoVolumeHandoffEvidenceSeeder implements ApplicationRunner {
    private static final String RECIPIENT_AVIATION = LocalStage5HandoffSeeder.RECIPIENT_AVIATION;
    private static final String RECIPIENT_POLICE = LocalStage5HandoffSeeder.RECIPIENT_POLICE;
    private static final String RECIPIENT_PUNISH = LocalStage14PunishmentSeeder.RECIPIENT;
    private static final String S7_ORG = LocalStage7RuleEngineSeeder.ORG, S7_DISTRICT = LocalStage7RuleEngineSeeder.DISTRICT;
    /** 阶段四告警夹具的机构/区域：常量在 LocalStage4AlarmSeeder 内是字面量，这里照抄（与阶段十三种子同一做法）。 */
    private static final String S4_ORG = "seed-stage4-alarm-org", S4_DISTRICT = "seed-stage4-alarm-district";
    /** 体量告警种子的已确认事件（LocalDemoVolumeAlarmSeeder：01/02/08/12 为 CONFIRMED）；用字面量并在运行期用 EXISTS 守卫。 */
    private static final String EVENT_01 = "seed-vol-event-01", EVENT_02 = "seed-vol-event-02",
            EVENT_08 = "seed-vol-event-08", EVENT_12 = "seed-vol-event-12";
    private static final String EVENT_S13 = LocalStage13DisposalSeeder.EVENT;

    private static final String PENDING_DELIVERY = HandoffRules.PENDING_DELIVERY, SUBMITTED = "SUBMITTED",
            DELIVERED = "DELIVERED", FAILED = "FAILED";
    private static final String NOT_EXPECTED = HandoffRules.NOT_EXPECTED, RECEIPT_PENDING = "PENDING",
            ACKNOWLEDGED = "ACKNOWLEDGED", TIMEOUT = "TIMEOUT";

    /** 1×1 透明 PNG：图片种类的占位内容，能被浏览器与校验接口按真实文件处理。 */
    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HandoffMaterialAssembler materials;
    private final ObjectStoragePort storage;

    public LocalDemoVolumeHandoffEvidenceSeeder(JdbcTemplate jdbc, ObjectMapper json, HandoffMaterialAssembler materials,
            ObjectStoragePort storage) {
        this.jdbc = jdbc; this.json = json; this.materials = materials; this.storage = storage;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String actor = adminUserId();
        if (actor == null) return;   // 没有种子管理员就不造：交接提交人、核验人、冻结人都必须是真实用户。
        riskNoticeHandoffs(actor);
        completedDisposals(actor);
        evidence(actor);
        // 处罚交接放在证据之后：v2 快照冻结提交那一刻的证据段，先有证据再组装才不是空的。
        punishmentHandoffs(actor);
    }

    /* ------------------------------------------------------------------ 风险通知交接 */

    private void riskNoticeHandoffs(String actor) {
        // 体量风险种子没有核验历史；待通知/已通知都是核验之后的状态，补一条核验记录让材料包里"谁核验的"不是空的。
        riskVerification("seed-vol-risk-02", "seed-vol-verify-r02", actor, at("2026-09-05T02:05:00Z"), "本地演示：人工核验通过，偏离量超出容差");
        riskVerification("seed-vol-risk-03", "seed-vol-verify-r03", actor, at("2026-09-05T01:50:00Z"), "本地演示：人工核验通过，限高空域内超高");
        riskVerification("seed-vol-risk-07", "seed-vol-verify-r07", actor, at("2026-09-05T02:20:00Z"), "本地演示：人工核验通过，管制区内飞行");
        riskVerification("seed-vol-risk-09", "seed-vol-verify-r09", actor, at("2026-09-05T02:10:00Z"), "本地演示：人工核验通过，穿越禁飞区");
        riskVerification("seed-stage4-risk-confirmed", "seed-vol-verify-s4c", actor, at("2026-09-05T00:30:00Z"), "本地演示：人工核验通过");

        // 已通知的风险：一份已送达且已回执、一份已送达但回执超时。
        Instant t03 = at("2026-09-05T02:30:00Z");
        riskHandoff("seed-vol-handoff-r03-aviation", "seed-vol-risk-03", RECIPIENT_AVIATION, actor, t03);
        delivery("seed-vol-delivery-r03-aviation-1", "seed-vol-handoff-r03-aviation", 1, DELIVERED, ACKNOWLEDGED, null,
                t03, t03.plusSeconds(4), t03.plusSeconds(40), t03.plusSeconds(1_800));
        riskHandoff("seed-vol-handoff-r03-police", "seed-vol-risk-03", RECIPIENT_POLICE, actor, t03.plusSeconds(60));
        delivery("seed-vol-delivery-r03-police-1", "seed-vol-handoff-r03-police", 1, DELIVERED, TIMEOUT, null,
                t03.plusSeconds(60), t03.plusSeconds(65), t03.plusSeconds(120), null);
        // 已提交通知、投递未完成的风险：已发送等待回执、发送失败、两次尝试、渠道未接通的待投递。
        // 源风险按口径是已通知（提交即已通知）；下面 alignSubmittedRisks 会把仍停在待通知的行补上。
        Instant t02 = at("2026-09-05T02:40:00Z");
        riskHandoff("seed-vol-handoff-r02-police", "seed-vol-risk-02", RECIPIENT_POLICE, actor, t02);
        delivery("seed-vol-delivery-r02-police-1", "seed-vol-handoff-r02-police", 1, SUBMITTED, RECEIPT_PENDING, null,
                t02, t02.plusSeconds(5), null, null);
        riskHandoff("seed-vol-handoff-r02-aviation", "seed-vol-risk-02", RECIPIENT_AVIATION, actor, t02.plusSeconds(30));
        delivery("seed-vol-delivery-r02-aviation-1", "seed-vol-handoff-r02-aviation", 1, FAILED, NOT_EXPECTED, null,
                t02.plusSeconds(30), t02.plusSeconds(35), null, null);
        Instant t07 = at("2026-09-05T03:00:00Z");
        riskHandoff("seed-vol-handoff-r07-aviation", "seed-vol-risk-07", RECIPIENT_AVIATION, actor, t07);
        delivery("seed-vol-delivery-r07-aviation-1", "seed-vol-handoff-r07-aviation", 1, FAILED, NOT_EXPECTED, null,
                t07, t07.plusSeconds(5), null, null);
        delivery("seed-vol-delivery-r07-aviation-2", "seed-vol-handoff-r07-aviation", 2, DELIVERED, RECEIPT_PENDING, null,
                t07.plusSeconds(600), t07.plusSeconds(605), t07.plusSeconds(640), null);
        Instant t09 = at("2026-09-05T03:10:00Z");
        riskHandoff("seed-vol-handoff-r09-police", "seed-vol-risk-09", RECIPIENT_POLICE, actor, t09);
        delivery("seed-vol-delivery-r09-police-1", "seed-vol-handoff-r09-police", 1, PENDING_DELIVERY, NOT_EXPECTED,
                HandoffRules.CHANNEL_NOT_CONNECTED, t09, null, null, null);
        Instant tS4 = at("2026-09-05T01:00:00Z");
        riskHandoff("seed-vol-handoff-s4c-aviation", "seed-stage4-risk-confirmed", RECIPIENT_AVIATION, actor, tS4);
        delivery("seed-vol-delivery-s4c-aviation-1", "seed-vol-handoff-s4c-aviation", 1, SUBMITTED, NOT_EXPECTED, null,
                tS4, tS4.plusSeconds(5), null, null);
        alignSubmittedRisks();
    }

    /**
     * 提交通知即已通知（见 docs/风险通知状态口径.md）。体量种子原先直插交接却把源风险留在待通知，
     * 工作台会把「通知上级」当成当前步并置灰。只把「待通知且已有未失败交接」推进到已通知，已是已通知/已回执的不动。
     */
    private void alignSubmittedRisks() {
        jdbc.update("UPDATE flight_risk SET state_code='NOTIFIED', version=version+1"
                + " WHERE state_code='PENDING_NOTIFICATION'"
                + " AND EXISTS (SELECT 1 FROM handoff h WHERE h.source_kind='RISK' AND h.source_id=flight_risk.risk_id"
                + " AND h.handoff_type='RISK_NOTICE'"
                + " AND EXISTS (SELECT 1 FROM handoff_delivery d WHERE d.handoff_id=h.handoff_id AND d.delivery_status<>'FAILED'))");
    }

    private void riskVerification(String riskId, String historyId, String actor, Instant at, String note) {
        jdbc.update("INSERT INTO flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at)"
                + " SELECT ?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED',?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM flight_risk WHERE risk_id=? AND state_code IN ('PENDING_NOTIFICATION','NOTIFIED'))"
                + " AND NOT EXISTS (SELECT 1 FROM flight_risk_verification WHERE history_id=? OR risk_id=?)",
                historyId, riskId, note, actor, ts(at), riskId, historyId, riskId);
    }

    /** 交接头的范围、版本、来源模式都取自风险行本身，不写死；风险或接收方不存在时不插。 */
    private void riskHandoff(String id, String riskId, String recipientId, String actor, Instant at) {
        RiskRow risk = risk(riskId);
        if (risk == null) return;
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,"
                + "owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " SELECT ?,'RISK',r.risk_id,r.risk_id,NULL,'RISK_NOTICE',?,r.version,r.owner_org_id,r.district_id,r.source_mode,?,?"
                + " FROM flight_risk r WHERE r.risk_id=?"
                + " AND EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=? AND handoff_type='RISK_NOTICE')"
                + " AND NOT EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?"
                + "   OR (source_kind='RISK' AND source_id=? AND handoff_type='RISK_NOTICE' AND recipient_id=?))",
                id, recipientId, actor, ts(at), riskId, recipientId, id, riskId, recipientId);
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at) SELECT ?,?,CAST(? AS JSON),?"
                + " WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_material_snapshot WHERE handoff_id=?)",
                id, HandoffRules.SNAPSHOT_SCHEMA_VERSION, write(riskMaterial(risk)), ts(at), id, id);
    }

    private record RiskRow(String riskId, String sourceRiskId, String riskType, String severity, String state, String reasonCode,
            String reasonText, Instant occurredAt, Instant receivedAt, long version, String planId, String routeVersionId) { }

    private RiskRow risk(String riskId) {
        return jdbc.query("SELECT risk_id,source_risk_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,"
                + "version,plan_id,route_version_id FROM flight_risk WHERE risk_id=?",
                rs -> rs.next() ? new RiskRow(rs.getString("risk_id"), rs.getString("source_risk_id"), rs.getString("risk_type"),
                        rs.getString("severity"), rs.getString("state_code"), rs.getString("reason_code"), rs.getString("reason_text"),
                        instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("received_at")), rs.getLong("version"),
                        rs.getString("plan_id"), rs.getString("route_version_id")) : null,
                riskId);
    }

    /** v1 材料按库内风险事实组装（与 LocalStage5HandoffSeeder 同形），核验历史取真实记录。 */
    private MaterialDto riskMaterial(RiskRow risk) {
        List<VerificationMaterialDto> verifications = jdbc.query("SELECT conclusion,note,resulting_state,version,created_at,actor_id"
                + " FROM flight_risk_verification WHERE risk_id=? ORDER BY version ASC",
                (rs, i) -> new VerificationMaterialDto(rs.getString("conclusion"), rs.getString("note"), rs.getString("resulting_state"),
                        rs.getLong("version"), rs.getTimestamp("created_at").toInstant().toEpochMilli(), rs.getString("actor_id")),
                risk.riskId());
        return new MaterialDto(HandoffRules.SNAPSHOT_SCHEMA_VERSION,
                new RiskMaterialDto(risk.riskId(), risk.sourceRiskId(), risk.riskType(), risk.severity(), risk.state(),
                        risk.reasonCode(), risk.reasonText(), risk.occurredAt() == null ? null : risk.occurredAt().toEpochMilli(),
                        risk.receivedAt().toEpochMilli(), risk.version()),
                verifications,
                new ReferenceMaterialDto(risk.planId(), risk.routeVersionId(), null, null, null));
    }

    /* ------------------------------------------------------------------ 处罚交接 */

    /**
     * 每个用到的体量已确认事件补齐主线：已完成反制 + 已完成干扰。
     * 原先单条授权仍插入（处置列表要覆盖驱离/仅反制/仅干扰），主线两步按动作类型去重，已有则跳过。
     */
    private void completedDisposals(String actor) {
        completedDisposal("seed-vol-auth-e01", "AUTH-20260901-9101", EVENT_01, "JAMMING", S7_ORG, S7_DISTRICT, actor,
                at("2026-09-01T01:40:00Z"), "本地演示：现场人工干扰驱离，目标离开限高空域", null);
        completedDisposal("seed-vol-auth-e02", "AUTH-20260902-9102", EVENT_02, "COUNTERMEASURE", S7_ORG, S7_DISTRICT, actor,
                at("2026-09-02T07:00:00Z"), "本地演示：人工反制迫降，目标已回收", null);
        completedDisposal("seed-vol-auth-e08", "AUTH-20260907-9108", EVENT_08, "DISPERSAL", S4_ORG, S4_DISTRICT, actor,
                at("2026-09-07T06:10:00Z"), "本地演示：现场劝离操作人", null);
        completedDisposal("seed-vol-auth-e12", "AUTH-20260906-9112", EVENT_12, "JAMMING", S7_ORG, S7_DISTRICT, actor,
                at("2026-09-06T09:10:00Z"), "本地演示：人工干扰后目标降至计划高度以内", null);
        completedMainline(EVENT_01, S7_ORG, S7_DISTRICT, actor, at("2026-09-01T01:20:00Z"),
                "seed-vol-auth-e01cm", "AUTH-20260901-9121", "seed-vol-auth-e01jam", "AUTH-20260901-9123");
        completedMainline(EVENT_02, S7_ORG, S7_DISTRICT, actor, at("2026-09-02T06:50:00Z"),
                "seed-vol-auth-e02cm", "AUTH-20260902-9122", "seed-vol-auth-e02jam", "AUTH-20260902-9124");
        completedMainline(EVENT_08, S4_ORG, S4_DISTRICT, actor, at("2026-09-07T05:50:00Z"),
                "seed-vol-auth-e08cm", "AUTH-20260907-9128", "seed-vol-auth-e08jam", "AUTH-20260907-9129");
        completedMainline(EVENT_12, S7_ORG, S7_DISTRICT, actor, at("2026-09-06T08:50:00Z"),
                "seed-vol-auth-e12cm", "AUTH-20260906-9132", "seed-vol-auth-e12jam", "AUTH-20260906-9133");
    }

    private void completedMainline(String eventId, String org, String district, String actor, Instant cmAt,
            String cmId, String cmNo, String jamId, String jamNo) {
        completedDisposal(cmId, cmNo, eventId, "COUNTERMEASURE", org, district, actor, cmAt,
                "本地演示：主线联动反制已完成", null);
        String parent = completedAuthId(eventId, "COUNTERMEASURE");
        if (parent == null) return;
        completedDisposal(jamId, jamNo, eventId, "JAMMING", org, district, actor, cmAt.plusSeconds(900),
                "本地演示：反制完成后自动干扰已完成", parent);
    }

    private String completedAuthId(String eventId, String actionType) {
        return jdbc.query("SELECT authorization_id FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=?"
                + " AND action_type=? AND status='COMPLETED' ORDER BY requested_at ASC",
                rs -> rs.next() ? rs.getString(1) : null, eventId, actionType);
    }

    private void completedDisposal(String id, String no, String eventId, String actionType, String org, String district, String actor,
            Instant at, String resultDetail, String chainedFrom) {
        Instant validUntil = at.plusSeconds(1_800), done = at.plusSeconds(900);
        jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,"
                + "device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,decision_note,valid_from,valid_until,status,"
                + "execution_command_id,result_code,result_detail,policy_version,owner_org_id,district_id,source_mode,"
                + "chained_from_authorization_id,version,created_at,updated_at)"
                + " SELECT ?,?,?,'UAV_EVENT',?,NULL,NULL,'MANUAL','本地演示：体量交接种子的处置前提',?,?,?,?,'本地演示：已批准',?,?,'COMPLETED',"
                + "NULL,'MANUAL_SUCCEEDED',?,'demo-v1',?,?,'mock',?,1,?,?"
                + " WHERE EXISTS (SELECT 1 FROM uav_event WHERE event_id=? AND state_code='CONFIRMED')"
                + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=? OR authorization_no=?"
                + "   OR (subject_kind='UAV_EVENT' AND subject_id=? AND action_type=?))",
                id, no, actionType, eventId, actor, ts(at), actor, ts(at), ts(at), ts(validUntil), resultDetail, org, district,
                chainedFrom, ts(at), ts(done), eventId, id, no, eventId, actionType);
        disposalEvent(id, "REQUEST", actor, at);
        disposalEvent(id, "APPROVE", actor, at.plusSeconds(60));
        disposalEvent(id, "EXECUTE", actor, at.plusSeconds(120));
        disposalEvent(id, "MANUAL_RESULT", actor, done);
    }

    private void disposalEvent(String authorizationId, String kind, String actor, Instant at) {
        // event_id 须留在 VARCHAR(36) 内："seed-vol-dae-e01-manual_result" 30 字符。
        String eventId = "seed-vol-dae-" + authorizationId.substring(authorizationId.lastIndexOf('-') + 1) + "-" + kind.toLowerCase();
        jdbc.update("INSERT INTO disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at)"
                + " SELECT ?,?,?,?,'本地演示夹具',NULL,?"
                + " WHERE EXISTS (SELECT 1 FROM disposal_authorization WHERE authorization_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization_event WHERE event_id=?)",
                eventId, authorizationId, kind, actor, ts(at), authorizationId, eventId);
    }

    private void punishmentHandoffs(String actor) {
        Instant t01 = at("2026-09-01T02:30:00Z");
        eventHandoff("seed-vol-handoff-e01-punish", EVENT_01, actor, t01);
        delivery("seed-vol-delivery-e01-punish-1", "seed-vol-handoff-e01-punish", 1, SUBMITTED, RECEIPT_PENDING, null,
                t01, t01.plusSeconds(5), null, null);
        Instant t02 = at("2026-09-02T07:30:00Z");
        eventHandoff("seed-vol-handoff-e02-punish", EVENT_02, actor, t02);
        delivery("seed-vol-delivery-e02-punish-1", "seed-vol-handoff-e02-punish", 1, DELIVERED, ACKNOWLEDGED, null,
                t02, t02.plusSeconds(5), t02.plusSeconds(60), t02.plusSeconds(7_200));
        Instant t08 = at("2026-09-07T06:40:00Z");
        eventHandoff("seed-vol-handoff-e08-punish", EVENT_08, actor, t08);
        delivery("seed-vol-delivery-e08-punish-1", "seed-vol-handoff-e08-punish", 1, FAILED, NOT_EXPECTED, null,
                t08, t08.plusSeconds(5), null, null);
        Instant t12 = at("2026-09-06T09:40:00Z");
        eventHandoff("seed-vol-handoff-e12-punish", EVENT_12, actor, t12);
        delivery("seed-vol-delivery-e12-punish-1", "seed-vol-handoff-e12-punish", 1, DELIVERED, TIMEOUT, null,
                t12, t12.plusSeconds(5), t12.plusSeconds(90), null);
    }

    /** 与 LocalStage14PunishmentSeeder 同形：范围取事件、source_mode 取告警（决策 14-22），快照走服务层组装器（决策 14-28）。 */
    private void eventHandoff(String id, String eventId, String actor, Instant at) {
        Integer completed = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=?"
                + " AND status='COMPLETED'", Integer.class, eventId);
        if (completed == null || completed == 0) return;   // 前提不成立就不造处罚交接。
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,"
                + "owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " SELECT ?,'UAV_EVENT',e.event_id,NULL,e.event_id,'UAV_PUNISHMENT',?,e.version,e.owner_org_id,e.district_id,a.source_mode,?,?"
                + " FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=? AND e.state_code='CONFIRMED'"
                + " AND EXISTS (SELECT 1 FROM handoff_recipient WHERE recipient_id=? AND handoff_type='UAV_PUNISHMENT')"
                + " AND NOT EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?"
                + "   OR (source_kind='UAV_EVENT' AND source_id=? AND handoff_type='UAV_PUNISHMENT' AND recipient_id=?))",
                id, RECIPIENT_PUNISH, actor, ts(at), eventId, RECIPIENT_PUNISH, id, eventId, RECIPIENT_PUNISH);
        Integer inserted = jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE handoff_id=?", Integer.class, id);
        if (inserted == null || inserted == 0) return;
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at) SELECT ?,?,CAST(? AS JSON),?"
                + " WHERE NOT EXISTS (SELECT 1 FROM handoff_material_snapshot WHERE handoff_id=?)",
                id, HandoffMaterialAssembler.SCHEMA_V2, write(materials.assemble(eventId, true)), ts(at), id);
    }

    private void delivery(String id, String handoffId, int attempt, String deliveryStatus, String receiptStatus, String blockedReason,
            Instant created, Instant submitted, Instant delivered, Instant acknowledged) {
        jdbc.update("INSERT INTO handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at,"
                + "submitted_at,delivered_at,acknowledged_at) SELECT ?,?,?,?,?,?,?,?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM handoff WHERE handoff_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_delivery WHERE delivery_id=? OR (handoff_id=? AND attempt_no=?))",
                id, handoffId, attempt, deliveryStatus, receiptStatus, blockedReason, ts(created), ts(submitted), ts(delivered), ts(acknowledged),
                handoffId, id, handoffId, attempt);
    }

    /* ------------------------------------------------------------------ 证据文件 */

    private void evidence(String actor) {
        // 在库：光电抓拍图（PNG）挂事件 01。
        file("seed-vol-evidence-01", "EV-20260901-SEEDV001", "EO_STILL", "eo-still-h1-120m.png", "image/png", "AVAILABLE",
                PNG_1X1, PNG_1X1, at("2026-09-01T00:12:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-01-event", "seed-vol-evidence-01", "EVENT", EVENT_01, at("2026-09-01T00:15:00Z"));
        // 文件缺失：光电录像的元数据在、对象不在（录像没有可用的真实内容，只在无文件状态出现）。
        file("seed-vol-evidence-02", "EV-20260901-SEEDV002", "EO_VIDEO", "eo-video-h1-120m.mp4", "video/mp4", "MISSING",
                text("演示：该录像对象已不在存储中"), null, at("2026-09-01T00:12:30Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-02-event", "seed-vol-evidence-02", "EVENT", EVENT_01, at("2026-09-01T00:15:00Z"));
        // 在库：雷达轨迹快照（JSON）同时挂事件 02 与体量目标；附一条已解除的冻结。
        file("seed-vol-evidence-03", "EV-20260902-SEEDV003", "TRACK_SNAPSHOT", "track-snapshot-p1.json", "application/json", "AVAILABLE",
                text("{\"demo\":true,\"target_id\":\"seed-vol-target-vol-prohibited-1\",\"points\":[[118.3,37.3005,50],[118.3,37.3005,50]]}"),
                null, at("2026-09-02T02:21:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-03-event", "seed-vol-evidence-03", "EVENT", EVENT_02, at("2026-09-02T03:00:00Z"));
        link("seed-vol-evlink-03-target", "seed-vol-evidence-03", "TARGET", "seed-vol-target-vol-prohibited-1", at("2026-09-02T03:00:00Z"));
        hold("seed-vol-evhold-03-released", "seed-vol-evidence-03", EVENT_02, actor, "补证期间冻结，核实完成后解除",
                at("2026-09-02T03:05:00Z"), at("2026-09-02T06:50:00Z"));
        // 哈希不符：文件在，但库内 sha256 是另一份内容的哈希（入库后内容被改写）。
        file("seed-vol-evidence-04", "EV-20260902-SEEDV004", "EO_VIDEO", "eo-video-p1-crossing.mp4", "video/mp4", "CORRUPT",
                text("演示：入库时的原始内容"), text("演示：被改写后的内容，与库内哈希不符"), at("2026-09-02T02:22:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-04-event", "seed-vol-evidence-04", "EVENT", EVENT_02, at("2026-09-02T06:45:00Z"));
        // 在库 + 法律冻结：现场照片挂事件 08（阶段四范围），冻结未解除 → custody=HELD。
        file("seed-vol-evidence-05", "EV-20260907-SEEDV005", "SCENE_PHOTO", "scene-photo-shared-target.png", "image/png", "AVAILABLE",
                PNG_1X1, PNG_1X1, at("2026-09-07T05:25:00Z"), S4_ORG, S4_DISTRICT, null);
        link("seed-vol-evlink-05-event", "seed-vol-evidence-05", "EVENT", EVENT_08, at("2026-09-07T06:00:00Z"));
        hold("seed-vol-evhold-05-active", "seed-vol-evidence-05", EVENT_08, actor, "处罚案件办理中，法律冻结，不得清理", at("2026-09-07T06:45:00Z"), null);
        // 入库中：录像元数据已登记、对象尚未写入，没有大小与哈希。
        file("seed-vol-evidence-06", "EV-20260906-SEEDV006", "EO_VIDEO", "eo-video-plan-altitude.mp4", "video/mp4", "PENDING",
                null, null, at("2026-09-06T08:26:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-06-event", "seed-vol-evidence-06", "EVENT", EVENT_12, at("2026-09-06T09:00:00Z"));
        // 在库：处罚文书（文本占位）挂阶段 13/14 的案件事件。
        file("seed-vol-evidence-07", "EV-20260908-SEEDV007", "PENALTY_DOCUMENT", "penalty-decision-demo.txt", "text/plain", "AVAILABLE",
                text("演示：行政处罚决定书（占位文本，非正式文书）\n案件：CASE-20260908-9001\n事件：" + EVENT_S13 + "\n"),
                null, at("2026-09-08T03:30:00Z"), S4_ORG, S4_DISTRICT, null);
        link("seed-vol-evlink-07-event", "seed-vol-evidence-07", "EVENT", EVENT_S13, at("2026-09-08T03:30:00Z"));
        // 已销毁：现场照片（1 年留存）2024 年取证、到期后人工销毁，只留元数据与销毁留痕；挂阶段四共享目标。
        file("seed-vol-evidence-08", "EV-20240601-SEEDV008", "SCENE_PHOTO", "scene-photo-2024-destroyed.png", "image/png", "DESTROYED",
                PNG_1X1, null, at("2024-06-01T02:00:00Z"), S4_ORG, S4_DISTRICT,
                new Destroy(at("2026-09-01T01:00:00Z"), actor, "留存期届满，按演示制度人工销毁", "DEMO-DESTROY-0001"));
        link("seed-vol-evlink-08-target", "seed-vol-evidence-08", "TARGET", "seed-stage4-target-shared", at("2024-06-01T02:10:00Z"));
        // 在库：通报单回执（文本）挂体量计划（风险 09 所在计划）。
        file("seed-vol-evidence-09", "EV-20260905-SEEDV009", "NOTICE_RECEIPT", "notice-receipt-r09.txt", "text/plain", "AVAILABLE",
                text("演示：风险通知回执（占位）\n风险：seed-vol-risk-09\n接收方：民航监管部门（本地演示接收方）\n"),
                null, at("2026-09-05T03:40:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-09-plan", "seed-vol-evidence-09", "PLAN", "seed-vol-plan-vol-prohibited-1", at("2026-09-05T03:40:00Z"));
        // 在库：指令报文与回执（文本日志）挂事件 02 的人工处置。
        file("seed-vol-evidence-10", "EV-20260902-SEEDV010", "COMMAND_LOG", "manual-disposal-e02.log", "text/plain", "AVAILABLE",
                text("2026-09-02T07:02:00Z REQUEST  seed-vol-auth-e02 COUNTERMEASURE MANUAL\n"
                        + "2026-09-02T07:03:00Z APPROVE  seed-vol-auth-e02\n2026-09-02T07:15:00Z MANUAL_RESULT MANUAL_SUCCEEDED\n"),
                null, at("2026-09-02T07:15:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-10-event", "seed-vol-evidence-10", "EVENT", EVENT_02, at("2026-09-02T07:20:00Z"));
        // 在库：调测报告（90 天留存）取证于 2026-06-27 → 2026-09-25 到期，演示期内为"临近到期"；有可用设备时挂设备。
        DeviceScope device = deviceScope();
        file("seed-vol-evidence-11", "EV-20260627-SEEDV011", "COMMISSION_REPORT", "commission-report-demo.txt", "text/plain", "AVAILABLE",
                text("演示：设备调测报告（占位）\n设备：" + (device == null ? "未关联" : device.deviceId()) + "\n"), null,
                at("2026-06-27T01:00:00Z"), device == null ? S7_ORG : device.ownerOrgId(), device == null ? S7_DISTRICT : device.districtId(), null);
        if (device != null) link("seed-vol-evlink-11-device", "seed-vol-evidence-11", "DEVICE", device.deviceId(), at("2026-06-27T01:00:00Z"));
        // 在库 + 已到期：光电抓拍图 2023 年取证，3 年留存已届满、未冻结 → custody=DUE，可演示人工销毁闸门。
        file("seed-vol-evidence-12", "EV-20230501-SEEDV012", "EO_STILL", "eo-still-2023-due.png", "image/png", "AVAILABLE",
                PNG_1X1, PNG_1X1, at("2023-05-01T02:00:00Z"), S7_ORG, S7_DISTRICT, null);
        link("seed-vol-evlink-12-target", "seed-vol-evidence-12", "TARGET", "seed-vol-target-vol-legal-1", at("2023-05-01T02:10:00Z"));
    }

    private record Destroy(Instant at, String by, String reason, String approval) { }
    private record DeviceScope(String deviceId, String ownerOrgId, String districtId) { }

    /**
     * 一行证据元数据。
     * @param recorded  库内 size/sha256 所描述的内容（null → 没有哈希：PENDING）
     * @param onDisk    实际写进存储的内容（null → 不写文件：MISSING/PENDING/DESTROYED；与 recorded 不同 → CORRUPT）
     * 行已存在时整条跳过，也不再补写文件。
     */
    private void file(String id, String no, String kind, String name, String contentType, String status, byte[] recorded, byte[] onDisk,
            Instant captured, String org, String district, Destroy destroy) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_file WHERE evidence_id=? OR evidence_no=?", Integer.class, id, no);
        if (existing != null && existing > 0) return;
        String objectKey = captured.toString().substring(0, 10) + "/" + id + "/" + name;
        Instant stored = recorded == null ? null : captured.plusSeconds(20);
        Instant updated = destroy != null ? destroy.at() : stored != null ? stored : captured;
        Long size = recorded == null ? null : (long) recorded.length;
        String sha = recorded == null ? null : sha256(recorded);
        Instant retainUntil = EvidenceRetention.until(kind, captured, stored);
        if (onDisk != null && !storage.exists(objectKey)) storage.putNew(objectKey, new ByteArrayInputStream(onDisk));
        jdbc.update("INSERT INTO evidence_file (evidence_id,evidence_no,kind_code,original_name,content_type,storage_backend,object_key,"
                + "object_version,size_bytes,sha256,captured_at,stored_at,status,retain_until,source_mode,owner_org_id,district_id,"
                + "created_at,updated_at,version,destroyed_at,destroyed_by,destroy_reason,destroy_approval)"
                + " SELECT ?,?,?,?,?,'local',?,NULL,?,?,?,?,?,?,'mock',?,?,?,?,?,?,?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM app_org WHERE org_id=?) AND EXISTS (SELECT 1 FROM app_district WHERE district_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM evidence_file WHERE evidence_id=? OR evidence_no=? OR (storage_backend='local' AND object_key=?))",
                id, no, kind, name, contentType, objectKey, size, sha, ts(captured), ts(stored), status, ts(retainUntil), org, district,
                ts(captured), ts(updated), destroy != null ? 2 : recorded != null ? 1 : 0,
                destroy == null ? null : ts(destroy.at()), destroy == null ? null : destroy.by(),
                destroy == null ? null : destroy.reason(), destroy == null ? null : destroy.approval(),
                org, district, id, no, objectKey);
    }

    /** 关联只在证据与主体都存在时插；列按 subject_kind 落到对应外键列（与 EvidenceRepository.insertLink 同形）。 */
    private void link(String id, String evidenceId, String kind, String subjectId, Instant at) {
        String subjectTable = switch (kind) {
            case "EVENT" -> "uav_event WHERE event_id=?";
            case "TARGET" -> "target WHERE target_id=?";
            case "PLAN" -> "flight_plan WHERE plan_id=?";
            case "DEVICE" -> "ops_device WHERE device_id=?";
            default -> throw new IllegalArgumentException(kind);
        };
        String event = "EVENT".equals(kind) ? subjectId : null, device = "DEVICE".equals(kind) ? subjectId : null;
        String target = "TARGET".equals(kind) ? subjectId : null, plan = "PLAN".equals(kind) ? subjectId : null;
        jdbc.update("INSERT INTO evidence_link (link_id,evidence_id,subject_kind,subject_id,event_id,device_id,target_id,plan_id,command_id,"
                + "commission_id,created_at) SELECT ?,?,?,?,?,?,?,?,NULL,NULL,?"
                + " WHERE EXISTS (SELECT 1 FROM evidence_file WHERE evidence_id=?) AND EXISTS (SELECT 1 FROM " + subjectTable + ")"
                + " AND NOT EXISTS (SELECT 1 FROM evidence_link WHERE link_id=? OR (evidence_id=? AND subject_kind=? AND subject_id=?))",
                id, evidenceId, kind, subjectId, event, device, target, plan, ts(at),
                evidenceId, subjectId, id, evidenceId, kind, subjectId);
    }

    private void hold(String id, String evidenceId, String eventId, String actor, String reason, Instant at, Instant released) {
        jdbc.update("INSERT INTO evidence_hold (hold_id,evidence_id,event_id,held_by,reason,released_at,released_by,created_at)"
                + " SELECT ?,?,?,?,?,?,?,?"
                + " WHERE EXISTS (SELECT 1 FROM evidence_file WHERE evidence_id=?) AND EXISTS (SELECT 1 FROM uav_event WHERE event_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM evidence_hold WHERE hold_id=?)"
                + " AND NOT EXISTS (SELECT 1 FROM evidence_hold WHERE evidence_id=? AND released_at IS NULL)",
                id, evidenceId, eventId, actor, reason, ts(released), released == null ? null : actor, ts(at),
                evidenceId, eventId, id, evidenceId);
    }

    /** 取任意一台已启用且有业务范围的设备；没有就不挂设备（LocalDeviceSeeder 只在 test profile 存在）。 */
    private DeviceScope deviceScope() {
        return jdbc.query("SELECT d.device_id,s.owner_org_id,s.district_id FROM ops_device d"
                + " JOIN device_business_scope s ON s.ops_device_id=d.device_id WHERE d.enabled=TRUE ORDER BY d.device_id ASC FETCH FIRST 1 ROW ONLY",
                rs -> rs.next() ? new DeviceScope(rs.getString(1), rs.getString(2), rs.getString(3)) : null);
    }

    /* ------------------------------------------------------------------ 工具 */

    private String adminUserId() {
        return jdbc.query("SELECT user_id FROM app_user WHERE account='admin1'", rs -> rs.next() ? rs.getString(1) : null);
    }

    private String write(Object material) {
        try { return json.writeValueAsString(material); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize demo volume handoff snapshot", ex); }
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Instant at(String iso) { return Instant.parse(iso); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
