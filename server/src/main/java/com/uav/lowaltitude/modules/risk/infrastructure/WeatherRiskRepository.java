package com.uav.lowaltitude.modules.risk.infrastructure;

import java.sql.Timestamp;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WeatherRiskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WeatherRiskRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record FactRow(List<List<Double>> polygon, long publishedAt, long validFrom, long validTo,
            BigDecimal windSpeedMps, BigDecimal windFromDegrees, BigDecimal visibilityM, String sourceMode) { }

    /** 调用方先完成风险对象范围鉴权；不提供独立的无鉴权 HTTP 入口。 */
    public FactRow find(String riskId) {
        return jdbc.query("""
            SELECT f.*, r.source_mode FROM weather_risk_fact f JOIN flight_risk r ON r.risk_id=f.risk_id
            WHERE f.risk_id=? AND r.risk_type='WEATHER'
            """, (rs, index) -> {
                try {
                    return new FactRow(json.readValue(rs.getString("polygon_json"), new TypeReference<>() {}),
                        rs.getTimestamp("published_at").getTime(), rs.getTimestamp("valid_from").getTime(), rs.getTimestamp("valid_to").getTime(),
                        rs.getBigDecimal("wind_speed_mps"), rs.getBigDecimal("wind_from_degrees"), rs.getBigDecimal("visibility_m"), rs.getString("source_mode"));
                } catch (java.io.IOException e) { throw new IllegalStateException("气象范围数据无法读取", e); }
            }, riskId).stream().findFirst().orElse(null);
    }

    /** 只追加专属模拟源的范围快照，重启不改已有风险或历史范围。 */
    public void insertDemo(String riskId, FactRow fact) {
        try {
            jdbc.update("""
                INSERT INTO weather_risk_fact(risk_id,polygon_json,published_at,valid_from,valid_to,wind_speed_mps,wind_from_degrees,visibility_m)
                SELECT risk_id,?,?,?,?,?,?,? FROM flight_risk
                WHERE risk_id=? AND source_id='seed-weather-demo' AND source_mode='mock' AND risk_type='WEATHER'
                  AND NOT EXISTS(SELECT 1 FROM weather_risk_fact WHERE risk_id=?)
                """, json.writeValueAsString(fact.polygon()), new Timestamp(fact.publishedAt()), new Timestamp(fact.validFrom()),
                    new Timestamp(fact.validTo()), fact.windSpeedMps(), fact.windFromDegrees(), fact.visibilityM(), riskId, riskId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("气象范围数据无法保存", e); }
    }
}
