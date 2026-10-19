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

    /** 天气风险的有效时段：画了范围的气象数据和天气预报规则生成的风险都有，后者没有范围。 */
    public record Window(long publishedAt, long validFrom, long validTo) { }

    /**
     * 天气预报规则生成的风险，时段记在预报事实里（预报自己带的时段）。
     * 以前只查 weather_risk_fact，这类风险就一直显示“缺少气象有效时段”（CDX-P06）。调用方先完成风险对象范围鉴权。
     */
    public Window findForecastWindow(String riskId) {
        return jdbc.query("""
            SELECT f.published_at,f.valid_from,f.valid_to FROM weather_forecast_risk_fact f JOIN flight_risk r ON r.risk_id=f.risk_id
            WHERE f.risk_id=? AND r.risk_type='WEATHER'
            """, (rs, index) -> new Window(rs.getTimestamp("published_at").getTime(), rs.getTimestamp("valid_from").getTime(),
                rs.getTimestamp("valid_to").getTime()), riskId).stream().findFirst().orElse(null);
    }

    public record ForecastFact(String messageId, String areaName, long publishedAt, long validFrom, long validTo,
            String summary, BigDecimal windSpeedMps, BigDecimal gustMps, Integer precipitationProbabilityPct,
            String ruleCode, String ruleVersion, String sourceMode) { }

    public record ForecastMap(List<List<Double>> polygon, BigDecimal windFromDegrees, BigDecimal precipitationRateMmH) { }

    /** Read only the original receipt and exact period frozen by this risk, never the latest district forecast. */
    public ForecastMap forecastMap(String riskId) {
        return jdbc.query("""
            SELECT m.payload,f.published_at,f.valid_from,f.valid_to
              FROM weather_forecast_risk_fact f JOIN flight_risk r ON r.risk_id=f.risk_id AND r.source_mode=f.source_mode
              JOIN local_interface_message m ON m.message_id=f.forecast_message_id AND m.kind='WEATHER_FORECAST' AND m.direction='IN'
             WHERE f.risk_id=? AND r.risk_type='WEATHER' AND r.source_id IN ('weather-forecast-rule-mock','weather-forecast-rule-replay')
            """, (rs,n) -> {
                try {
                    var payload=json.readTree(rs.getString("payload"));
                    if (payload.path("published_at").asLong(-1)!=rs.getTimestamp("published_at").getTime()) return null;
                    List<List<Double>> polygon=payload.hasNonNull("polygon") ? json.convertValue(payload.get("polygon"),new TypeReference<List<List<Double>>>() {}) : null;
                    com.uav.lowaltitude.modules.risk.domain.WeatherFootprint.validate(polygon);
                    for (var period:payload.path("periods")) {
                        if (period.path("from").asLong(-1)==rs.getTimestamp("valid_from").getTime()
                                && period.path("to").asLong(-1)==rs.getTimestamp("valid_to").getTime())
                            return new ForecastMap(polygon,number(period,"wind_direction_deg",360),number(period,"precipitation_rate_mm_h",1000));
                    }
                    return null;
                } catch (java.io.IOException | IllegalArgumentException ex) { throw new IllegalStateException("原始天气预报范围无法读取",ex); }
            },riskId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static BigDecimal number(com.fasterxml.jackson.databind.JsonNode period,String field,int max) {
        var value=period.path(field);
        if (!value.isNumber()) return null;
        var number=value.decimalValue();
        return number.signum()>=0 && number.compareTo(BigDecimal.valueOf(max))<=0 ? number : null;
    }

    /** 风险生成时存下的预报事实；不读取后续预报代替原依据。调用方先完成风险范围鉴权。 */
    public ForecastFact forecast(String riskId) {
        return jdbc.query("SELECT f.* FROM weather_forecast_risk_fact f JOIN flight_risk r ON r.risk_id=f.risk_id WHERE f.risk_id=? AND r.risk_type='WEATHER'",
            (rs,n) -> new ForecastFact(rs.getString("forecast_message_id"),rs.getString("area_name"),rs.getTimestamp("published_at").getTime(),
                rs.getTimestamp("valid_from").getTime(),rs.getTimestamp("valid_to").getTime(),rs.getString("summary"),rs.getBigDecimal("wind_speed_mps"),
                rs.getBigDecimal("gust_mps"),rs.getObject("precipitation_probability_pct",Integer.class),rs.getString("rule_code"),rs.getString("rule_version"),rs.getString("source_mode")),riskId)
                .stream().findFirst().orElse(null);
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
