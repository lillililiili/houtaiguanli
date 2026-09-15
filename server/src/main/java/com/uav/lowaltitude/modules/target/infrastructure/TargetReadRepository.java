package com.uav.lowaltitude.modules.target.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.fusion.domain.QualityFacts;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class TargetReadRepository {

    private static final String TARGET_FROM = """
            FROM target t
            LEFT JOIN target_latest_state ls ON ls.target_id=t.target_id
            LEFT JOIN app_org org_ref ON org_ref.org_id=t.owner_org_id
            LEFT JOIN app_district dist_ref ON dist_ref.district_id=t.district_id
            """;

    /**
     * 阶段 8：融合层轨迹 link_id 为空（决策 8-12），link/来源/设备三张表因此改为 LEFT JOIN。
     * 原始层必须保持阶段 2 的可见性语义——link 属于本目标、来源与目标同 source_mode、设备存在且同源同模式；
     * 少判其中任何一条，跨模式或错连的轨迹都会因为 LEFT JOIN 变成 NULL 行而被放行。
     */
    private static final String RAW_LAYER_LINK_VALID =
            " AND (tr.link_id IS NULL OR (l.link_id IS NOT NULL AND s.source_id IS NOT NULL"
                    + " AND (l.device_id IS NULL OR linked_device.device_id IS NOT NULL)))";

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;
    private final ObjectMapper objectMapper;

    public TargetReadRepository(JdbcTemplate jdbcTemplate, DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
        this.objectMapper = objectMapper;
    }

    public long countTargets(TargetQuery query, AccessDecision access) {
        Where where = targetWhere(query, access);
        return count("SELECT COUNT(*) " + TARGET_FROM + where.sql, where.parameters);
    }

    public List<TargetRow> listTargets(TargetQuery query, AccessDecision access, int offset, int size) {
        Where where = targetWhere(query, access);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(targetSelect() + TARGET_FROM + where.sql
                + " ORDER BY t.last_seen_at DESC NULLS LAST, t.target_id ASC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, this::targetRow);
    }

    public TargetRow findTarget(String targetId, AccessDecision access) {
        TargetQuery query = new TargetQuery(null, null, null, null, null, null, null, true);
        Where where = targetWhere(query, access);
        where.sql.append(" AND t.target_id=:target_id");
        where.parameters.put("target_id", targetId);
        List<TargetRow> rows = jdbc.query(targetSelect() + TARGET_FROM + where.sql,
                where.parameters, this::targetRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SourceLinkRow> sourceLinks(String targetId, AccessDecision access) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("target_id", targetId);
        StringBuilder sql = new StringBuilder("""
                SELECT l.link_id,l.source_id,s.source_code,s.source_mode,s.name AS source_name,l.source_session_key,
                       l.external_target_id,l.device_id,l.protocol_version,s.source_type,type_ref.schema_status
                FROM target_source_link l
                JOIN target t ON t.target_id=l.target_id
                JOIN integration_source s ON s.source_id=l.source_id AND s.source_mode=t.source_mode
                LEFT JOIN source_type_catalog type_ref ON type_ref.source_type=s.source_type
                LEFT JOIN device linked_device
                  ON linked_device.device_id=l.device_id
                 AND linked_device.source_id=l.source_id
                 AND linked_device.source_mode=t.source_mode
                """);
        appendScope(sql, parameters, access);
        sql.append(" AND l.target_id=:target_id")
                .append(" AND (l.device_id IS NULL OR linked_device.device_id IS NOT NULL)")
                .append(" ORDER BY s.source_code ASC,l.source_session_key ASC,l.external_target_id ASC,l.link_id ASC");
        return jdbc.query(sql.toString(), parameters, (rs, rowNum) -> new SourceLinkRow(
                rs.getString("link_id"), rs.getString("source_id"), rs.getString("source_code"),
                rs.getString("source_mode"), rs.getString("source_session_key"),
                rs.getString("external_target_id"), rs.getString("device_id"),
                rs.getString("protocol_version"), rs.getString("source_name"),
                rs.getString("source_type"), rs.getString("schema_status")));
    }

    public long countTracks(String targetId, TrackQuery query, AccessDecision access) {
        Where where = trackWhere(targetId, query, access);
        return count("SELECT COUNT(*) " + trackFrom() + where.sql, where.parameters);
    }

    public List<TrackRow> listTracks(
            String targetId, TrackQuery query, AccessDecision access, int offset, int size) {
        Where where = trackWhere(targetId, query, access);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query("""
                SELECT tr.track_id,tr.target_id,tr.link_id,tr.external_track_id,tr.started_at,
                       l.source_id,s.source_code,s.source_mode,l.device_id,tr.layer,tr.config_version,tr.ended_at
                """ + trackFrom() + where.sql
                + " ORDER BY tr.started_at DESC NULLS LAST,tr.track_id ASC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, (rs, rowNum) -> new TrackRow(
                rs.getString("track_id"), rs.getString("target_id"), rs.getString("link_id"),
                rs.getString("external_track_id"), rs.getString("source_id"),
                rs.getString("source_code"), rs.getString("source_mode"), rs.getString("device_id"),
                time(rs, "started_at"), rs.getString("layer"), rs.getString("config_version"), time(rs, "ended_at")));
    }

    public boolean accessibleValidTrack(String trackId, AccessDecision access) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("track_id", trackId);
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM track tr
                JOIN target t ON t.target_id=tr.target_id
                LEFT JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                LEFT JOIN integration_source s ON s.source_id=l.source_id AND s.source_mode=t.source_mode
                LEFT JOIN device linked_device
                  ON linked_device.device_id=l.device_id
                 AND linked_device.source_id=l.source_id
                 AND linked_device.source_mode=t.source_mode
                """);
        appendScope(sql, parameters, access);
        sql.append(" AND tr.track_id=:track_id");
        // 融合层轨迹没有 link（决策 8-12）：只有原始层需要校验 link、来源与设备的模式一致性。
        sql.append(RAW_LAYER_LINK_VALID);
        return count(sql.toString(), parameters) == 1;
    }

    public long countPoints(String trackId, TimeQuery query, AccessDecision access) {
        Where where = pointWhere(trackId, query, access);
        return count("SELECT COUNT(*) " + pointFrom() + where.sql, where.parameters);
    }

    public List<PointRow> listPoints(
            String trackId, TimeQuery query, AccessDecision access, int offset, int size) {
        Where where = pointWhere(trackId, query, access);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(pointSelect() + pointFrom() + where.sql
                + " ORDER BY COALESCE(p.observed_at,p.received_at) ASC,p.point_seq ASC,p.point_id ASC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, this::pointRow);
    }

    private Where targetWhere(TargetQuery query, AccessDecision access) {
        Where where = new Where();
        appendScope(where.sql, where.parameters, access);
        // 决策 16-6：被合并的目标按定义就是某个存活目标的别名，列表里不该有它自己的一行——
        // 否则用户在态势页上看到的仍是"同一架出现两次"，合并在库里做完了、在屏幕上没有。
        // 详情不过滤：告警、事件、风险里存的是旧 id，历史数据必须还能打开。
        if (!query.includeMerged) {
            where.sql.append(" AND NOT EXISTS (SELECT 1 FROM target_track_status merged_status"
                    + " WHERE merged_status.target_id=t.target_id AND merged_status.status='MERGE')");
        }
        if (query.ownerOrgId != null) add(where, "t.owner_org_id", "owner_org_id", query.ownerOrgId);
        if (query.districtId != null) add(where, "t.district_id", "district_id", query.districtId);
        if (query.objectTypeCode != null) add(where, "t.object_type_code", "object_type_code", query.objectTypeCode);
        if (query.seenFrom != null) {
            where.sql.append(" AND t.last_seen_at IS NOT NULL AND t.last_seen_at>=:seen_from AND t.last_seen_at<=:seen_to");
            where.parameters.put("seen_from", query.seenFrom);
            where.parameters.put("seen_to", query.seenTo);
        }
        if (query.sourceCode != null || query.deviceId != null) {
            where.sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM target_source_link filter_link
                         JOIN integration_source filter_source
                           ON filter_source.source_id=filter_link.source_id
                          AND filter_source.source_mode=t.source_mode
                         LEFT JOIN device filter_device
                           ON filter_device.device_id=filter_link.device_id
                          AND filter_device.source_id=filter_link.source_id
                          AND filter_device.source_mode=t.source_mode
                         WHERE filter_link.target_id=t.target_id
                           AND (filter_link.device_id IS NULL OR filter_device.device_id IS NOT NULL)
                    """);
            if (query.sourceCode != null) {
                where.sql.append(" AND filter_source.source_code=:source_code");
                where.parameters.put("source_code", query.sourceCode);
            }
            if (query.deviceId != null) {
                where.sql.append(" AND filter_link.device_id=:device_id");
                where.parameters.put("device_id", query.deviceId);
            }
            where.sql.append(')');
        }
        return where;
    }

    private Where trackWhere(String targetId, TrackQuery query, AccessDecision access) {
        Where where = new Where();
        appendScope(where.sql, where.parameters, access);
        where.sql.append(" AND tr.target_id=:target_id")
                // 融合层没有 link：只有原始层需要校验 link、来源与设备的模式一致性。
                // s.source_id IS NOT NULL 不可省：LEFT JOIN 后跨 source_mode 的来源会变成 NULL 行，漏掉它等于让跨模式轨迹可见。
                .append(RAW_LAYER_LINK_VALID);
        where.parameters.put("target_id", targetId);
        if (query.layer != null) add(where, "tr.layer", "layer", query.layer);
        if (query.sourceCode != null) add(where, "s.source_code", "source_code", query.sourceCode);
        if (query.deviceId != null) add(where, "l.device_id", "device_id", query.deviceId);
        if (query.startedFrom != null) {
            where.sql.append(" AND tr.started_at IS NOT NULL AND tr.started_at>=:started_from AND tr.started_at<=:started_to");
            where.parameters.put("started_from", query.startedFrom);
            where.parameters.put("started_to", query.startedTo);
        }
        return where;
    }

    /**
     * 阶段 8：融合层轨迹 link_id 为空（决策 8-12），因此 link/source 三张表改为 LEFT JOIN。
     * 原始层的可见性条件（同 source_mode、设备存在）用 tr.link_id IS NULL 保护，语义与阶段 2 完全一致。
     */
    private static String trackFrom() {
        return """
                 FROM track tr
                 JOIN target t ON t.target_id=tr.target_id
                 LEFT JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                 LEFT JOIN integration_source s ON s.source_id=l.source_id AND s.source_mode=t.source_mode
                 LEFT JOIN device linked_device
                   ON linked_device.device_id=l.device_id
                  AND linked_device.source_id=l.source_id
                  AND linked_device.source_mode=t.source_mode
                """;
    }

    private static String pointFrom() {
        return """
                 FROM track_point p
                 JOIN track tr ON tr.track_id=p.track_id
                 JOIN target t ON t.target_id=tr.target_id
                 LEFT JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                 LEFT JOIN integration_source s ON s.source_id=l.source_id AND s.source_mode=t.source_mode
                 LEFT JOIN device linked_device
                   ON linked_device.device_id=l.device_id
                  AND linked_device.source_id=l.source_id
                  AND linked_device.source_mode=t.source_mode
                """;
    }

    private static Where pointWhere(String trackId, TimeQuery query, AccessDecision access) {
        Where where = new Where();
        appendScope(where.sql, where.parameters, access);
        where.sql.append(" AND p.track_id=:track_id").append(RAW_LAYER_LINK_VALID);
        where.parameters.put("track_id", trackId);
        if (query.kinds != null && !query.kinds.isEmpty()) {
            where.sql.append(" AND p.point_kind IN (:point_kinds)");
            where.parameters.put("point_kinds", query.kinds);
        }
        if (query.timeFrom != null) {
            where.sql.append(" AND COALESCE(p.observed_at,p.received_at)>=:time_from"
                    + " AND COALESCE(p.observed_at,p.received_at)<=:time_to");
            where.parameters.put("time_from", query.timeFrom);
            where.parameters.put("time_to", query.timeTo);
        }
        return where;
    }

    private static void appendScope(StringBuilder sql, Map<String, Object> parameters, AccessDecision access) {
        sql.append(" WHERE t.owner_org_id IS NOT NULL AND t.district_id IS NOT NULL");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM app_user_data_scope granted_scope
                         JOIN app_org scope_org ON scope_org.org_id=granted_scope.org_id AND scope_org.enabled=TRUE
                         JOIN app_district scope_district
                           ON scope_district.district_id=granted_scope.district_id AND scope_district.enabled=TRUE
                         WHERE granted_scope.user_id=:scope_user_id
                           AND granted_scope.org_id=t.owner_org_id
                           AND granted_scope.district_id=t.district_id
                     )
                    """);
            parameters.put("scope_user_id", access.userId());
        }
    }

    private String targetSelect() {
        return """
                SELECT t.target_id,t.target_no,t.object_type_code,t.subtype,t.uav_sn,
                       t.first_seen_at,t.last_seen_at,t.source_mode,t.owner_org_id,t.district_id,
                       org_ref.name AS owner_org_name,dist_ref.name AS district_name,t.version AS target_version,
                       t.created_at,t.updated_at,ls.observed_at AS state_observed_at,
                       ls.received_at AS state_received_at,ls.altitude_amsl_m,ls.height_agl_m,
                       ls.speed_mps,ls.heading_deg,ls.classification_confidence,ls.fusion_confidence,
                       ls.unknown_fields,
                """ + locationColumns("ls.location", "") + "," + locationColumns("ls.pilot_location", "pilot_");
    }

    private String pointSelect() {
        return """
                SELECT p.point_id,p.track_id,p.point_seq,p.observed_at,p.received_at,
                       p.altitude_amsl_m,p.height_agl_m,p.point_kind,p.position_accuracy_m,p.contributing,
                       p.source_switched,p.degradation_level,
                """ + locationColumns("p.location", "");
    }

    /** alias 前缀区分同一行里的多个几何列（目标位置与飞手位置）。 */
    private String locationColumns(String column, String alias) {
        if (postgis) {
            return "CASE WHEN " + column + " IS NOT NULL AND ST_SRID(" + column + ")=4326"
                    + " AND ST_X(" + column + ") BETWEEN -180 AND 180"
                    + " AND ST_Y(" + column + ") BETWEEN -90 AND 90 THEN ST_X(" + column + ") END AS " + alias + "longitude,"
                    + "CASE WHEN " + column + " IS NOT NULL AND ST_SRID(" + column + ")=4326"
                    + " AND ST_X(" + column + ") BETWEEN -180 AND 180"
                    + " AND ST_Y(" + column + ") BETWEEN -90 AND 90 THEN ST_Y(" + column + ") END AS " + alias + "latitude,"
                    + "CASE WHEN " + column + " IS NOT NULL THEN ST_SRID(" + column + ") END AS " + alias + "location_srid,"
                    + "CAST(NULL AS VARCHAR) AS " + alias + "location_text ";
        }
        return "CAST(NULL AS NUMERIC) AS " + alias + "longitude,CAST(NULL AS NUMERIC) AS " + alias + "latitude,"
                + "CAST(NULL AS INTEGER) AS " + alias + "location_srid,CAST(" + column + " AS VARCHAR) AS " + alias + "location_text ";
    }

    private TargetRow targetRow(ResultSet rs, int rowNum) throws SQLException {
        return new TargetRow(
                rs.getString("target_id"), rs.getString("target_no"), rs.getString("object_type_code"),
                rs.getString("subtype"), rs.getString("uav_sn"), time(rs, "first_seen_at"),
                time(rs, "last_seen_at"), rs.getString("source_mode"), rs.getString("owner_org_id"),
                rs.getString("district_id"), time(rs, "created_at"), time(rs, "updated_at"),
                time(rs, "state_observed_at"), time(rs, "state_received_at"),
                location(rs, ""), rs.getBigDecimal("altitude_amsl_m"), rs.getBigDecimal("height_agl_m"),
                rs.getBigDecimal("speed_mps"), rs.getBigDecimal("heading_deg"),
                rs.getBigDecimal("classification_confidence"), rs.getBigDecimal("fusion_confidence"),
                normalizedJson(rs.getString("unknown_fields")), rs.getString("owner_org_name"), rs.getString("district_name"),
                rs.getObject("target_version") == null ? null : rs.getLong("target_version"), location(rs, "pilot_"));
    }

    private PointRow pointRow(ResultSet rs, int rowNum) throws SQLException {
        return new PointRow(rs.getString("point_id"), rs.getString("track_id"), rs.getLong("point_seq"),
                time(rs, "observed_at"), time(rs, "received_at"), location(rs, ""),
                rs.getBigDecimal("altitude_amsl_m"), rs.getBigDecimal("height_agl_m"),
                rs.getString("point_kind"), rs.getBigDecimal("position_accuracy_m"),
                jsonTextOf(rs.getObject("contributing")), (Boolean) rs.getObject("source_switched"), rs.getString("degradation_level"));
    }

    private static Coordinate location(ResultSet rs, String alias) throws SQLException {
        BigDecimal longitude = rs.getBigDecimal(alias + "longitude");
        BigDecimal latitude = rs.getBigDecimal(alias + "latitude");
        if (longitude != null && latitude != null) return trusted(longitude, latitude);
        String value = rs.getString(alias + "location_text");
        if (value == null) return null;
        int point = value.toUpperCase().indexOf("POINT");
        int open = value.indexOf('(', point);
        int close = value.indexOf(')', open);
        if (point < 0 || open < 0 || close < 0 || !value.substring(0, point).contains("4326")) return null;
        String[] coordinates = value.substring(open + 1, close).trim().split("\\s+");
        if (coordinates.length != 2) return null;
        try {
            return trusted(new BigDecimal(coordinates[0]), new BigDecimal(coordinates[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Coordinate trusted(BigDecimal longitude, BigDecimal latitude) {
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) return null;
        return new Coordinate(longitude, latitude);
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    private String normalizedJson(String value) {
        if (postgis || value == null) return value;
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && parsed.isTextual() ? parsed.asText() : value;
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void add(Where where, String column, String name, Object value) {
        where.sql.append(" AND ").append(column).append("=:").append(name);
        where.parameters.put(name, value);
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof ZonedDateTime time) return time.toOffsetDateTime();
        if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        if (value instanceof Number time) return Instant.ofEpochMilli(time.longValue()).atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> parameters = new HashMap<>();
    }

    public record TargetQuery(String sourceCode, String deviceId, String objectTypeCode,
            OffsetDateTime seenFrom, OffsetDateTime seenTo, String ownerOrgId, String districtId,
            boolean includeMerged) {
    }

    public record TrackQuery(OffsetDateTime startedFrom, OffsetDateTime startedTo,
            String sourceCode, String deviceId, String layer) {
    }

    public record TimeQuery(OffsetDateTime timeFrom, OffsetDateTime timeTo, java.util.List<String> kinds) {
    }

    public record Coordinate(BigDecimal longitude, BigDecimal latitude) {
    }

    /* ---- 阶段 15：悬浮卡三摘要与 AOA 方位（决策 15-4 / 15-5）---- */

    /**
     * 按**整页**一次取回，而不是在主查询里挂相关子查询。
     *
     * 两者都不是 N+1（每页固定四条查询），但批量版不会给 TargetRow 再塞十几个扁平列——
     * 那个记录已经有二十多个字段，再加会让"这一行到底代表什么"彻底看不出来。
     * 每段都只取**最新一条**：悬浮卡要回答的是"现在怎么样"，不是历史（决策 15-4）。
     */
    public Map<String, TargetSummariesRow> summaries(List<String> targetIds) {
        Map<String, TargetSummariesRow> result = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) return result;
        Map<String, Object> params = Map.of("ids", targetIds);
        Map<String, RiskSummaryRow> risks = new HashMap<>();
        // 排序键用 COALESCE(occurred_at, received_at)（决策 15-21）：`flight_risk.occurred_at` **可空**，
        // 而元组比较里只要有一侧是 NULL，整个比较就返回 NULL 而不是真——于是两行都满足 NOT EXISTS，
        // 两条风险都被写进 map，最终留下哪一条取决于结果集顺序，悬浮卡上的风险等级会在刷新之间跳变。
        // 另外三段（研判 created_at、处置 requested_at、观测 observed_at）的排序列都是非空，不受这条影响。
        jdbc.query("SELECT r.target_id,r.risk_id,r.severity,r.state_code,r.occurred_at FROM flight_risk r"
                + " WHERE r.target_id IN (:ids) AND NOT EXISTS (SELECT 1 FROM flight_risk n WHERE n.target_id=r.target_id"
                + "   AND (COALESCE(n.occurred_at, n.received_at), n.risk_id)"
                + "     > (COALESCE(r.occurred_at, r.received_at), r.risk_id))", params,
                rs -> { risks.put(rs.getString("target_id"), new RiskSummaryRow(rs.getString("risk_id"),
                        rs.getString("severity"), rs.getString("state_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class))); });
        Map<String, LegalitySummaryRow> legality = new HashMap<>();
        jdbc.query("SELECT e.target_id,e.evaluation_id,e.legal_status,e.grade,e.violation_reasons FROM rule_evaluation e"
                + " WHERE e.target_id IN (:ids) AND NOT EXISTS (SELECT 1 FROM rule_evaluation n"
                + "   WHERE n.target_id=e.target_id AND (n.created_at, n.evaluation_id) > (e.created_at, e.evaluation_id))",
                params, rs -> { legality.put(rs.getString("target_id"), new LegalitySummaryRow(
                        rs.getString("evaluation_id"), rs.getString("legal_status"), rs.getString("grade"),
                        jsonTextOf(rs.getObject("violation_reasons")))); });
        Map<String, DisposalSummaryRow> disposals = new HashMap<>();
        jdbc.query("SELECT d.target_id,d.authorization_id,d.authorization_no,d.action_type,d.status"
                + " FROM disposal_authorization d WHERE d.target_id IN (:ids)"
                + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization n WHERE n.target_id=d.target_id"
                + "   AND (n.requested_at, n.authorization_id) > (d.requested_at, d.authorization_id))", params,
                rs -> { disposals.put(rs.getString("target_id"), new DisposalSummaryRow(
                        rs.getString("authorization_id"), rs.getString("authorization_no"),
                        rs.getString("action_type"), rs.getString("status"))); });
        // 方位：只看**无位置但带方位**的观测（决策 15-5）。有位置的目标画点就够了，方位线是给"只知道方向"的那些准备的。
        //
        // 不用 `quality ->> 'bearing_deg'`：那是 PostgreSQL 独有的写法，H2 直接语法错误，
        // 而这条查询在单测库与生产库上必须是同一条。改成 SQL 里做可移植的粗筛（LIKE 键名），
        // 取值交给 Java 解析——与仓库里其它 JSON 列的读法一致。
        Map<String, BearingRow> bearings = new HashMap<>();
        jdbc.query("SELECT l.target_id, o.device_id, o.quality FROM source_observation o"
                + " JOIN target_source_link l ON l.source_id=o.source_id"
                + "   AND l.external_target_id=o.external_target_id"
                + " WHERE l.target_id IN (:ids) AND o.location IS NULL AND " + QualityFacts.mentionsBearing("o.quality")
                + " AND NOT EXISTS (SELECT 1 FROM source_observation n"
                + "   JOIN target_source_link nl ON nl.source_id=n.source_id AND nl.external_target_id=n.external_target_id"
                + "   WHERE nl.target_id=l.target_id AND n.location IS NULL AND " + QualityFacts.mentionsBearing("n.quality")
                + "   AND (n.observed_at > o.observed_at"
                + "        OR (n.observed_at = o.observed_at AND n.observation_id > o.observation_id)))", params,
                rs -> {
                    BigDecimal bearing = QualityFacts.bearingDeg(jsonTextOf(rs.getObject("quality")));
                    // 粗筛只保证字符串里出现过这个键；真取不到值就不给方位，而不是画一条方向不明的线。
                    if (bearing != null) {
                        bearings.put(rs.getString("target_id"), new BearingRow(bearing, rs.getString("device_id")));
                    }
                });
        for (String targetId : targetIds) {
            result.put(targetId, new TargetSummariesRow(risks.get(targetId), legality.get(targetId),
                    disposals.get(targetId), bearings.get(targetId)));
        }
        return result;
    }

    public record TargetSummariesRow(RiskSummaryRow risk, LegalitySummaryRow legality, DisposalSummaryRow disposal,
            BearingRow bearing) { }
    public record RiskSummaryRow(String riskId, String severity, String state, OffsetDateTime occurredAt) { }
    public record LegalitySummaryRow(String evaluationId, String legalStatus, String grade, String violationReasonsJson) { }
    public record DisposalSummaryRow(String authorizationId, String authorizationNo, String actionType, String status) { }
    public record BearingRow(BigDecimal bearingDeg, String deviceId) { }

    public record TargetRow(String targetId, String targetNo, String objectTypeCode, String subtype,
            String uavSn, OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt, String sourceMode,
            String ownerOrgId, String districtId, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime stateObservedAt, OffsetDateTime stateReceivedAt, Coordinate location,
            BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
            BigDecimal classificationConfidence, BigDecimal fusionConfidence, String unknownFields,
            String ownerOrgName, String districtName, Long version,
            /* 阶段 8.5：融合层写入的飞手位置，可空。 */
            Coordinate pilotLocation) {
    }

    public record SourceLinkRow(String linkId, String sourceId, String sourceCode, String sourceMode,
            String sourceSessionKey, String externalTargetId, String deviceId, String protocolVersion, String sourceName,
            String sourceType, String schemaStatus) {
    }

    public record TrackRow(String trackId, String targetId, String linkId, String externalTrackId,
            String sourceId, String sourceCode, String sourceMode, String deviceId, OffsetDateTime startedAt,
            String layer, String configVersion, OffsetDateTime endedAt) {
    }

    public record PointRow(String pointId, String trackId, long pointSeq, OffsetDateTime observedAt,
            OffsetDateTime receivedAt, Coordinate location, BigDecimal altitudeAmslM, BigDecimal heightAglM,
            String pointKind, BigDecimal positionAccuracyM, String contributingJson, Boolean sourceSwitched, String degradationLevel) {
    }

    /** JSON 列在 H2 上回读为 byte[]，PostgreSQL 为文本；统一成文本交由应用层解析。 */
    private static String jsonTextOf(Object stored) {
        if (stored == null) return null;
        if (stored instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return String.valueOf(stored);
    }
}
