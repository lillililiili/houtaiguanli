package com.uav.lowaltitude.modules.alarm.api;

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

/** 告警列表的排序、筛选与导出（决策 15-6 / 15-7）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlarmListSortExportApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String reader;

    @BeforeEach
    void setUp() {
        reader = user();
        alarm("LOW", "UAV_INTRUSION", Instant.parse("2026-09-08T01:00:00Z"));
        alarm("HIGH", "BIRD_FLOCK", Instant.parse("2026-09-08T03:00:00Z"));
        alarm("MEDIUM", "UAV_INTRUSION", Instant.parse("2026-09-08T02:00:00Z"));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from uav_event where alarm_id like 'srt-alarm-%'");
        jdbc.update("delete from alarm where alarm_id like 'srt-alarm-%'");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'srt-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'srt-%')");
        jdbc.update("delete from app_user where account like 'srt-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-SRT-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-SRT-%'");
        jdbc.update("delete from audit_log where action='alarms_exported'");
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
     * 决策 15-30：四个排序键 × 两个方向全部走一遍。
     *
     * 原先只请求过 received_at，所以两个缺陷一直没人碰到：`sort=state` 输出 `ORDER BY a.state`，
     * 而 alarm 表没有 state 列（状态在 LEFT JOIN 的 uav_event 上），白名单放行、SQL 必炸，稳定 500；
     * `sort=severity` 按枚举字符串排，得到字典序 CRITICAL < HIGH < LOW < MEDIUM——不报错，
     * 只是"按等级排序"排出来没有规律。白名单里放了什么键，就得每个都真的请求过。
     */
    @Test
    void everySortKeyWorksInBothDirections() throws Exception {
        alarm("CRITICAL", "UAV_INTRUSION", Instant.parse("2026-09-08T04:00:00Z"));
        attachEventsWithDistinctStates();
        for (String key : List.of("received_at", "occurred_at", "severity", "state")) {
            List<String> asc = sortedValues(key, "sort=" + key + "&order=asc");
            List<String> desc = sortedValues(key, "sort=" + key + "&order=desc");
            // 少于四条就可能刚好看不出字典序与等级序的差别，那样这条用例是空转的。
            assertThat(asc).as("%s 升序", key).hasSizeGreaterThanOrEqualTo(4).isSorted();
            List<String> reversed = new ArrayList<>(asc);
            java.util.Collections.reverse(reversed);
            // 次序键相同的行靠 alarm_id 兜底，且方向一致，所以降序必须恰是升序的倒序。
            assertThat(desc).as("%s 降序", key).isEqualTo(reversed);
            // 导出与列表共用同一个 orderBy，所以 sort=state 当时同样是 500——四个键都得走一遍。
            mvc.perform(get("/api/v1/alarms/export.csv?sort=" + key + "&order=desc")
                            .header("Authorization", bearer(reader)))
                    .andExpect(status().isOk());
        }

        // 上面的循环用等级序号断言，够严但要读者自己去追映射。这里直接钉死字面次序：
        // 旧的字典序写法同样返回 200，也同样"有序"——只有写出期望的那一串才能一眼看出排的是什么。
        assertThat(rawValues("severity", "sort=severity&order=desc"))
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW");
        assertThat(rawValues("state", "sort=state&order=desc"))
                .containsExactly("PENDING_VERIFICATION", "FALSE_POSITIVE", "CONFIRMED", "CONFIRMED");
    }

    /** 某个字段在本用例数据上的原始取值，按接口返回的次序。 */
    private List<String> rawValues(String field, String query) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("alarm_id").asText().startsWith("srt-alarm-")) {
                values.add(item.path(field).asText());
            }
        }
        return values;
    }

    private List<String> sortedValues(String key, String query) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("alarm_id").asText().startsWith("srt-alarm-")) values.add(sortable(key, item));
        }
        return values;
    }

    /** 把一条记录在该排序键上的取值翻成"可按字典序比较"的字符串，好把四个键放进同一个循环里断言。 */
    private static String sortable(String key, JsonNode item) {
        return switch (key) {
            // 时间是 epoch 毫秒，补零后字典序才等于数值序。
            case "received_at", "occurred_at" -> String.format("%020d", item.path(key).asLong());
            // 等级要按严重程度而不是枚举字符串：字典序会排成 CRITICAL < HIGH < LOW < MEDIUM。
            case "severity" -> switch (item.path("severity").asText()) {
                case "CRITICAL" -> "4"; case "HIGH" -> "3"; case "MEDIUM" -> "2"; case "LOW" -> "1"; default -> "0";
            };
            default -> item.path("state").asText();
        };
    }

    /** 给本用例的告警各挂一条状态互不相同的 uav_event——状态在 uav_event 上，不挂就全是 null，排序断言等于空转。 */
    private void attachEventsWithDistinctStates() {
        List<String> states = List.of("PENDING_VERIFICATION", "CONFIRMED", "FALSE_POSITIVE", "CONFIRMED");
        List<String> ids = jdbc.queryForList(
                "select alarm_id from alarm where alarm_id like 'srt-alarm-%' order by alarm_id", String.class);
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T06:00:00Z"));
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,"
                    + "updated_at,version) values (?,?,?,?,?,?,?,1)",
                    "srt-event-" + UUID.randomUUID().toString().substring(0, 8), ids.get(i),
                    states.get(i % states.size()), ORG, DISTRICT, at, at);
        }
    }

    @Test
    void illegalSortOrOrderIsRejectedRatherThanSilentlyIgnored() throws Exception {
        // 悄悄回落到默认次序会让调用方以为自己排好了序，拿到的却是另一种顺序——这种错在页面上很难看出来。
        mvc.perform(get("/api/v1/alarms?sort=alarm_id").header("Authorization", bearer(reader)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/alarms?sort=received_at&order=sideways").header("Authorization", bearer(reader)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void alarmTypeFilterMatchesTheListCount() throws Exception {
        JsonNode all = page("");
        JsonNode filtered = page("alarm_type=UAV_INTRUSION");
        assertThat(filtered.path("total").asLong()).isLessThan(all.path("total").asLong());
        for (JsonNode item : filtered.path("items")) {
            assertThat(item.path("alarm_type").asText()).isEqualTo("UAV_INTRUSION");
        }
        // total 与实际返回的条数必须来自同一套筛选，否则分页会在最后一页对不上。
        assertThat(filtered.path("items").size()).isEqualTo((int) filtered.path("total").asLong());
    }

    @Test
    void exportCarriesBomChineseHeadersAndTheSameOrder() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/alarms/export.csv?sort=occurred_at&order=asc")
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
        assertThat(text.lines().findFirst().orElseThrow()).startsWith("告警编号,告警类别,等级,状态");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("alarms-").contains(".csv");
        // 导出的次序必须与列表一致：两处不同的话，人对着页面核对导出表会核不上。
        List<String> exported = new ArrayList<>();
        text.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> exported.add(l.split(",")[4]));
        assertThat(exported).isSorted();
    }

    /**
     * 决策 15-26：以 = + - @ 开头的单元格会被 Excel/WPS 当公式执行。上游编号是外部系统送进来的，
     * 我们管不住它写什么——导出的表点开就执行别人写的东西，这条路必须在导出侧堵死。
     */
    @Test
    void exportPrefixesFormulaLikeCellsSoSpreadsheetsTreatThemAsText() throws Exception {
        alarm("LOW", "UAV_INTRUSION", Instant.parse("2026-09-08T04:00:00Z"), "=cmd()");
        alarm("LOW", "UAV_INTRUSION", Instant.parse("2026-09-08T05:00:00Z"), "@SUM(1+1)");
        MvcResult result = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        List<String> first = new ArrayList<>();
        text.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> first.add(l.split(",")[0]));
        assertThat(first).contains("'=cmd()", "'@SUM(1+1)");
        // 反面：一格都不能以公式字符开头，否则前缀只是"加了但没加全"。
        assertThat(first).noneMatch(cell -> !cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0);
    }

    /**
     * 决策 15-32：列头早就是中文，正文却还是 HIGH / PENDING_VERIFICATION——一线人员拿到的是半中半英的表。
     * 中文逐字取自前端，导出与页面必须同一套说法。
     */
    @Test
    void exportTranslatesEnumColumnsToChinese() throws Exception {
        attachEventsWithDistinctStates();
        List<String[]> rows = exportRows();
        assertThat(rows).as("本用例的导出行").hasSize(3);
        // 等级：三条种子分别是 LOW/HIGH/MEDIUM。
        assertThat(rows.stream().map(r -> r[2]).sorted().toList())
                .containsExactlyInAnyOrder("低", "高", "中");
        // 状态取自 uav_event，与风险的"待核验"不是同一套（告警核实、风险核验）。
        assertThat(rows.stream().map(r -> r[3]).toList())
                .containsExactlyInAnyOrder("待核实", "已核实，待处置", "误报");
        // 类别：字典里有的翻译，没有的原样给出（不写成"未知"，那会把信息抹掉）。
        assertThat(rows.stream().map(r -> r[1]).toList()).contains("无人机入侵");
    }

    /** 导出正文里属于本用例的那几行，按列拆开。 */
    private List<String[]> exportRows() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String[]> rows = new ArrayList<>();
        new String(body, 3, body.length - 3, StandardCharsets.UTF_8).lines().skip(1)
                .filter(l -> l.startsWith("告警-排序-")).forEach(l -> rows.add(l.split(",")));
        return rows;
    }

    @Test
    void exportIsAudited() throws Exception {
        mvc.perform(get("/api/v1/alarms/export.csv?alarm_type=UAV_INTRUSION")
                .header("Authorization", bearer(reader))).andExpect(status().isOk());
        // 事后要能回答"这份表是谁、按什么条件导出去的"。
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='alarms_exported'"
                + " and detail like '%alarm_type=UAV_INTRUSION%' and detail like '%rows=%'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exportRequiresTheSamePermissionAsTheList() throws Exception {
        String outsider = userWithout();
        mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    void districtOptionsUseTheListPermissionNotSystemAdministration() throws Exception {
        // 决策 15-22：页面原来拉系统管理的 /districts，那要 users.read——业务角色没有，筛选框就是空的：
        // 功能在，但只有管理员用得了。这里的清单跟列表同一权限、同一范围。
        JsonNode districts = json.readTree(mvc.perform(get("/api/v1/alarms/districts")
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
        mvc.perform(get("/api/v1/alarms/districts").header("Authorization", bearer(userWithout())))
                .andExpect(status().isForbidden());
    }

    private List<String> occurredTimes(String query) throws Exception {
        List<String> times = new ArrayList<>();
        for (JsonNode item : page(query).path("items")) {
            if (item.path("alarm_id").asText().startsWith("srt-alarm-")) times.add(item.path("occurred_at").asText());
        }
        return times;
    }

    private JsonNode page(String query) throws Exception {
        String url = "/api/v1/alarms?size=100" + (query.isBlank() ? "" : "&" + query);
        return json.readTree(mvc.perform(get(url).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void alarm(String severity, String type, Instant occurredAt) {
        alarm(severity, type, occurredAt, null);
    }

    private void alarm(String severity, String type, Instant occurredAt, String sourceAlarmId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(occurredAt);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,"
                + "updated_at,version) select 'srt-src','SRT-TEST','排序测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='srt-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'srt-src',?,?,?,?,?,'mock',?,?,?)",
                "srt-alarm-" + suffix, sourceAlarmId == null ? "告警-排序-" + suffix : sourceAlarmId,
                type, severity, at, at, ORG, DISTRICT, at);
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
                    + "created_at) values (?, 'alarm:read','READ',false,current_timestamp)", role);
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
