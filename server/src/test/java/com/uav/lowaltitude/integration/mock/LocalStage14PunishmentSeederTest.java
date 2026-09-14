package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 阶段 14 种子：处罚页各块都有数据可看；重跑幂等；阶段 13 种子缺失时跳过而不是崩。 */
@SpringBootTest
@ActiveProfiles("test")
class LocalStage14PunishmentSeederTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage14PunishmentSeeder seeder;

    @Test
    void seedsHandoffCaseDiscretionAndLead() {
        assertThat(count("handoff_recipient", "recipient_id", LocalStage14PunishmentSeeder.RECIPIENT)).isEqualTo(1);
        assertThat(count("handoff", "handoff_id", LocalStage14PunishmentSeeder.HANDOFF)).isEqualTo(1);
        assertThat(count("punishment_case", "case_id", LocalStage14PunishmentSeeder.CASE_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from punishment_case where case_id=?", String.class,
                LocalStage14PunishmentSeeder.CASE_ID)).isEqualTo("INVESTIGATING");
        // 处罚页的"罚款与裁量"和"待补线索"两块都要有东西，否则打开就是空的。
        assertThat(jdbc.queryForObject("select status from penalty_discretion where discretion_id=?", String.class,
                LocalStage14PunishmentSeeder.DISCRETION)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select resolved from punishment_case_lead where lead_id=?", Boolean.class,
                LocalStage14PunishmentSeeder.LEAD)).isFalse();
    }

    @Test
    void seededHandoffSourceModeMatchesItsAlarm() {
        // 决策 14-22：交接的 source_mode 与告警同源。种子直插绕过服务层，写死 'live' 会让
        // 一条 mock 演示数据在库里冒充真实来源——而且因为服务路径是对的，这种不一致更难被发现。
        String handoffMode = jdbc.queryForObject("select source_mode from handoff where handoff_id=?", String.class,
                LocalStage14PunishmentSeeder.HANDOFF);
        String alarmMode = jdbc.queryForObject("select a.source_mode from handoff h"
                + " join uav_event e on e.event_id=h.event_id join alarm a on a.alarm_id=e.alarm_id"
                + " where h.handoff_id=?", String.class, LocalStage14PunishmentSeeder.HANDOFF);
        assertThat(handoffMode).isEqualTo(alarmMode);
        // 演示夹具不该以 live 示人。
        assertThat(handoffMode).isNotEqualTo("live");
    }

    @Test
    void seededNamesAreDisplayNamesNotAccounts() {
        // 决策 14-27：卷宗里的署名要是姓名，不是登录名。种子直插同样要守这条——
        // 服务路径改对了、种子绕过它，库里看到的就是账号，而看代码只会看到已经改对的那条路径。
        String expected = jdbc.queryForObject("select name from app_user where account='admin1'", String.class);
        String officerName = jdbc.queryForObject("select officer_name from punishment_case where case_id=?",
                String.class, LocalStage14PunishmentSeeder.CASE_ID);
        String filedByName = jdbc.queryForObject("select filed_by_name from punishment_case where case_id=?",
                String.class, LocalStage14PunishmentSeeder.CASE_ID);
        assertThat(officerName).isEqualTo(expected).isNotEqualTo("admin1");
        assertThat(filedByName).isEqualTo(expected).isNotEqualTo("admin1");
        assertThat(jdbc.queryForObject("select actor_name from punishment_case_event where event_id='seed-s14-ev-file'",
                String.class)).isEqualTo(expected);
    }

    @Test
    void snapshotIsSchemaVersionTwo() {
        // 处罚交接必须是事件形状（决策 14-1），否则处罚页读到的是风险材料。
        assertThat(jdbc.queryForObject("select schema_version from handoff_material_snapshot where handoff_id=?",
                Integer.class, LocalStage14PunishmentSeeder.HANDOFF)).isEqualTo(2);
    }

    @Test
    void snapshotIsAssembledFromRealFactsNotAHandWrittenShell() throws Exception {
        // 决策 14-28：种子走服务层同一套组装器。空 disposals/verifications 的壳子页面照样渲染，
        // 但"事实清楚、证据充分"根本没被证明过——而且服务路径是对的，看代码只会看到对的那条。
        com.fasterxml.jackson.databind.JsonNode material = snapshot();
        assertThat(material.path("event").path("alarm_type").asText()).isNotBlank();
        assertThat(material.path("event").path("source_alarm_id").asText()).isNotBlank();
        assertThat(material.path("verifications")).isNotEmpty();
        assertThat(material.path("disposals")).isNotEmpty();
        boolean completed = false;
        for (com.fasterxml.jackson.databind.JsonNode d : material.path("disposals")) {
            if ("COMPLETED".equals(d.path("status").asText())) completed = true;
        }
        // 处罚交接的前提就是"确实处置过"；这一条不成立，整份材料的意义就没了。
        assertThat(completed).as("快照里至少要有一条已完成的处置授权").isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode snapshot() throws Exception {
        String raw = jdbc.queryForObject("select snapshot from handoff_material_snapshot where handoff_id=?",
                String.class, LocalStage14PunishmentSeeder.HANDOFF);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(raw);
        // H2 把 CAST(? AS JSON) 的字符串包成 JSON 文本，PostgreSQL 直接存对象。
        if (node != null && node.isTextual()) node = mapper.readTree(node.textValue());
        return node;
    }

    @Test
    void doesNotFabricateDecisionDocumentsOrReviews() {
        // 决策 14-16：不造决定书与复核——正面路径留给浏览器真的走一遍，种子替它走完就验不出问题了。
        assertThat(jdbc.queryForObject("select count(*) from penalty_decision_document where case_id=?", Long.class,
                LocalStage14PunishmentSeeder.CASE_ID)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from punishment_review where case_id=?", Long.class,
                LocalStage14PunishmentSeeder.CASE_ID)).isZero();
    }

    @Test
    void rerunIsIdempotent() {
        long before = jdbc.queryForObject("select count(*) from punishment_case where case_id like 'seed-stage14-%'",
                Long.class);
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where case_id like 'seed-stage14-%'",
                Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from penalty_discretion where discretion_id=?", Long.class,
                LocalStage14PunishmentSeeder.DISCRETION)).isEqualTo(1);
    }

    @Test
    void skipsInsteadOfCrashingWhenStage13EventIsMissing() {
        // 种子一炸整个 Spring 上下文都起不来，表现是所有用例全红——所以缺前置数据必须跳过而不是抛。
        jdbc.update("delete from punishment_case_lead where case_id=?", LocalStage14PunishmentSeeder.CASE_ID);
        jdbc.update("delete from penalty_discretion where case_id=?", LocalStage14PunishmentSeeder.CASE_ID);
        jdbc.update("delete from punishment_case_event where case_id=?", LocalStage14PunishmentSeeder.CASE_ID);
        jdbc.update("delete from punishment_case where case_id=?", LocalStage14PunishmentSeeder.CASE_ID);
        String eventId = LocalStage13DisposalSeeder.EVENT;
        String alarmId = jdbc.queryForObject("select alarm_id from uav_event where event_id=?", String.class, eventId);
        jdbc.update("delete from handoff_delivery where handoff_id=?", LocalStage14PunishmentSeeder.HANDOFF);
        jdbc.update("delete from handoff_material_snapshot where handoff_id=?", LocalStage14PunishmentSeeder.HANDOFF);
        jdbc.update("delete from handoff where handoff_id=?", LocalStage14PunishmentSeeder.HANDOFF);
        // 只删挡在 uav_event 前面的引用。disposal_authorization.subject_id **没有** FK 到 uav_event，
        // 不需要删；删了会把阶段 13 的种子夹具一起端掉，害它的用例莫名其妙地红。
        // 核实历史有 FK，必须先删——本用例的 finally 会重跑种子把它补回来。
        jdbc.update("delete from uav_event_verification where event_id=?", eventId);
        jdbc.update("delete from uav_event where event_id=?", eventId);
        try {
            assertThatCode(() -> seeder.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
            assertThat(count("punishment_case", "case_id", LocalStage14PunishmentSeeder.CASE_ID)).isZero();
        } finally {
            // 把前置事件放回去，再重跑一次种子——本用例是破坏性的，不还原会让同类里其他用例
            // 因为"夹具不见了"而红，而那种红查起来会一路查到产品代码上去。
            jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,"
                    + "updated_at,version) values (?,?,'CONFIRMED','seed-stage4-alarm-org','seed-stage4-alarm-district',"
                    + "current_timestamp,current_timestamp,1)", eventId, alarmId);
            seeder.run(new DefaultApplicationArguments());
        }
    }

    private long count(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, value);
    }
}
