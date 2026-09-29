package com.uav.lowaltitude.modules.integrationconfig.infrastructure;

import java.sql.Timestamp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalWeatherRiskController.Input;

@Repository
public class LocalWeatherRiskRepository {
    public static final String SOURCE="qa-weather-risk-input";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LocalWeatherRiskRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public void ensureSource(long now) {
        // One stable configuration row serializes first registration across different operators.
        jdbc.queryForObject("SELECT kind FROM external_interface_config WHERE kind='WEATHER_FORECAST' FOR UPDATE",String.class);
        jdbc.update("""
            INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)
            SELECT ?,'QA_WEATHER_RISK_INPUT','本地QA气象风险输入',TRUE,'mock',?,?,0
            WHERE NOT EXISTS(SELECT 1 FROM integration_source WHERE source_id=?)
            """,SOURCE,new Timestamp(now),new Timestamp(now),SOURCE);
    }
    public void insertFact(String risk,Input input) {
        try {
            int inserted=jdbc.update("""
                INSERT INTO weather_risk_fact(risk_id,polygon_json,published_at,valid_from,valid_to,wind_speed_mps,wind_from_degrees,visibility_m)
                SELECT risk_id,?,?,?,?,?,?,? FROM flight_risk
                WHERE risk_id=? AND source_id=? AND source_mode='mock' AND risk_type='WEATHER'
                """,json.writeValueAsString(input.polygon()),new Timestamp(input.publishedAt()),new Timestamp(input.validFrom()),
                new Timestamp(input.validTo()),input.windSpeedMps(),input.windFromDegrees(),input.visibilityM(),risk,SOURCE);
            if(inserted!=1)throw new IllegalStateException("QA气象风险范围保存失败");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
