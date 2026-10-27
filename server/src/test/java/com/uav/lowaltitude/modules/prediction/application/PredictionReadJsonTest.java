package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.time.AppClock;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PredictionReadJsonTest {
    private final ObjectMapper json=new ObjectMapper();
    private final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    private final AppClock clock=mock(AppClock.class);
    private final PredictionReadService service=new PredictionReadService(jdbc,mock(AccessControlService.class),json,clock);

    @ParameterizedTest @ValueSource(strings={"TEXT","UTF8_BYTES","WRAPPED_JSON","ENCODED_UTF8_BYTES"})
    void batchEvidenceAcceptsJdbcRepresentationsWithoutLosingUnicode(String representation) throws Exception {
        String event=UUID.randomUUID().toString(),target=UUID.randomUUID().toString();
        String document=json.writeValueAsString(Map.of("target_id",target,"status","READY","valid_until",2000,
                "description","连续实测与预测","points",List.of()));
        String warning=json.writeValueAsString(Map.of("airspace_name","北侧禁飞区"));
        when(clock.nowMillis()).thenReturn(1000L);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(row(event,encoded(document,representation),encoded(warning,representation))));

        AlarmDto dto=alarm(event,target);
        assertThat(service.enrichList(List.of(dto))).containsExactly(dto);
        assertThat(dto.getPrediction()).containsEntry("description","连续实测与预测").containsEntry("target_id",target);
        assertThat(((Map<?,?>)dto.getPrediction().get("warning")).get("airspace_name")).isEqualTo("北侧禁飞区");
        assertThat(dto.getPredictionSnapshot()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"broken-json","[]","null"})
    void malformedOrNonObjectEvidenceIsRejected(String raw) {
        String event=UUID.randomUUID().toString();
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(row(event,raw.getBytes(StandardCharsets.UTF_8),"{}")));
        assertThatThrownBy(()->service.enrichList(List.of(alarm(event,UUID.randomUUID().toString()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid prediction evidence JSON");
    }

    @Test void legacyUnknownReadsFirstFrozenAirspaceSeparatelyFromLastValidMeasurement() throws Exception {
        String event=UUID.randomUUID().toString(),target=UUID.randomUUID().toString(),region=UUID.randomUUID().toString();
        String first=UUID.randomUUID().toString(),last=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        var row=row(event,json.writeValueAsBytes(Map.of("status","UNKNOWN","reason_codes",List.of("STALE_OR_UNTRUSTED_MEASUREMENT"),"points",List.of())),
                json.writeValueAsBytes(Map.of("airspace_id",region,"airspace_name",region,"airspace_version_ids",List.of())));
        row.put("status","UNKNOWN");row.put("reason","STALE_OR_UNTRUSTED_MEASUREMENT");
        row.put("last_evidence_prediction_id",last);row.put("last_evidence_generated_at",1000L);row.put("last_evidence_observed_at",900L);
        row.put("frozen_detail",json.writeValueAsBytes(Map.of("prediction_id",first,"prediction",
                Map.of("airspace_name","首次禁飞区名称","airspace_version_ids",List.of(version)))));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(row));
        AlarmDto dto=service.enrichList(List.of(alarm(event,target))).get(0);
        assertThat(dto.getPrediction()).containsEntry("evidence_status","LAST_KNOWN").containsEntry("based_on_observed_at",900L)
                .containsEntry("last_evidence_prediction_id",last).containsEntry("reason_codes",List.of("STALE_OR_UNTRUSTED_MEASUREMENT"));
        var warning=(Map<?,?>)dto.getPrediction().get("warning");
        assertThat(warning.get("airspace_name")).isEqualTo("首次禁飞区名称");
        assertThat(warning.get("airspace_version_ids")).isEqualTo(List.of(version));
        assertThat(warning.get("airspace_basis")).isEqualTo("FIRST_FROZEN");
        assertThat(warning.get("airspace_basis_prediction_id")).isEqualTo(first);
    }

    @ParameterizedTest @ValueSource(longs={1500L,2500L})
    void processingFailureKeepsItsCauseBeforeAndAfterForecastExpiry(long now) throws Exception {
        String event=UUID.randomUUID().toString();
        var row=row(event,json.writeValueAsString(Map.of("status","READY","valid_until",2000L,"points",List.of(Map.of("horizon_seconds",10)),
                "trajectory",List.of(Map.of("horizon_seconds",0)),"reason_codes",List.of("READY"))),"{}");
        row.put("status","UNKNOWN");row.put("reason","PREDICTION_PROCESSING_FAILED");
        when(clock.nowMillis()).thenReturn(now);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(row));
        var result=service.enrichList(List.of(alarm(event,UUID.randomUUID().toString()))).get(0).getPrediction();
        assertThat(result).containsEntry("status","UNKNOWN").containsEntry("reason_codes",List.of("PREDICTION_PROCESSING_FAILED"));
        assertThat((List<?>)result.get("points")).isEmpty();
        assertThat(result).doesNotContainKey("trajectory");
    }

    @ParameterizedTest @ValueSource(longs={1999L,2000L})
    void verificationDisplayAndEligibilityUseOneCurrentReadAndOneExpiryBoundary(long now) throws Exception {
        String event=UUID.randomUUID().toString(),target=UUID.randomUUID().toString(),prediction=UUID.randomUUID().toString();
        var current=row(event,json.writeValueAsString(Map.of("prediction_id",prediction,"target_id",target,
                "status","READY","valid_until",2000L,"points",List.of(Map.of("horizon_seconds",10)))),"{}");
        current.put("latest_prediction_id",prediction);current.put("last_observed_at",1000L);
        current.put("current_evidence_valid",true);current.put("snapshot_status","READY");
        current.put("snapshot_generated_at",1100L);current.put("snapshot_valid_until",2000L);
        when(clock.nowMillis()).thenReturn(now);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(current));
        var dto=alarm(event,target).withRiskCase(Map.of("risk_case_id",UUID.randomUUID().toString(),"version",3L));

        var basis=service.verificationEvidence(dto);
        assertThat(basis.confirmable()).isEqualTo(now<2000L);
        assertThat(basis.checkedAt()).isEqualTo(now);
        assertThat(dto.getPrediction()).containsEntry("prediction_id",prediction)
                .containsEntry("status",now<2000L?"READY":"UNKNOWN");
        assertThat(dto.getPredictionHistory()).isEmpty();assertThat(dto.getPredictionSnapshot()).isEmpty();
        org.mockito.Mockito.verify(jdbc,org.mockito.Mockito.times(1)).queryForList(anyString(),anyMap());
        org.mockito.Mockito.verify(clock,org.mockito.Mockito.times(1)).nowMillis();
    }

    private Object encoded(String value,String representation) throws Exception {
        return switch(representation) {
            case "UTF8_BYTES"->value.getBytes(StandardCharsets.UTF_8);
            case "ENCODED_UTF8_BYTES"->json.writeValueAsBytes(value);
            case "WRAPPED_JSON"->new JdbcJson(value);
            default->value;
        };
    }
    private Map<String,Object> row(String event,Object result,Object warning) {
        var row=new HashMap<String,Object>();
        row.put("event_id",event);row.put("role","PREDICTED");row.put("status","ACTIVE");row.put("valid_until",2000L);
        row.put("reason","STABLE_ENTRY_PREDICTION");row.put("version",3L);row.put("episode_id",UUID.randomUUID().toString());
        row.put("result_json",result);row.put("warning_json",warning);
        return row;
    }
    private AlarmDto alarm(String event,String target) {
        return new AlarmDto(UUID.randomUUID().toString(),event,"PENDING_VERIFICATION","PREDICTED_AIRSPACE_ENTRY","HIGH",1000L,
                1000L,"source","live","org","district",target,"ALARM","来源","单位","区域","目标",
                "HIGH",List.of(),0,null,"CURRENT","CURRENT",null);
    }
    private record JdbcJson(String value) { @Override public String toString(){return value;} }
}
