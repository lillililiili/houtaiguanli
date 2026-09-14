package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 风险列表的排序、筛选与导出（决策 15-6 / 15-7）。与告警同一套口径，两边不能各走各的。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskListSortExportApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String reader;

    @BeforeEach
    void setUp() {
        reader = user();
        risk("LOW", "AIRSPACE", Instant.parse("2026-09-08T01:00:00Z"));
        risk("HIGH", "ROUTE_DEVIATION", Instant.parse("2026-09-08T03:00:00Z"));
        risk("MEDIUM", "AIRSPACE", Instant.parse("2026-09-08T02:00:00Z"));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from flight_risk where risk_id like 'srt-risk-%'");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'srt-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'srt-%')");
        jdbc.update("delete from app_user where account like 'srt-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-SRT-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-SRT-%'");
        jdbc.update("delete from audit_log where action='risks_exported'");
    }

    @Test
    void sortingByOccurredAtRespectsDirection() throws Exception {
        List<String> asc = occurredTimes("sort=occurred_at&order=asc");
        List<String> desc = occurredTimes("sort=occurred_at&order=desc");
        assertThat(asc).isSorted();
        List<String> reversed = new ArrayList<>(asc);
        java.util.Collections.reverse(reversed);
        assertThat(desc).isEqualTo(reversed);
    }

    /**
     * 决策 15-30：四个排序键 × 两个方向全部走一遍。原先只请求过 received_at，
     * 所以"按等级排序"其实排的是枚举字符串（字典序 CRITICAL < HIGH < LOW < MEDIUM）一直没被发现——
     * 这类错不报任何异常，只是顺序看着没规律。白名单里放了什么键，就得每个都真的请求过。
     */
    @Test
    void everySortKeyWorksInBothDirections() throws Exception {
        risk("CRITICAL", "AIRSPACE", Instant.parse("2026-09-08T05:00:00Z"), "演示：等级排序", "EXCLUDED");
        // 三条种子记录状态相同，不铺开的话 state 那一轮全是并列、断言等于空转。
        jdbc.update("update flight_risk set state_code='NOTIFIED'"
                + " where risk_id in (select risk_id from flight_risk where risk_id like 'srt-risk-%'"
                + " and state_code='PENDING_VERIFICATION' order by risk_id fetch next 1 rows only)");
        jdbc.update("update flight_risk set state_code='PENDING_NOTIFICATION'"
                + " where risk_id in (select risk_id from flight_risk where risk_id like 'srt-risk-%'"
                + " and state_code='PENDING_VERIFICATION' order by risk_id fetch next 1 rows only)");
        for (String key : List.of("received_at", "occurred_at", "severity", "state")) {
            List<String> asc = sortedValues(key, "sort=" + key + "&order=asc");
            List<String> desc = sortedValues(key, "sort=" + key + "&order=desc");
            assertThat(asc).as("%s 升序", key).hasSizeGreaterThanOrEqualTo(4).isSorted();
            List<String> reversed = new ArrayList<>(asc);
            java.util.Collections.reverse(reversed);
            // 次序键相同的行靠 risk_id 兜底，且方向一致，所以降序必须恰是升序的倒序。
            assertThat(desc).as("%s 降序", key).isEqualTo(reversed);
            // 导出与列表共用同一个 orderBy，所以 sort=state 当时同样是 500——四个键都得走一遍。
            mvc.perform(get("/api/v1/risks/export.csv?sort=" + key + "&order=desc")
                            .header("Authorization", bearer(reader)))
                    .andExpect(status().isOk());
        }

        // 上面的循环用等级序号断言，够严但要读者自己去追映射。这里直接钉死字面次序：
        // 旧的字典序写法同样返回 200，也同样"有序"——只有写出期望的那一串才能一眼看出排的是什么。
        assertThat(rawValues("severity", "sort=severity&order=desc"))
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW");
        assertThat(rawValues("state", "sort=state&order=desc"))
                .containsExactly("PENDING_VERIFICATION", "PENDING_NOTIFICATION", "NOTIFIED", "EXCLUDED");
    }

    /** 某个字段在本用例数据上的原始取值，按接口返回的次序。 */
    private List<String> rawValues(String field, String query) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("risk_id").asText().startsWith("srt-risk-")) {
                values.add(item.path(field).asText());
            }
        }
        return values;
    }

    private List<String> sortedValues(String key, String query) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("risk_id").asText().startsWith("srt-risk-")) values.add(sortable(key, item));
        }
        return values;
    }

    /** 把一条记录在该排序键上的取值翻成"可按字典序比较"的字符串，好把四个键放进同一个循环里断言。 */
    private static String sortable(String key, JsonNode item) {
        return switch (key) {
            case "received_at", "occurred_at" -> String.format("%020d", item.path(key).asLong());
            case "severity" -> switch (item.path("severity").asText()) {
                case "CRITICAL" -> "4"; case "HIGH" -> "3"; case "MEDIUM" -> "2"; case "LOW" -> "1"; default -> "0";
            };
            default -> item.path("state").asText();
        };
    }

    @Test
    void illegalSortOrOrderIsRejectedRatherThanSilentlyIgnored() throws Exception {
        // 悄悄回落到默认次序会让调用方以为自己排好了序，拿到的却是另一种顺序——这种错在页面上很难看出来。
        mvc.perform(get("/api/v1/risks?sort=alarm_id").header("Authorization", bearer(reader)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/risks?sort=received_at&order=sideways").header("Authorization", bearer(reader)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void riskTypeFilterMatchesTheListCount() throws Exception {
        JsonNode all = page("");
        JsonNode filtered = page("risk_type=AIRSPACE");
        assertThat(filtered.path("total").asLong()).isLessThan(all.path("total").asLong());
        for (JsonNode item : filtered.path("items")) {
            assertThat(item.path("risk_type").asText()).isEqualTo("AIRSPACE");
        }
        // total 与实际返回的条数必须来自同一套筛选，否则分页会在最后一页对不上。
        assertThat(filtered.path("items").size()).isEqualTo((int) filtered.path("total").asLong());
    }

    @Test
    void exportCarriesBomChineseHeadersAndTheSameOrder() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/risks/export.csv?sort=occurred_at&order=asc")
                        .header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        // 没有 BOM 的话 Excel 会按本地代码页解释 UTF-8，中文列头直接乱码——这是导出最常见的投诉。
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(text.lines().findFirst().orElseThrow()).startsWith("风险编号,风险类型,等级,状态");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("risks-").contains(".csv");
        // 导出的次序必须与列表一致：两处不同的话，人对着页面核对导出表会核不上。
        List<String> exported = new ArrayList<>();
        text.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> exported.add(l.split(",")[5]));
        assertThat(exported).isSorted();
    }

    /**
     * 决策 15-26：事由是自由文本，一线人员能写进去什么就能被导出。以 = 开头的一格会被表格软件
     * 当公式执行——导出侧加 ' 前缀让它读成文本。
     */
    @Test
    void exportPrefixesFormulaLikeCellsSoSpreadsheetsTreatThemAsText() throws Exception {
        risk("LOW", "AIRSPACE", Instant.parse("2026-09-08T04:00:00Z"), "=cmd()");
        MvcResult result = mvc.perform(get("/api/v1/risks/export.csv").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        // 事由是第 5 列（风险编号,风险类型,等级,状态,事由,...）。
        List<String> reasons = new ArrayList<>();
        text.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> reasons.add(l.split(",")[4]));
        assertThat(reasons).contains("'=cmd()");
        assertThat(reasons).noneMatch(cell -> !cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0);
    }

    /**
     * 决策 15-32：列头是中文、正文是 HIGH / PENDING_VERIFICATION 的话，拿到的是半中半英的表。
     * 注意风险状态说"待核验"而告警说"待核实"——同一个码、两套业务用语，页面上一直如此，不要去"统一"。
     */
    @Test
    void exportTranslatesEnumColumnsToChinese() throws Exception {
        List<String[]> rows = exportRows();
        assertThat(rows).as("本用例的导出行").hasSize(3);
        assertThat(rows.stream().map(r -> r[2]).toList()).containsExactlyInAnyOrder("低", "高", "中");
        assertThat(rows.stream().map(r -> r[3]).toList()).containsOnly("待核验");
        assertThat(rows.stream().map(r -> r[1]).toList()).contains("空域风险");
    }

    /** 导出正文里属于本用例的那几行，按列拆开。 */
    private List<String[]> exportRows() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/risks/export.csv").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String[]> rows = new ArrayList<>();
        new String(body, 3, body.length - 3, StandardCharsets.UTF_8).lines().skip(1)
                .filter(l -> l.startsWith("风险-排序-")).forEach(l -> rows.add(l.split(",")));
        return rows;
    }

    @Test
    void exportIsAudited() throws Exception {
        mvc.perform(get("/api/v1/risks/export.csv?risk_type=AIRSPACE")
                .header("Authorization", bearer(reader))).andExpect(status().isOk());
        // 事后要能回答"这份表是谁、按什么条件导出去的"。
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='risks_exported'"
                + " and detail like '%risk_type=AIRSPACE%' and detail like '%rows=%'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exportRequiresTheSamePermissionAsTheList() throws Exception {
        String outsider = userWithout();
        mvc.perform(get("/api/v1/risks/export.csv").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    void districtOptionsUseTheListPermissionNotSystemAdministration() throws Exception {
        // 决策 15-22：页面原来拉系统管理的 /districts，那要 users.read——业务角色没有，筛选框就是空的：
        // 功能在，但只有管理员用得了。这里的清单跟列表同一权限、同一范围。
        JsonNode districts = json.readTree(mvc.perform(get("/api/v1/risks/districts")
                        .header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(districts).isNotEmpty();
        boolean seen = false;
        for (JsonNode d : districts) {
            assertThat(d.path("district_id").asText()).isNotBlank();
            // 名称要能上屏；只给 id 的话筛选框里是一串 UUID。
            assertThat(d.path("name").asText()).isNotBlank();
            if (DISTRICT.equals(d.path("district_id").asText())) seen = true;
        }
        // 清单必须与他真能看到的数据同口径，否则会出现"选了就是空结果"的区域。
        assertThat(seen).as("本用例造的数据所在区域应出现在清单里").isTrue();
    }

    @Test
    void districtOptionsRequireTheListPermission() throws Exception {
        mvc.perform(get("/api/v1/risks/districts").header("Authorization", bearer(userWithout())))
                .andExpect(status().isForbidden());
    }

    private List<String> occurredTimes(String query) throws Exception {
        List<String> times = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("risk_id").asText().startsWith("srt-risk-")) times.add(item.path("occurred_at").asText());
        }
        return times;
    }

    private JsonNode page(String query) throws Exception {
        String url = "/api/v1/risks?size=100" + (query.isBlank() ? "" : "&" + query);
        return json.readTree(mvc.perform(get(url).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void risk(String severity, String type, Instant occurredAt) {
        risk(severity, type, occurredAt, "演示：排序用例");
    }

    private void risk(String severity, String type, Instant occurredAt, String reasonText) {
        risk(severity, type, occurredAt, reasonText, "PENDING_VERIFICATION");
    }

    private void risk(String severity, String type, Instant occurredAt, String reasonText, String stateCode) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(occurredAt);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,"
                + "updated_at,version) select 'srt-src','SRT-TEST','排序测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='srt-src')", at, at);
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,"
                + "severity,state_code,reason_code,reason_text,occurred_at,received_at,source_mode,owner_org_id,"
                + "district_id,created_at,updated_at,version)"
                + " values (?,'srt-src',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,?,?,"
                + "'PROHIBITED_AIRSPACE_OVERLAP',?,?,?,'mock',?,?,?,?,0)",
                "srt-risk-" + suffix, "风险-排序-" + suffix, type, severity, stateCode, reasonText,
                at, at, ORG, DISTRICT, at, at);
    }

    private String user() { return user(true); }
    private String userWithout() { return user(false); }

    private String user(boolean alarmRead) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-SRT-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,"
                + "system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        if (alarmRead) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,"
                    + "created_at) values (?, 'risk:read','READ',false,current_timestamp)", role);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "srt-" + suffix, "排序读者", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
