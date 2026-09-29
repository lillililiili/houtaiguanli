package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.dev-seed.enabled=false","app.airspace.upstream.source-id=test-upper-live","app.airspace.upstream.user-id=test-upper-user",
    "spring.datasource.url=jdbc:h2:mem:upstream_live;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class UpstreamAirspaceLiveApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void boundAccountAcceptsLiveAndOtherAccountOrDisabledSourceIsRejected() throws Exception {
        var f=new UpstreamAirspaceApiTest();f.mvc=mvc;f.jdbc=jdbc;f.json=json;f.fixedUser="test-upper-user";f.fixture();
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version) VALUES('test-upper-live','TEST-UPPER-LIVE','测试正式空域来源','AIRSPACE_PUSH_V1','1',TRUE,'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
        var body=f.input(1);
        mvc.perform(post("/api/v1/integrations/airspaces/messages").header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.source_mode").value("live"));
        assertThat(jdbc.queryForObject("select source_id from airspace where airspace_no=?",String.class,f.no)).isEqualTo("test-upper-live");
        f.send(f.input(2)).andExpect(status().isNotFound()); // mock source cannot take over live airspace
        var other=new UpstreamAirspaceApiTest();other.mvc=mvc;other.jdbc=jdbc;other.json=json;other.fixture();
        mvc.perform(post("/api/v1/integrations/airspaces/messages").header("Authorization","Bearer "+other.token).contentType(MediaType.APPLICATION_JSON).content(other.input(1).toString()))
            .andExpect(status().isForbidden());
        jdbc.update("UPDATE integration_source SET enabled=FALSE WHERE source_id='test-upper-live'");
        mvc.perform(post("/api/v1/integrations/airspaces/messages").header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isServiceUnavailable());
    }
}
