package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * fusion_event 摘要 payload 的只读上下文（契约 §4/§8，决策 10-1）：目标编号、当前是否有未关闭的无人机事件、
 * 未关闭飞行风险的最高等级、以及 target_latest_state 里保留的飞手位置。
 * 只有 SELECT：告警/风险的状态机归 alarm/risk 模块，融合层只读它们的现状写进事件摘要，绝不改写。
 * 每个方法只在真的要发事件时才被调用（STABLE 首次、UNDETERMINED 转入），不在每帧路径上。
 */
@Repository
public class FusionEventContextRepository {
    /**
     * uav_event 的"未关闭"状态与 alarm 模块 UavEventState.OPEN 一致（PENDING_VERIFICATION）；
     * CONFIRMED / FALSE_POSITIVE 是核实结论，事件到此关闭。这里复制一份而不是引用，是为了不让融合层依赖 alarm 模块内部类。
     */
    static final Set<String> OPEN_UAV_EVENT_STATES = Set.of("PENDING_VERIFICATION");
    /** flight_risk 的"未关闭"状态：待核验 / 待通知；NOTIFIED（已交接）与 EXCLUDED（已排除）都不再算当前风险。 */
    static final Set<String> OPEN_RISK_STATES = Set.of("PENDING_VERIFICATION", "PENDING_NOTIFICATION");
    /** 迁移 022 的 ck_stage4_risk_severity 字典，按严重程度升序；取最高等级时按此排序而不是按字符串比较。 */
    static final List<String> RISK_SEVERITY_ASCENDING = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final NamedParameterJdbcTemplate jdbc;
    /** PostgreSQL 上 CAST(geometry AS VARCHAR) 是 EWKB 十六进制而非 'POINT(x y)'，必须走 ST_X/ST_Y；H2 用 CAST 后解析文本。 */
    private final boolean postgis;

    public FusionEventContextRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            return false;
        }
    }

    /** 目标展示编号（target.target_no）；目标行不存在时返回 null，由调用方省略该键。 */
    public String targetNo(String targetId) {
        List<String> rows = jdbc.queryForList("SELECT target_no FROM target WHERE target_id=:t", Map.of("t", targetId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 目标行当前的 object_type_code（决策 10-11：融合属性优选选不出类别时，事件摘要的 class_code 回退到它）。
     * 目标行不存在或该列为空返回 null，由调用方省略该键。只读，不改 target。
     */
    public String objectTypeCode(String targetId) {
        List<String> rows = jdbc.queryForList("SELECT object_type_code FROM target WHERE target_id=:t", Map.of("t", targetId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该目标是否有未关闭的无人机事件：uav_event 经 alarm.target_id 挂到目标。 */
    public boolean alarmActive(String targetId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id"
                + " WHERE a.target_id=:t AND e.state_code IN (:open)", Map.of("t", targetId, "open", OPEN_UAV_EVENT_STATES), Long.class);
        return count != null && count > 0;
    }

    /** 该目标未关闭飞行风险中的最高 severity；没有未关闭风险时返回 null（调用方不出键，不写 null）。 */
    public String maxOpenRiskSeverity(String targetId) {
        List<String> severities = jdbc.queryForList("SELECT severity FROM flight_risk WHERE target_id=:t AND state_code IN (:open)",
                Map.of("t", targetId, "open", OPEN_RISK_STATES), String.class);
        return severities.stream()
                .filter(RISK_SEVERITY_ASCENDING::contains)
                .max(Comparator.comparingInt(RISK_SEVERITY_ASCENDING::indexOf))
                .orElse(null);
    }

    /**
     * target_latest_state 里当前保留的飞手位置（决策 8.5-27：没有身份主源的帧不改写这两列，所以本帧写入的记录里可能没有它）。
     * 无行或列为空返回 null。
     */
    public double[] retainedPilotLocation(String targetId) {
        String columns = postgis ? "ST_X(pilot_location) AS lon, ST_Y(pilot_location) AS lat, NULL AS pilot_text" : "NULL AS lon, NULL AS lat, CAST(pilot_location AS VARCHAR) AS pilot_text";
        List<double[]> rows = jdbc.query("SELECT " + columns + " FROM target_latest_state WHERE target_id=:t", Map.of("t", targetId), (rs, i) -> {
            if (postgis) {
                double lon = rs.getDouble("lon");
                if (rs.wasNull()) return null;
                double lat = rs.getDouble("lat");
                return rs.wasNull() ? null : new double[] { lon, lat };
            }
            String text = rs.getString("pilot_text");
            return text == null ? null : parsePoint(text);
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 解析 H2 回读的 'SRID=4326;POINT (lon lat)' 文本；不是点文本时返回 null，不猜坐标。 */
    static double[] parsePoint(String text) {
        int open = text.indexOf('('), close = text.indexOf(')');
        if (open < 0 || close < open) return null;
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length != 2) return null;
        try { return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) }; }
        catch (NumberFormatException ex) { return null; }
    }
}
