package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeviceEventsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private String token;

    @BeforeEach
    void login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = json.readTree(body).path("data").path("session_id").asText();
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'");
    }

    @Test
    void defaultReadsOldestAndLatestReadsNewestInAscendingSequence() throws Exception {
        String device = device();
        List<Long> seq = new ArrayList<>();
        for (int i = 0; i < 5; i++) seq.add(event(device));
        JsonNode oldest = events("?device_id=" + device + "&limit=2");
        seq = seq.stream().map(this::publishedSequence).toList();
        assertThat(sequences(oldest)).containsExactly(seq.get(0), seq.get(1));
        assertThat(oldest.path("next_seq").asLong()).isEqualTo(seq.get(1));
        JsonNode latest = events("?device_id=" + device + "&limit=2&latest=true");
        assertThat(sequences(latest)).containsExactly(seq.get(3), seq.get(4));
        assertThat(latest.path("next_seq").asLong()).isEqualTo(seq.get(4));
        assertThat(latest.path("items").get(0).path("device_id").asText()).isEqualTo(device);
    }

    @Test
    void positiveCursorAlwaysUsesIncrementalOrderingAndEmptyBatchKeepsCursor() throws Exception {
        String device = device();
        long first = event(device), second = event(device), third = event(device);
        events("?device_id=" + device);
        first = publishedSequence(first);
        second = publishedSequence(second);
        third = publishedSequence(third);
        JsonNode incremental = events("?device_id=" + device + "&after_seq=" + first + "&latest=true&limit=1");
        assertThat(sequences(incremental)).containsExactly(second);
        JsonNode empty = events("?device_id=" + device + "&after_seq=" + third);
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("next_seq").asLong()).isEqualTo(third);
    }

    @Test
    void validDeviceWithoutEventsReturnsEmptyAndNegativeCursorIsNormalized() throws Exception {
        JsonNode empty = events("?device_id=" + device() + "&latest=true&after_seq=-1");
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("next_seq").asLong()).isZero();
    }

    @Test
    void deletedAndMissingDevicesReturnNotFoundAndDeletedEventsAreExcluded() throws Exception {
        String device = device();
        long raw = event(device);
        events("?device_id=" + device);
        long seq = publishedSequence(raw);
        jdbc.update("UPDATE ops_device SET deleted_at=1,enabled=FALSE WHERE device_id=?", device);
        forbiddenDevice(device);
        forbiddenDevice("missing-device");
        assertThat(sequences(events("?after_seq=" + (seq - 1)))).doesNotContain(seq);
    }

    @Test
    void assignedScopeFiltersBeforeLatestLimitAndRejectsOutOfScopeDevice() throws Exception {
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString();
        String otherDistrict = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, org);
        for (String id : List.of(district, otherDistrict))
            jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", id, id, id);
        String visible = device(), hidden = device(), unmapped = device();
        jdbc.update("UPDATE ops_device SET device_type_code='radar' WHERE device_id=?", visible);
        jdbc.update("UPDATE ops_device SET device_type_code='countermeasure' WHERE device_id=?", hidden);
        jdbc.update("UPDATE ops_device SET device_type_code='eo' WHERE device_id=?", unmapped);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", visible, org, district);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", hidden, org, otherDistrict);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=(SELECT user_id FROM app_user WHERE account='admin1')");
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) SELECT user_id,?,? FROM app_user WHERE account='admin1'", org, district);
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE account='admin1'");
        long visibleSeq = event(visible), hiddenSeq = event(hidden), unmappedSeq = event(unmapped);
        JsonNode latest = events("?latest=true&limit=1");
        visibleSeq = publishedSequence(visibleSeq);
        hiddenSeq = publishedSequence(hiddenSeq);
        unmappedSeq = publishedSequence(unmappedSeq);
        assertThat(sequences(latest)).containsExactly(visibleSeq);
        assertThat(sequences(events("?after_seq=" + (visibleSeq - 1)))).containsExactly(visibleSeq).doesNotContain(hiddenSeq, unmappedSeq);
        forbiddenDevice(hidden);
        forbiddenDevice(unmapped);
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?", org);
        forbiddenDevice(visible);
        assertThat(sequences(events("?after_seq=" + (visibleSeq - 1)))).isEmpty();
    }

    @Test
    void requiresMonitoringPermissionAndBusinessDataScope() throws Exception {
        mvc.perform(get("/api/v1/device-events")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        mvc.perform(get("/api/v1/device-events").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'");
        String role = "EVT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("UPDATE app_user SET role_code=? WHERE account='admin1'", role);
        mvc.perform(get("/api/v1/device-events").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incrementalPollingIncludesLowerRawSequenceThatCommitsLater() throws Exception {
        String device = device();
        var inserted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var transaction = new TransactionTemplate(transactions);
        var delayed = pool.submit(() -> transaction.execute(status -> {
            long raw = event(device);
            inserted.countDown();
            try {
                if (!allowCommit.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Commit gate timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return raw;
        }));
        try {
            assertThat(inserted.await(20, TimeUnit.SECONDS)).isTrue();
            long committedFirst = transaction.execute(status -> event(device));
            JsonNode first = events("?device_id=" + device);
            assertThat(first.path("items")).hasSize(1);
            assertThat(first.path("items").get(0).path("event_id").asText()).isEqualTo(eventId(committedFirst));
            long cursor = first.path("next_seq").asLong();
            allowCommit.countDown();
            long committedLater = delayed.get(20, TimeUnit.SECONDS);
            assertThat(committedLater).isLessThan(committedFirst);
            JsonNode next = events("?device_id=" + device + "&after_seq=" + cursor);
            assertThat(next.path("items")).hasSize(1);
            assertThat(next.path("items").get(0).path("event_id").asText()).isEqualTo(eventId(committedLater));
            assertThat(next.path("next_seq").asLong()).isGreaterThan(cursor);
            assertThat(events("?device_id=" + device + "&after_seq=" + next.path("next_seq").asLong()).path("items")).isEmpty();
        } finally {
            allowCommit.countDown();
            try { delayed.get(20, TimeUnit.SECONDS); } finally {
                pool.shutdownNow();
                jdbc.update("DELETE FROM device_event_publication WHERE raw_event_seq IN (SELECT event_seq FROM device_event_log WHERE device_id=?)", device);
                jdbc.update("DELETE FROM device_event_log WHERE device_id=?", device);
                jdbc.update("DELETE FROM ops_device WHERE device_id=?", device);
            }
        }
    }

    private long publishedSequence(long raw) {
        return jdbc.queryForObject("SELECT event_seq FROM device_event_publication WHERE raw_event_seq=?", Long.class, raw);
    }

    private String eventId(long raw) {
        return jdbc.queryForObject("SELECT event_id FROM device_event_log WHERE event_seq=?", String.class, raw);
    }

    private JsonNode events(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/device-events" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void forbiddenDevice(String id) throws Exception {
        mvc.perform(get("/api/v1/device-events").param("device_id", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private List<Long> sequences(JsonNode data) {
        List<Long> result = new ArrayList<>();
        data.path("items").forEach(item -> result.add(item.path("event_seq").asLong()));
        return result;
    }

    private String device() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) VALUES (?,?,?,'weather_sensor','气象传感器','测试',TRUE,'mock',TRUE,0,0,0)", id, id, "事件接口测试");
        return id;
    }

    private long event(String device) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_event_log(event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,'REPORT_SUMMARY','INFO','测试上报摘要',1,TRUE)", id, device);
        return jdbc.queryForObject("SELECT event_seq FROM device_event_log WHERE event_id=?", Long.class, id);
    }
}
