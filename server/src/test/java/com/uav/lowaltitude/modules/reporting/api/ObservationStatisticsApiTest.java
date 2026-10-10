package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.reporting.infrastructure.ObservationMetricsRepository;
import com.uav.lowaltitude.modules.reporting.infrastructure.ReportingRepository.Scope;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.qa.reporting.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ObservationStatisticsApiTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired ObservationMetricsRepository repository;
    @org.springframework.boot.test.mock.mockito.SpyBean
    com.uav.lowaltitude.modules.reporting.application.ObservationMetricsService observationService;
    private final LocalDate date=LocalDate.of(2005,4,7);
    private final OffsetDateTime at=date.atTime(12,0).atOffset(ZoneOffset.ofHours(8));
    private record Fixture(String target,String track,String source,String org,String district) { }
    private String id() { return UUID.randomUUID().toString(); }
    private Fixture fixture(String mode,double longitude,boolean confirmed) {
        return fixture(mode,longitude,confirmed,mode,"{}");
    }
    private Fixture fixture(String mode,double longitude,boolean confirmed,String observationMode,String quality) {
        String org=id(),district=id(),source=id(),target=id(),track=id(),config=id().replace("-","");
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,org);
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",district,district,district);
        jdbc.update("insert into integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,source_type,created_at,updated_at,version) values(?,?,?,'RADAR','3.0',true,?,'RADAR',?,?,0)",source,source,source,mode,at,at);
        // First detection predates the report: observations in the period must still be counted.
        jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values(?,?,'UAV',?,?,?,?,?,?,?,0)",target,target,mode,org,district,at.minusDays(2),at,at,at);
        jdbc.update("insert into fusion_config(config_version,status,schema_status,params,note,created_at,version) select ?,'DRAFT',?,params,'isolated test',?,0 from fusion_config where config_version='demo-v1'",config,confirmed?"CONFIRMED":"DEMO",at);
        jdbc.update("insert into track(track_id,target_id,external_track_id,started_at,created_at,layer,config_version) values(?,?,?,?,?,'FUSED',?)",track,target,"fused:"+target,at,at,config);
        Fixture f=new Fixture(target,track,source,org,district);
        point(f,observationMode,0,longitude,quality);point(f,observationMode,1,longitude+.0001,quality);point(f,observationMode,2,longitude+.0002,quality);
        return f;
    }
    private void point(Fixture f,String mode,int seq,double lon,String quality) {
        String observation=id();var time=at.plusSeconds(seq);String location="SRID=4326;POINT("+lon+" 35)";
        jdbc.update("insert into source_observation(observation_id,source_id,source_type,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,quality,source_mode,owner_org_id,district_id,class_code,created_at) values(?,?,'RADAR',?,?,?, ?,CAST(? AS GEOMETRY),10,CAST(? AS JSON),?,?,?,'UAV',?)",
                observation,f.source,f.target,f.target,time,time,location,quality,mode,f.org,f.district,time);
        String contributing="[{\"source_id\":\""+f.source+"\",\"observation_id\":\""+observation+"\",\"weight\":1}]";
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,observation_id,position_accuracy_m,contributing,position_source_id,source_switched) values(?,?,?,?,?,CAST(? AS GEOMETRY),?,'MEAS',?,10,CAST(? AS JSON),?,false)",
                id(),f.track,seq,time,time,location,time,observation,contributing,f.source);
    }
    private com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.Result read(Scope scope,List<String> modes) {
        return repository.read(date,date,at.plusDays(1).toInstant().toEpochMilli(),scope,modes);
    }
    @ParameterizedTest @CsvSource({"live,105.0","replay,115.0","mock,-70.0"})
    void realAndSimulatedSourcesAndOrgScopeStaySeparate(String mode,double lon) {
        Fixture own=fixture(mode,lon,true);fixture(mode,lon+1,true);fixture(mode.equals("live")?"replay":"live",lon+2,true);
        var report=read(new Scope(true,"test",own.org),List.of(mode));
        assertThat(report.durationSeconds()).isEqualTo(2);assertThat(report.distanceMeters()).isBetween(16d,20d);
        assertThat(report.measuredTargets()).isEqualTo(1);assertThat(report.sourceModes()).containsExactly(mode);
    }
    @Test void tupleScopeAndAnomalousOrCrossSourceLineageAreEnforced() {
        Fixture own=fixture("replay",105,true);fixture("replay",110,true);
        String user=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        jdbc.update("delete from app_user_data_scope where user_id=?",user);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",user,own.org,own.district);
        assertThat(read(new Scope(false,user),List.of("replay")).durationSeconds()).isEqualTo(2);
        Fixture anomaly=fixture("replay",106,true,"replay","{\"anomaly_z\":99}");
        assertThat(read(new Scope(true,user,anomaly.org),List.of("replay")).durationSeconds()).isNull();
        Fixture crossed=fixture("replay",107,true,"live","{}");
        assertThat(read(new Scope(true,user,crossed.org),List.of("replay")).durationSeconds()).isNull();
    }
    @Test void missingConfigAndDemoLiveFactsStayUnavailable() {
        Fixture f=fixture("live",100,false);
        assertThat(read(new Scope(true,"test",f.org),List.of("live")).durationSeconds()).isNull();
        jdbc.update("update track set config_version=null where track_id=?",f.track);
        assertThat(read(new Scope(true,"test",f.org),List.of("live")).durationSeconds()).isNull();
    }
    private String token() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    @ParameterizedTest @CsvSource({"live,104.0", "replay,114.0"})
    void pageQuerySkipsTrackCalculationAndPreservesRequestedStatistics(String mode, double longitude) throws Exception {
        Fixture f=fixture(mode,longitude,true);String auth="Bearer "+token();
        // The old target has observations today, but is not a new target for today's page metrics.
        org.mockito.Mockito.clearInvocations(observationService);
        var light=json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth)
                .param("from",date.toString()).param("to",date.toString()).param("owner_org_id",f.org)
                .param("include_observations","false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.observation_metrics").doesNotExist())
                .andReturn().getResponse().getContentAsString()).path("data");
        org.mockito.Mockito.verify(observationService,org.mockito.Mockito.never()).operations(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThat(light.path("source_mode").asText()).isEqualTo("unknown");
        var full=json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth)
                .param("from",date.toString()).param("to",date.toString()).param("owner_org_id",f.org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observation_metrics.duration_seconds").value(2))
                .andReturn().getResponse().getContentAsString()).path("data");
        for(String field:List.of("summary","days","regions","by_risk","by_type","alt_bands","devices","availability"))
            assertThat(light.path(field)).as(field).isEqualTo(full.path(field));
        assertThat(full.path("source_mode").asText()).isEqualTo(mode);
        mvc.perform(get("/api/v1/stats/operations").param("include_observations","false"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth)
                .param("include_observations","false").param("owner_org_id",id()))
                .andExpect(status().isBadRequest());
    }

    private void todayTarget(String mode) {
        String org=id(),district=id(),target=id();var now=OffsetDateTime.now();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,org);
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",district,district,district);
        jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values(?,?,'UAV',?,?,?,?,?,?,?,0)",target,target,mode,org,district,now,now,now,now);
    }
    private com.fasterxml.jackson.databind.JsonNode statsDays(String auth,LocalDate from,LocalDate to) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth)
                .param("from",from.toString()).param("to",to.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("days");
    }

    /** 数据大屏的趋势只要按日数字：不再为它对每个融合点逐点算监测时长和里程（7 天报表里最慢的一块），按日数字与完整报表一致。 */
    @ParameterizedTest @CsvSource({"live","replay"})
    void dashboardTrendSkipsTrackCalculationAndKeepsTheDailyNumbers(String mode) throws Exception {
        String auth="Bearer "+token();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai")),from=today.minusDays(6);
        todayTarget(mode);
        org.mockito.Mockito.clearInvocations(observationService);
        var trend=json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization",auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("trend");
        org.mockito.Mockito.verify(observationService,org.mockito.Mockito.never()).operations(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        // 完整报表确实会算观测指标；没有这一步，上面的 never() 证明不了什么。
        var full=statsDays(auth,from,today);
        org.mockito.Mockito.verify(observationService,org.mockito.Mockito.atLeastOnce()).operations(
                org.mockito.ArgumentMatchers.eq(from),org.mockito.ArgumentMatchers.eq(today),org.mockito.ArgumentMatchers.isNull());
        assertThat(trend.path("days")).hasSize(7);assertThat(full).hasSize(7);
        assertThat(trend.path("days").get(6).path("total").asInt()).isGreaterThanOrEqualTo(1);
        for(int i=0;i<7;i++)
            for(String field:List.of("date","md","total","illegal"))
                assertThat(trend.path("days").get(i).path(field)).as("day "+i+" "+field).isEqualTo(full.get(i).path(field));
        assertThat(trend.path("from").asText()).isEqualTo(from.toString());assertThat(trend.path("to").asText()).isEqualTo(today.toString());
        // 今天新出现的目标来自模拟器（replay）时，趋势仍标明含模拟数据（页面的“含模拟数据”提示靠它）。
        if ("replay".equals(mode)) assertThat(trend.path("simulated").asBoolean()).isTrue();
    }

    @Test void apiKeepsOldCountsAndCsvAndBusinessExportsUseSameMetric() throws Exception {
        Fixture f=fixture("live",100,true);String auth="Bearer "+token();
        mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth).param("from",date.toString()).param("to",date.toString()).param("owner_org_id",f.org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.total").value(0))
                .andExpect(jsonPath("$.data.observation_metrics.duration_seconds").value(2))
                .andExpect(jsonPath("$.data.availability.by_duration.status").value("UNAVAILABLE"));
        String csv=mvc.perform(get("/api/v1/stats/operations/export.csv").header("Authorization",auth).param("from",date.toString()).param("to",date.toString()).param("owner_org_id",f.org))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("有效监测时长(秒)\",\"2.0").contains("已观测里程(米)");
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",date.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observation_metrics.duration_seconds").value(2));
        byte[] excel=mvc.perform(get("/api/v1/stats/reports/export.xlsx").header("Authorization",auth).param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",date.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            assertThat(workbook.getSheet("监测时长与里程").getRow(1).getCell(1).getNumericCellValue()).isEqualTo(2);
        }
        byte[] pdf=mvc.perform(get("/api/v1/stats/reports/export.pdf").header("Authorization",auth).param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",date.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("有效监测时长（秒）：2.000","已观测里程");
        }
    }
}
