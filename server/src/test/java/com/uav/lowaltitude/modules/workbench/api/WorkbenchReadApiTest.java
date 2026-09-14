package com.uav.lowaltitude.modules.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工作台只聚合三类源事项：每类仍要求源读权限与精确元组范围，UNION 内先过滤再统一排序分页，
 * 没有权限的类别既不出现在列表里，也不泄露 count。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkbenchReadApiTest {
    private static final String SOURCE = "seed-stage3-source";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, org, district, otherOrg, otherDistrict, plan, routeVersion, otherPlan, otherRouteVersion, target;
    private String full;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "wb-org-" + suffix; district = "wb-dist-" + suffix;
        otherOrg = "wb-other-org-" + suffix; otherDistrict = "wb-other-dist-" + suffix;
        catalog(org, district); catalog(otherOrg, otherDistrict);
        plan = "wb-plan-" + suffix; routeVersion = "wb-rv-" + suffix; planTuple(plan, routeVersion, org, district);
        otherPlan = "wb-plan-other-" + suffix; otherRouteVersion = "wb-rv-other-" + suffix; planTuple(otherPlan, otherRouteVersion, otherOrg, otherDistrict);
        target = "wb-target-" + suffix;
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,?,?,0)", target, "T-" + suffix, org, district, ts(1_000), ts(1_000));
        full = reader("ASSIGNED", org, district);
        grantAction(full, "workbench:read", "alarm:read", "risk:read", "device:read");
        grantModule(full, "monitoring");
    }

    @Test
    void workbenchReadIsRequiredBeforeAnyParameterOrPathIsInterpreted() throws Exception {
        String denied = reader("ASSIGNED", org, district);
        grantAction(denied, "alarm:read", "risk:read");
        mvc.perform(get("/api/v1/workbench/items?page=bad&page=again&page_size=3").header("Authorization", bearer(denied)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/workbench/items/NOPE/   ").header("Authorization", bearer(denied)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void workbenchOnlyReaderGetsEmptyListForbiddenAvailabilityAndNullCounts() throws Exception {
        String workbenchOnly = reader("ASSIGNED", org, district);
        grantAction(workbenchOnly, "workbench:read");
        event("wb-only-event", "wb-only-alarm", "HIGH", 5_000, "PENDING_VERIFICATION", org, district);
        mvc.perform(get("/api/v1/workbench/items").header("Authorization", bearer(workbenchOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.source_availability.UAV_EVENT").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.source_availability.RISK").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.source_availability.DEVICE_INCIDENT").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.counts_by_kind", hasKey("UAV_EVENT")))
                .andExpect(jsonPath("$.data.counts_by_kind.UAV_EVENT").value(nullValue()))
                .andExpect(jsonPath("$.data.counts_by_kind.RISK").value(nullValue()))
                .andExpect(jsonPath("$.data.counts_by_kind.DEVICE_INCIDENT").value(nullValue()))
                .andExpect(jsonPath("$.data.as_of").isNumber());
        // 详情同样先要求源读权限：没有 alarm:read 时即使事项存在也不能借工作台读取。
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-only-event").header("Authorization", bearer(workbenchOnly)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceKindNeedsBothDeviceReadAndMonitoringMenuRead() throws Exception {
        String deviceOnly = reader("ASSIGNED", org, district);
        grantAction(deviceOnly, "workbench:read", "device:read");
        mvc.perform(get("/api/v1/workbench/items").header("Authorization", bearer(deviceOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_availability.DEVICE_INCIDENT").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.counts_by_kind.DEVICE_INCIDENT").value(nullValue()));
    }

    @Test
    void stateRequiresKindAndQueryParsingStaysStrict() throws Exception {
        mvc.perform(get("/api/v1/workbench/items?state=PENDING_VERIFICATION").header("Authorization", bearer(full)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("STATE_REQUIRES_KIND"));
        mvc.perform(get("/api/v1/workbench/items?kind=NOPE").header("Authorization", bearer(full)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_KIND"));
        for (String query : new String[]{"page_size=20", "wat=1", "severity=HIGH&severity=LOW", "kind=RISK&state=BOGUS",
                "severity=URGENT", "source_mode=browser", "page=0", "size=101", "occurred_from=5&occurred_to=5", "occurred_from=1"}) {
            mvc.perform(get("/api/v1/workbench/items?" + query).header("Authorization", bearer(full)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/workbench/items?kind=RISK&state=PENDING_VERIFICATION&severity=HIGH&source_mode=mock&page=1&size=5")
                        .header("Authorization", bearer(full))).andExpect(status().isOk());
    }

    @Test
    void sameTargetEventsSameDeviceIncidentsAndSameSourceIdAcrossKindsAreAllKept() throws Exception {
        String shared = "wb-shared-id-" + suffix;
        event(shared, "wb-alarm-shared-" + suffix, "HIGH", 5_000, "PENDING_VERIFICATION", org, district);
        event("wb-event-b-" + suffix, "wb-alarm-b-" + suffix, "LOW", 4_000, "PENDING_VERIFICATION", org, district);
        risk(shared, "MEDIUM", "PENDING_VERIFICATION", plan, routeVersion, org, district, 3_000);
        String device = device("WB-DEV", org, district);
        incident("wb-inc-1-" + suffix, device, "HIGH", "PENDING", 6_000);
        incident("wb-inc-2-" + suffix, device, "LOW", "RECOVERED", 2_000);

        JsonNode data = data(mvc.perform(get("/api/v1/workbench/items").header("Authorization", bearer(full))).andExpect(status().isOk()).andReturn());
        assertThat(data.get("total").asLong()).isEqualTo(5);
        assertThat(keys(data.get("items"))).containsExactlyInAnyOrder("UAV_EVENT:" + shared, "UAV_EVENT:wb-event-b-" + suffix,
                "RISK:" + shared, "DEVICE_INCIDENT:wb-inc-1-" + suffix, "DEVICE_INCIDENT:wb-inc-2-" + suffix);
        assertThat(data.get("counts_by_kind").get("UAV_EVENT").asLong()).isEqualTo(2);
        assertThat(data.get("counts_by_kind").get("RISK").asLong()).isEqualTo(1);
        assertThat(data.get("counts_by_kind").get("DEVICE_INCIDENT").asLong()).isEqualTo(2);
        assertThat(data.get("source_availability").get("UAV_EVENT").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void mixedKindsUseOneGlobalOrderAndPagingHasNoDuplicatesOrGaps() throws Exception {
        event("wb-e-critical-" + suffix, "wb-a-critical-" + suffix, "CRITICAL", 1_000, "PENDING_VERIFICATION", org, district);
        event("wb-e-high-" + suffix, "wb-a-high-" + suffix, "HIGH", 9_000, "PENDING_VERIFICATION", org, district);
        event("wb-e-low-" + suffix, "wb-a-low-" + suffix, "LOW", 8_000, "CONFIRMED", org, district);
        risk("wb-r-high-" + suffix, "HIGH", "PENDING_VERIFICATION", plan, routeVersion, org, district, 9_500);
        risk("wb-r-medium-" + suffix, "MEDIUM", "PENDING_NOTIFICATION", plan, routeVersion, org, district, 7_000);
        risk("wb-r-low-" + suffix, "LOW", "EXCLUDED", plan, routeVersion, org, district, 8_500);
        String device = device("WB-ORDER", org, district);
        incident("wb-i-high-" + suffix, device, "HIGH", "PENDING", 9_000);
        incident("wb-i-medium-" + suffix, device, "MEDIUM", "PROCESSING", 7_500);
        incident("wb-i-low-" + suffix, device, "LOW", "PENDING_VERIFICATION", 8_000);

        List<String> ordered = new ArrayList<>();
        long total = -1;
        for (int page = 1; page <= 6; page++) {
            JsonNode data = data(mvc.perform(get("/api/v1/workbench/items?page=" + page + "&size=2").header("Authorization", bearer(full)))
                    .andExpect(status().isOk()).andReturn());
            if (total < 0) total = data.get("total").asLong(); else assertThat(data.get("total").asLong()).isEqualTo(total);
            data.get("items").forEach(item -> ordered.add(item.get("kind").asText() + ":" + item.get("source_id").asText()));
        }
        assertThat(total).isEqualTo(9);
        assertThat(new LinkedHashSet<>(ordered)).hasSize(9);
        assertThat(ordered).hasSize(9);
        // action_rank DESC, severity_rank DESC, received_at DESC, kind ASC, source_id DESC（决策 16-10）：
        // 可操作的先按 CRITICAL > HIGH > … 与接收时间倒序、同时间按 kind 字母序；等回执的 PROCESSING 居中；已排除的沉底。
        assertThat(ordered).containsExactly(
                "UAV_EVENT:wb-e-critical-" + suffix,
                "RISK:wb-r-high-" + suffix,
                "DEVICE_INCIDENT:wb-i-high-" + suffix,
                "UAV_EVENT:wb-e-high-" + suffix,
                "RISK:wb-r-medium-" + suffix,
                "DEVICE_INCIDENT:wb-i-low-" + suffix,
                "UAV_EVENT:wb-e-low-" + suffix,
                "DEVICE_INCIDENT:wb-i-medium-" + suffix,
                "RISK:wb-r-low-" + suffix);
        JsonNode first = data(mvc.perform(get("/api/v1/workbench/items?page=1&size=1").header("Authorization", bearer(full))).andReturn()).get("items").get(0);
        assertThat(first.get("received_at").asLong()).isEqualTo(1_000);
        assertThat(first.get("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(first.get("links").get("source").asText()).startsWith("#/alarms?");
        assertThat(first.has("version")).isTrue();
        // 同筛选、同快照的 counts 与 total 一致；kind 过滤下其余类别为 0 而非 null。
        JsonNode filtered = data(mvc.perform(get("/api/v1/workbench/items?kind=DEVICE_INCIDENT&severity=HIGH").header("Authorization", bearer(full))).andReturn());
        assertThat(filtered.get("total").asLong()).isEqualTo(1);
        assertThat(filtered.get("counts_by_kind").get("DEVICE_INCIDENT").asLong()).isEqualTo(1);
        assertThat(filtered.get("counts_by_kind").get("RISK").asLong()).isZero();
        JsonNode timed = data(mvc.perform(get("/api/v1/workbench/items?occurred_from=1000&occurred_to=1001").header("Authorization", bearer(full))).andReturn());
        assertThat(keys(timed.get("items"))).containsExactly("UAV_EVENT:wb-e-critical-" + suffix);
    }

    @Test
    void crossTupleItemsStayInvisibleAndCountsMatchWhatIsListed() throws Exception {
        event("wb-visible-e-" + suffix, "wb-visible-a-" + suffix, "HIGH", 5_000, "PENDING_VERIFICATION", org, district);
        event("wb-hidden-e-" + suffix, "wb-hidden-a-" + suffix, "CRITICAL", 5_500, "PENDING_VERIFICATION", otherOrg, otherDistrict);
        risk("wb-hidden-r-" + suffix, "CRITICAL", "PENDING_VERIFICATION", otherPlan, otherRouteVersion, otherOrg, otherDistrict, 5_600);
        String hiddenDevice = device("WB-HIDDEN", otherOrg, otherDistrict);
        incident("wb-hidden-i-" + suffix, hiddenDevice, "HIGH", "PENDING", 5_700);
        String unmapped = deviceWithoutScope("WB-UNMAPPED");
        incident("wb-unmapped-i-" + suffix, unmapped, "HIGH", "PENDING", 5_800);

        JsonNode data = data(mvc.perform(get("/api/v1/workbench/items").header("Authorization", bearer(full))).andExpect(status().isOk()).andReturn());
        assertThat(keys(data.get("items"))).containsExactly("UAV_EVENT:wb-visible-e-" + suffix);
        assertThat(data.get("total").asLong()).isEqualTo(1);
        assertThat(data.get("counts_by_kind").get("UAV_EVENT").asLong()).isEqualTo(1);
        assertThat(data.get("counts_by_kind").get("RISK").asLong()).isZero();
        assertThat(data.get("counts_by_kind").get("DEVICE_INCIDENT").asLong()).isZero();
        // 越权对象一律 404，不区分“不存在”与“不可见”。
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-hidden-e-" + suffix).header("Authorization", bearer(full))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/workbench/items/RISK/wb-hidden-r-" + suffix).header("Authorization", bearer(full))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-hidden-i-" + suffix).header("Authorization", bearer(full))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-unmapped-i-" + suffix).header("Authorization", bearer(full))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workbench/items/NOPE/whatever").header("Authorization", bearer(full))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_KIND"));
        // 停用归属目录后连 ALL 范围也不可见。
        String all = reader("ALL", org, district);
        grantAction(all, "workbench:read", "alarm:read");
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-hidden-e-" + suffix).header("Authorization", bearer(all))).andExpect(status().isOk());
        jdbc.update("update app_org set enabled=false where org_id=?", otherOrg);
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-hidden-e-" + suffix).header("Authorization", bearer(all))).andExpect(status().isNotFound());
    }

    @Test
    void allowedActionsAndBlockedReasonsFollowSourceStateAndPermissions() throws Exception {
        event("wb-pending-" + suffix, "wb-pending-a-" + suffix, "HIGH", 5_000, "PENDING_VERIFICATION", org, district);
        event("wb-confirmed-" + suffix, "wb-confirmed-a-" + suffix, "HIGH", 4_000, "CONFIRMED", org, district);
        risk("wb-notify-" + suffix, "HIGH", "PENDING_NOTIFICATION", plan, routeVersion, org, district, 3_000);
        String device = device("WB-ACT", org, district);
        incident("wb-act-i-" + suffix, device, "MEDIUM", "PENDING", 2_000);
        // 种子交接引用了种子接收方，不能删除；停用即可让“无接收方”成立。
        jdbc.update("update handoff_recipient set enabled=false where handoff_type='RISK_NOTICE'");

        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-pending-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.item.blocked_reason").value(nullValue()));
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-confirmed-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.item.blocked_reason").value("COUNTERMEASURE_NOT_CONNECTED"));
        mvc.perform(get("/api/v1/workbench/items/RISK/wb-notify-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.item.blocked_reason").value("RECIPIENT_NOT_CONFIGURED"));
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-act-i-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.item.blocked_reason").value(nullValue()))
                .andExpect(jsonPath("$.data.item.version").doesNotExist())
                .andExpect(jsonPath("$.data.item.links.source").value("#/monitor?device_id=" + device))
                .andExpect(jsonPath("$.data.item.state").value("PENDING"));
        incident("wb-wait-i-" + suffix, device, "MEDIUM", "PROCESSING", 1_500);
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-wait-i-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.item.blocked_reason").value("WAITING_RECEIPT"));
        String operator = fullReader();
        jdbc.update("update app_role_permission set permission_level='OP' where permission_code='monitoring' and role_code=(select u.role_code from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?)", operator);
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-act-i-" + suffix).header("Authorization", bearer(operator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions[0]").value("REBOOT"))
                .andExpect(jsonPath("$.data.item.blocked_reason").value(nullValue()));

        // 同一事务内 MyBatis 一级缓存会保留首次权限查询结果，因此追加权限后用新的会话验证。
        String actor = fullReader("alarm:verify", "risk:verify", "handoff:create");
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,?,'RISK_NOTICE',true,current_timestamp,current_timestamp)", "wb-recipient-" + suffix, "测试接收方");
        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-pending-" + suffix).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions[0]").value("VERIFY"));
        mvc.perform(get("/api/v1/workbench/items/RISK/wb-notify-" + suffix).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.allowed_actions[0]").value("NOTIFY"))
                .andExpect(jsonPath("$.data.item.blocked_reason").value(nullValue()));
    }

    @Test
    void detailTimelineOnlyReadsScopedHistoryAndHandoffsNeedHandoffRead() throws Exception {
        String actor = userOf(full);
        event("wb-hist-e-" + suffix, "wb-hist-a-" + suffix, "HIGH", 5_000, "CONFIRMED", org, district);
        jdbc.update("insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','现场轨迹复核属实',?,?)",
                "wb-hist-" + suffix, "wb-hist-e-" + suffix, actor, ts(5_100));
        risk("wb-hist-r-" + suffix, "HIGH", "PENDING_NOTIFICATION", plan, routeVersion, org, district, 3_000);
        jdbc.update("insert into flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED','核验通过',?,?)",
                "wb-rhist-" + suffix, "wb-hist-r-" + suffix, actor, ts(3_100));
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,?,'RISK_NOTICE',true,current_timestamp,current_timestamp)", "wb-rcpt-" + suffix, "测试接收方");
        jdbc.update("insert into handoff (handoff_id,source_kind,source_id,risk_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK',?,?,'RISK_NOTICE',?,1,?,?,'mock',?,?)",
                "wb-handoff-" + suffix, "wb-hist-r-" + suffix, "wb-hist-r-" + suffix, "wb-rcpt-" + suffix, org, district, actor, ts(3_200));
        jdbc.update("insert into handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) values (?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?)",
                "wb-delivery-" + suffix, "wb-handoff-" + suffix, ts(3_200));

        mvc.perform(get("/api/v1/workbench/items/UAV_EVENT/wb-hist-e-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.kind").value("UAV_EVENT"))
                .andExpect(jsonPath("$.data.item.source_id").value("wb-hist-e-" + suffix))
                .andExpect(jsonPath("$.data.timeline[0].entry_type").value("VERIFICATION"))
                .andExpect(jsonPath("$.data.timeline[0].conclusion").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.timeline[0].version").value(1))
                .andExpect(jsonPath("$.data.timeline[0].actor_id").value(actor))
                .andExpect(jsonPath("$.data.availability.verifications").value("AVAILABLE"));
        // 没有 handoff:read：交接段省略并标记 FORBIDDEN，核实历史照常返回。
        mvc.perform(get("/api/v1/workbench/items/RISK/wb-hist-r-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline.length()").value(1))
                .andExpect(jsonPath("$.data.timeline[0].entry_type").value("VERIFICATION"))
                .andExpect(jsonPath("$.data.availability.handoffs").value("FORBIDDEN"));
        String withHandoffRead = fullReader("handoff:read");
        mvc.perform(get("/api/v1/workbench/items/RISK/wb-hist-r-" + suffix).header("Authorization", bearer(withHandoffRead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline.length()").value(2))
                .andExpect(jsonPath("$.data.timeline[1].entry_type").value("HANDOFF"))
                .andExpect(jsonPath("$.data.timeline[1].handoff_id").value("wb-handoff-" + suffix))
                .andExpect(jsonPath("$.data.timeline[1].delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.timeline[1].recipient_name").value("测试接收方"))
                .andExpect(jsonPath("$.data.availability.handoffs").value("AVAILABLE"));
        String device = device("WB-FACT", org, district);
        incident("wb-fact-i-" + suffix, device, "LOW", "RECOVERED", 2_000);
        jdbc.update("update device_incident set closed_at=2500 where incident_id=?", "wb-fact-i-" + suffix);
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-fact-i-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.updated_at").value(2_500))
                .andExpect(jsonPath("$.data.timeline[0].entry_type").value("DEVICE_INCIDENT_DETECTED"))
                .andExpect(jsonPath("$.data.timeline[1].entry_type").value("DEVICE_INCIDENT_CLOSED"))
                .andExpect(jsonPath("$.data.timeline[1].at").value(2_500));
    }

    @Test
    void emptyDeviceMappingIsUnconfiguredNotZeroIncidents() throws Exception {
        String device = device("WB-UNCONF", org, district);
        incident("wb-unconf-i-" + suffix, device, "HIGH", "PENDING", 2_000);
        event("wb-unconf-e-" + suffix, "wb-unconf-a-" + suffix, "LOW", 1_000, "PENDING_VERIFICATION", org, district);
        jdbc.update("delete from device_business_scope");
        mvc.perform(get("/api/v1/workbench/items").header("Authorization", bearer(full)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_availability.DEVICE_INCIDENT").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.data.source_availability.UAV_EVENT").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.counts_by_kind", hasKey("DEVICE_INCIDENT")))
                .andExpect(jsonPath("$.data.counts_by_kind.DEVICE_INCIDENT").value(nullValue()))
                .andExpect(jsonPath("$.data.counts_by_kind.UAV_EVENT").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/workbench/items/DEVICE_INCIDENT/wb-unconf-i-" + suffix).header("Authorization", bearer(full)))
                .andExpect(status().isNotFound());
    }

    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    private static Set<String> keys(JsonNode items) { Set<String> keys = new LinkedHashSet<>(); items.forEach(item -> keys.add(item.get("kind").asText() + ":" + item.get("source_id").asText())); return keys; }

    private void event(String eventId, String alarmId, String severity, long receivedAt, String state, String org, String district) {
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION',?,?,?,'mock',?,?,?)",
                alarmId, org.equals(this.org) ? target : null, SOURCE, "SRC-" + alarmId, severity, ts(receivedAt), ts(receivedAt), org, district, ts(receivedAt));
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,0)", eventId, alarmId, state, org, district, ts(receivedAt), ts(receivedAt));
    }

    private void risk(String riskId, String severity, String state, String plan, String routeVersion, String org, String district, long receivedAt) {
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'ROUTE_DEVIATION',?,?,'ROUTE_DEVIATION','服务端保存的风险依据',?,?,'UNKNOWN','mock',?,?,?,?,0)",
                riskId, SOURCE, "SRC-" + riskId, plan, routeVersion, severity, state, ts(receivedAt), ts(receivedAt), org, district, ts(receivedAt), ts(receivedAt));
    }

    private String device(String no, String org, String district) {
        String id = deviceWithoutScope(no);
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,current_timestamp,current_timestamp)", id, org, district);
        return id;
    }
    private String deviceWithoutScope(String no) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into ops_device (device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) values (?,?,?,'雷达','融合感知箱',true,'mock',true,0,0,0)", id, no + "-" + id.substring(0, 6), "工作台测试设备");
        return id;
    }
    private void incident(String incidentId, String deviceId, String severity, String stage, long detectedAt) {
        jdbc.update("insert into device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) values (?,?,?,'LINK_DEGRADED',?,?,?,'测试异常',true)", incidentId, deviceId, "INC-" + incidentId, severity, stage, detectedAt);
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, orgId.toUpperCase(), orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, districtId.toUpperCase(), districtId);
    }
    private void planTuple(String planId, String routeVersionId, String orgId, String districtId) {
        String route = "wb-route-" + planId;
        Timestamp at = ts(1_000);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,?,'mock',?,?,?,?,0)", route, "R-" + planId, "工作台测试航线", SOURCE, orgId, districtId, at, at);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,cast('SRID=4326;LINESTRING (118 37,118.1 37.1)' as geometry),100,?,?)", routeVersionId, route, at, at);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,0)", planId, "P-" + planId, SOURCE, at, ts(10_000), routeVersionId, orgId, districtId, at, at);
    }

    private String reader(String scope, String orgId, String districtId) {
        String id = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-WB-" + id;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "wb-" + id, "工作台测试", role, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user, orgId, districtId);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }
    /** 与 full 相同的三类源权限，再附加额外动作；新会话避免同事务内的权限查询缓存。 */
    private String fullReader(String... extra) {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "workbench:read", "alarm:read", "risk:read", "device:read");
        grantModule(token, "monitoring");
        grantAction(token, extra);
        return token;
    }
    private void grantAction(String token, String... permissions) {
        for (String permission : permissions) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',false,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", permission, token);
    }
    /** 运维菜单读取许可是 MODULE 目录项（如 monitoring），与 ACTION 目录分开授予。 */
    private void grantModule(String token, String module) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',true,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", module, token);
    }
    private String userOf(String token) { return jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token); }
    private static Timestamp ts(long millis) { return Timestamp.from(Instant.ofEpochMilli(millis)); }
    private static String bearer(String token) { return "Bearer " + token; }
}
