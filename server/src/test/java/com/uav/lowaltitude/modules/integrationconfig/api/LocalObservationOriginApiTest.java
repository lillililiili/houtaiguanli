package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Explicit simulator inputs in a rollback-only isolated test database. */
@SpringBootTest(properties={"app.handoff.channel=mock","app.automation-rules.runtime.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LocalObservationOriginApiTest {
    private static final String URL="/api/v1/local-interface-simulator/observation-devices";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private String token,org,district;

    @BeforeEach void setup() throws Exception {
        token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        long now=System.currentTimeMillis(); org=id(); district=id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at) VALUES(?,?,'origin test org',TRUE,?,?)",org,org,now,now);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at) VALUES(?,?,'origin test district',TRUE,?,?)",district,district,now,now);
    }

    @Test void declaredCoordinateSystemIsIncludedInRegistrationHashAndIdentityRemainsUnmapped() throws Exception {
        Map<String,Object> body=input();
        JsonNode registered=send(body,200);
        assertThat(send(body,200)).isEqualTo(registered);
        var row=jdbc.queryForMap("SELECT request_hash,origin_ops_device_id FROM simulator_observation_source WHERE source_id=?",registered.path("source_id").asText());
        String oldHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(
                new RegistrationSnapshot((String)body.get("message_id"),(String)body.get("name"),(BigDecimal)body.get("longitude"),
                        (BigDecimal)body.get("latitude"),org,district,"WGS84"))));
        assertThat(row.get("request_hash")).isEqualTo(oldHash);
        assertThat(row.get("origin_ops_device_id")).isNull();
        body.put("origin_ops_device_id",origin());
        send(body,409);
        assertThat(jdbc.queryForObject("SELECT origin_ops_device_id FROM simulator_observation_source WHERE source_id=?",String.class,registered.path("source_id").asText())).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings={" ", "GCJ02", "BD-09", "CGCS2000"})
    void observationAndWeatherRegistrationsRejectUndeclaredOrForeignCoordinates(String coordinateSystem) throws Exception {
        for(String path:new String[]{URL,"/api/v1/local-interface-simulator/weather-devices"}) {
            Map<String,Object> body=input();body.put("device_no","WX-"+id());
            if(coordinateSystem==null)body.remove("coordinate_system");else body.put("coordinate_system",coordinateSystem);
            mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
            String table=URL.equals(path)?"simulator_observation_source":"simulator_weather_device";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE message_id=?",Long.class,body.get("message_id"))).isZero();
        }
    }

    @Test void mappedRegistrationBindsIdentityIntoIdempotencyAndDoesNotMutateOriginal() throws Exception {
        String original=origin(),other=origin();
        Map<String,Object> body=input(); body.put("origin_ops_device_id",original);
        JsonNode registered=send(body,200);
        assertThat(send(body,200)).isEqualTo(registered);
        body.put("origin_ops_device_id",other); send(body,409);
        assertThat(jdbc.queryForObject("SELECT origin_ops_device_id FROM simulator_observation_source WHERE source_id=?",String.class,registered.path("source_id").asText())).isEqualTo(original);
        body.put("message_id",id());
        assertThat(send(body,200).path("source_id").asText()).isNotEqualTo(registered.path("source_id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings={"disabled","retired","real","source-real","source-disabled","fusion-disabled","foreign-scope","unregistered","external-mismatch","source-mismatch","unknown","blank"})
    void rejectsInvalidOriginWithoutRegisteringAnything(String condition) throws Exception {
        String device=origin();
        switch(condition) {
            case "disabled" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",device);
            case "retired" -> jdbc.update("UPDATE ops_device SET enabled=FALSE,deleted_at=? WHERE device_id=?",System.currentTimeMillis(),device);
            case "real" -> jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",device);
            case "source-real" -> jdbc.update("UPDATE ops_integration_source SET simulated=FALSE WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",device);
            case "source-disabled" -> jdbc.update("UPDATE ops_integration_source SET enabled=FALSE WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",device);
            case "fusion-disabled" -> jdbc.update("UPDATE device SET enabled=FALSE WHERE device_id=?",device);
            case "foreign-scope" -> jdbc.update("UPDATE device_business_scope SET owner_org_id=(SELECT MIN(org_id) FROM app_org WHERE org_id<>?) WHERE ops_device_id=?",org,device);
            case "unregistered" -> jdbc.update("DELETE FROM device WHERE device_id=?",device);
            case "external-mismatch" -> jdbc.update("UPDATE device SET external_device_id=? WHERE device_id=?",id(),device);
            case "source-mismatch" -> jdbc.update("UPDATE integration_source SET source_code=? WHERE source_id=(SELECT source_id FROM device WHERE device_id=?)",id(),device);
            case "unknown" -> device=id();
            case "blank" -> device=" ";
            default -> throw new IllegalArgumentException(condition);
        }
        Map<String,Object> body=input(); body.put("origin_ops_device_id",device);
        send(body,400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulator_observation_source WHERE message_id=?",Long.class,body.get("message_id"))).isZero();
    }

    @Test void requiresAuthenticationBeforeCreatingAnOriginMapping() throws Exception {
        Map<String,Object> body=input(); body.put("origin_ops_device_id",origin());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulator_observation_source WHERE message_id=?",Long.class,body.get("message_id"))).isZero();
    }

    private Map<String,Object> input() {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("message_id",id());body.put("name","explicit normalized observation");
        body.put("longitude",new BigDecimal("118.2"));body.put("latitude",new BigDecimal("37.1"));
        body.put("owner_org_id",org);body.put("district_id",district);body.put("coordinate_system","WGS84");return body;
    }

    private String origin() {
        String device=id(),source=id(),fusionSource=id(),code=id();long now=System.currentTimeMillis();Timestamp at=new Timestamp(now);
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,created_at,updated_at) VALUES(?,?,'origin source','RADAR_TCP_V3_0_0','live',TRUE,TRUE,?,?)",source,code,now,now);
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,created_at,updated_at) VALUES(?,?,'origin source','RADAR_TCP_V3_0_0','live',TRUE,?,?)",fusionSource,code,at,at);
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,?, 'origin device','TEST','TEST',TRUE,'live',TRUE,?,?)",device,source,device,device,now,now);
        jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,enabled,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,'origin device','RADAR',TRUE,'live',?,?,?,?)",device,fusionSource,device,device,org,district,at,at);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",device,org,district,at,at);
        return device;
    }

    private JsonNode send(Object input,int expected) throws Exception {
        return json.readTree(mvc.perform(post(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private static String id(){return UUID.randomUUID().toString();}
    private record RegistrationSnapshot(String messageId,String name,BigDecimal longitude,BigDecimal latitude,String ownerOrgId,String districtId,String coordinateSystem) { }
}
