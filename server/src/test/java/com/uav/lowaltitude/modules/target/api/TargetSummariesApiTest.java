package com.uav.lowaltitude.modules.target.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 目标悬浮卡的三段摘要（决策 15-4）。
 * 重点是"有就给、没有就整段省略"——空对象在页面上会渲染成一行没有内容的标题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TargetSummariesApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String reader;

    @BeforeEach
    void setUp() { reader = user(); }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from disposal_authorization_event where authorization_id like 'sum-auth-%'");
        jdbc.update("delete from disposal_authorization where authorization_id like 'sum-auth-%'");
        jdbc.update("delete from flight_risk where risk_id like 'sum-risk-%'");
        jdbc.update("delete from rule_evaluation where evaluation_id like 'sum-eval-%'");
        jdbc.update("delete from rule_run where run_id like 'sum-run-%'");
        jdbc.update("delete from source_observation where source_id='sum-aoa-src'");
        jdbc.update("delete from target_track_status where target_id like 'sum-target-%'");
        jdbc.update("delete from target_source_link where target_id like 'sum-target-%'");
        jdbc.update("delete from target_latest_state where target_id like 'sum-target-%'");
        jdbc.update("delete from target where target_id like 'sum-target-%'");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'sum-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'sum-%')");
        jdbc.update("delete from app_user where account like 'sum-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-SUM-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-SUM-%'");
    }

    @Test
    void targetWithoutAnySummaryOmitsAllThreeKeys() throws Exception {
        String targetId = target();
        JsonNode detail = detail(targetId);
        // 空对象会在页面上渲染成一行没有内容的标题；缺就整段不给。
        assertThat(detail.has("risk_summary")).isFalse();
        assertThat(detail.has("legality_summary")).isFalse();
        assertThat(detail.has("disposal_summary")).isFalse();
    }

    @Test
    void detailAndListCarryTheSameThreeSummaries() throws Exception {
        String targetId = target();
        risk(targetId, "HIGH", "PENDING_VERIFICATION");
        legality(targetId, "ILLEGAL", "MEDIUM");
        disposal(targetId, "COMPLETED");

        JsonNode detail = detail(targetId);
        assertSummaries(detail);
        // 列表与详情同形（决策 15-4）：两条路各写一份就迟早长出差异，而那种差异只有对着页面看才发现。
        JsonNode listed = null;
        for (JsonNode item : list().path("items")) {
            if (targetId.equals(item.path("target_id").asText())) listed = item;
        }
        assertThat(listed).isNotNull();
        assertSummaries(listed);
    }

    @Test
    void onlyTheLatestOfEachKindIsReported() throws Exception {
        String targetId = target();
        risk(targetId, "LOW", "EXCLUDED", Instant.parse("2026-09-08T01:00:00Z"));
        risk(targetId, "HIGH", "PENDING_VERIFICATION", Instant.parse("2026-09-08T05:00:00Z"));
        JsonNode detail = detail(targetId);
        // 悬浮卡回答的是"现在怎么样"，不是历史。给最早那条会让处置人员按已经排除的风险去判断。
        assertThat(detail.path("risk_summary").path("severity").asText()).isEqualTo("HIGH");
        assertThat(detail.path("risk_summary").path("state").asText()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void latestRiskIsStableWhenOccurredAtIsNull() throws Exception {
        String targetId = target();
        // occurred_at 可空。原实现用 (occurred_at, risk_id) 做元组比较，只要一侧是 NULL 整个比较就返回 NULL
        // 而不是真——两行都满足 NOT EXISTS，两条都被写进结果，最终留下哪条取决于结果集顺序，
        // 悬浮卡上的风险等级会在刷新之间跳变。改用 COALESCE(occurred_at, received_at) 之后必须稳定取到较新的那条。
        // 先插较新的、后插较旧的：错误实现下两行都满足 NOT EXISTS，结果按结果集顺序"后者覆盖前者"，
        // 于是留下的是**较旧**的那条。顺序反过来的话错误实现会碰巧选对，用例就白写了——
        // 我第一版正是这么写的，回退 COALESCE 后它照样绿。
        riskWithoutOccurredAt(targetId, "HIGH", Instant.parse("2026-09-08T05:00:00Z"));
        riskWithoutOccurredAt(targetId, "LOW", Instant.parse("2026-09-08T01:00:00Z"));
        assertThat(detail(targetId).path("risk_summary").path("severity").asText()).isEqualTo("HIGH");
        // 更根本的一条：谓词应当**只**选出一行。两行都匹配才是这个缺陷的本体，
        // "谁最终胜出"只是它的表象，会随结果集顺序变化。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk r where r.target_id=?"
                + " and not exists (select 1 from flight_risk n where n.target_id=r.target_id"
                + "   and (coalesce(n.occurred_at, n.received_at), n.risk_id)"
                + "     > (coalesce(r.occurred_at, r.received_at), r.risk_id))", Long.class, targetId))
                .as("最新一条的谓词只应命中一行").isEqualTo(1L);
    }

    @Test
    void bearingIsOmittedWhenTheTargetItselfHasAPosition() throws Exception {
        String targetId = target();
        bearingObservation(targetId);
        located(targetId);
        // 有位置就画点；再给方位线会让同一个目标同时出现一个点和一条方向线，读图的人不知道信哪个。
        assertThat(detail(targetId).path("latest_state").has("bearing_deg")).isFalse();
    }

    @Test
    void bearingIsGivenWhenOnlyThePilotIsLocated() throws Exception {
        String targetId = target();
        bearingObservation(targetId);
        // 只测到飞手、目标本身未定位——这**正是**需要方位线的场景，不能因为 pilot_location 非空就把它抑制掉。
        jdbc.update("update target_latest_state set pilot_location=?, location=null where target_id=?",
                point(118.5, 37.4), targetId);
        JsonNode state = detail(targetId).path("latest_state");
        assertThat(state.has("pilot_location")).isTrue();
        assertThat(state.path("bearing_deg").decimalValue()).isNotNull();
        assertThat(state.path("bearing_device_id").asText()).isNotBlank();
    }

    /**
     * 决策 16-6：被合并的目标不该再出现在列表里。
     *
     * 合并本身早就落库了（血缘、别名、`track_status=MERGE` 都对），但 `GET /targets` 从不看
     * `target_track_status`，于是用户在态势页上看到的还是"同一架出现两次"——功能在库里闭合了，
     * 在屏幕上没有。被并者按定义就是某个存活目标的别名，列表里不该有它自己的一行。
     */
    @Test
    void mergedTargetsAreHiddenFromTheDefaultListButCanBeAskedFor() throws Exception {
        String survivor = target(), merged = target();
        mergeInto(merged);

        List<String> defaultIds = idsOf("/api/v1/targets?size=100");
        assertThat(defaultIds).contains(survivor);
        assertThat(defaultIds).as("被并者不该出现在默认列表").doesNotContain(merged);

        // 详情不变：告警、事件、风险里存的是旧 id，历史数据必须还能打开。
        mvc.perform(get("/api/v1/targets/{id}", merged).header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        // 要查也查得到，只是得明说。
        assertThat(idsOf("/api/v1/targets?size=100&include_merged=true"))
                .as("显式要求时应当带上").contains(merged, survivor);
    }

    private List<String> idsOf(String url) throws Exception {
        List<String> ids = new ArrayList<>();
        data(mvc.perform(get(url).header("Authorization", bearer(reader))).andExpect(status().isOk()))
                .path("items").forEach(item -> ids.add(item.path("target_id").asText()));
        return ids;
    }

    /**
     * 把 merged 标成已被合并。规则只看 `target_track_status`，所以夹具也只写这一行——
     * 别名与血缘是合并的其他产物，与"列表该不该显示"无关，写进来只会让这条用例依赖更多东西。
     */
    private void mergeInto(String merged) {
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T06:00:00Z"));
        jdbc.update("insert into target_track_status (target_id,status,since,updated_at,version)"
                + " values (?,'MERGE',?,?,0)", merged, at, at);
    }

    private void assertSummaries(JsonNode node) {
        assertThat(node.path("risk_summary").path("severity").asText()).isEqualTo("HIGH");
        // legal_status 与 grade 是**英文枚举**（LEGAL/ILLEGAL/…、HIGH/MEDIUM/LOW），不是中文。
        // 接口原样透出码，中文由页面的共享字典翻译——后端替它翻会让同一个码在不同页面长出两种说法。
        assertThat(node.path("legality_summary").path("legal_status").asText()).isEqualTo("ILLEGAL");
        assertThat(node.path("legality_summary").path("grade").asText()).isEqualTo("MEDIUM");
        assertThat(node.path("legality_summary").path("violation_reasons").get(0).asText())
                .isEqualTo("PROHIBITED_AIRSPACE_OVERLAP");
        assertThat(node.path("disposal_summary").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(node.path("disposal_summary").path("authorization_no").asText()).isNotBlank();
    }

    private JsonNode detail(String targetId) throws Exception {
        return data(mvc.perform(get("/api/v1/targets/{id}", targetId).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()));
    }

    private JsonNode list() throws Exception {
        return data(mvc.perform(get("/api/v1/targets?size=100").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()));
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    private String target() {
        String id = "sum-target-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T05:00:00Z"));
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,"
                + "updated_at,version) values (?,?,'mock',?,?,?,?,0)", id, "目标-摘要-" + id.substring(11), ORG, DISTRICT, at, at);
        jdbc.update("insert into target_latest_state (target_id,observed_at,received_at,created_at,updated_at)"
                + " values (?,?,?,?,?)", id, at, at, at, at);
        return id;
    }

    private void risk(String targetId, String severity, String state) {
        risk(targetId, severity, state, Instant.now());
    }

    private void risk(String targetId, String severity, String state, Instant occurredAt) {
        String id = "sum-risk-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(occurredAt);
        // flight_risk 的必填面比看上去宽：计划、航线版本、来源、事由都不能空。
        // 复用阶段 3 的种子计划与航线，而不是再造一套——夹具越自成体系，越容易和真实形状脱节。
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,"
                + "updated_at,version) select 'sum-src','SUM-TEST','摘要测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='sum-src')", at, at);
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,"
                + "risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,source_mode,"
                + "owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,'sum-src',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE',?,?,"
                + "'PROHIBITED_AIRSPACE_OVERLAP','演示：进入禁飞空域',?,?,'mock',?,?,?,?,0)",
                id, id, targetId, severity, state, at, at, ORG, DISTRICT, at, at);
    }

    /** occurred_at 为 NULL、只有 received_at 的风险——真实数据里确实存在（该列可空）。 */
    private void riskWithoutOccurredAt(String targetId, String severity, Instant receivedAt) {
        String id = "sum-risk-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(receivedAt);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,"
                + "updated_at,version) select 'sum-src','SUM-TEST','摘要测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='sum-src')", at, at);
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,"
                + "risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,source_mode,"
                + "owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,'sum-src',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE',?,"
                + "'PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','演示：无发生时间',NULL,?,'mock',?,?,?,?,0)",
                id, id, targetId, severity, at, ORG, DISTRICT, at, at);
    }

    private void legality(String targetId, String status, String grade) {
        String id = "sum-eval-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.now());
        // rule_evaluation 的必填面很宽（run_id / rule_set_version_id 两个 FK 加十来个非空列）。
        // 与其伪造一整套规则引擎夹具，不如**复用种子里已有那条的骨架**——照抄的是不影响本用例的支撑字段，
        // 真正被断言的 legal_status / grade / violation_reasons 仍由用例自己给。
        // 测试库里没有任何规则运行（引擎种子不在 test 档跑），所以先造一条最小的 run 挂上去。
        // 用真实的 rule_set_version（库里有 3 个），不自己编一个版本号——那会让研判摘要指向一个不存在的规则集。
        String runId = "sum-run-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,"
                + "status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at)"
                + " select ?,v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',1,1,0,0,'mock',?"
                + " from rule_set_version v order by v.rule_set_version_id asc limit 1", runId, at, at, at);
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,"
                + "target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,"
                + "hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,"
                + "source_mode,created_at)"
                + " select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,"
                + "'FRESH','FULL',?,60,?,CAST(? AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),"
                + "CAST('{}' AS JSON),?,?,'mock',?"
                + " from rule_run r where r.run_id=?",
                id, targetId, status, grade, "[\"PROHIBITED_AIRSPACE_OVERLAP\"]", ORG, DISTRICT, at, runId);
    }

    private void disposal(String targetId, String status) {
        String id = "sum-auth-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.now());
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,"
                + "subject_id,target_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,"
                + "district_id,source_mode,version,created_at,updated_at) values (?,?,'DISPERSAL','TARGET',?,?,"
                + "'MANUAL','摘要用例',?,?,?,'demo-v1',?,?,'mock',0,?,?)",
                id, "AUTH-20260908-" + (6000 + (int) (Math.random() * 999)), targetId, targetId, admin, at, status,
                ORG, DISTRICT, at, at);
    }

    /** 一条无位置、带 quality.bearing_deg 的观测，并把它链到目标上。 */
    private void bearingObservation(String targetId) {
        Timestamp at = Timestamp.from(Instant.now());
        String external = "ext-" + targetId.substring(11);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,"
                + "created_at,updated_at,version) select 'sum-aoa-src','SUM-AOA','方位测试来源',true,'mock','AOA',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='sum-aoa-src')", at, at);
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,"
                + "external_target_id,created_at) values (?,?,'sum-aoa-src','sum-session',?,?)",
                UUID.randomUUID().toString(), targetId, external, at);
        jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,"
                + "external_target_id,observed_at,received_at,location,identity_confidence,device_id,quality,"
                + "source_mode,created_at) values (?,'sum-aoa-src','AOA','sum-session',?,?,?,null,0.80000,"
                + "?,CAST(? AS JSON),'mock',?)",
                UUID.randomUUID().toString(), external, at, at, anyDevice(), "{\"bearing_deg\":123.5}", at);
    }

    /** device_id 有指向 device 表的外键，不能编一个：用库里已有的设备，否则插入直接撞 23503。 */
    private String anyDevice() {
        return jdbc.query("select device_id from device order by device_id asc limit 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    private void located(String targetId) {
        jdbc.update("update target_latest_state set location=? where target_id=?", point(118.4, 37.3), targetId);
    }

    /** H2 直接把 WKT 字符串转成 GEOMETRY；ST_GeomFromText 是 PostGIS 的函数，测试库没有。 */
    private Object point(double lon, double lat) {
        return "SRID=4326;POINT(" + lon + " " + lat + ")";
    }

    private String user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-SUM-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,"
                + "system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,"
                + "created_at) values (?, 'target:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "sum-" + suffix, "摘要读者", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
