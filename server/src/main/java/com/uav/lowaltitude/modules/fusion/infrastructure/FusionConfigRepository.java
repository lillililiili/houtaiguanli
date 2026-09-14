package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** fusion_config 读写：参数 JSON 原样取出（H2 读出 byte[]，PostgreSQL 读出文本），由应用层解析。 */
@Repository
public class FusionConfigRepository {
    private static final String SELECT = "SELECT config_version,status,schema_status,params,note,created_by,created_at,activated_at,version FROM fusion_config";
    private final NamedParameterJdbcTemplate jdbc;

    public FusionConfigRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public ConfigRow findActive() {
        List<ConfigRow> rows = jdbc.query(SELECT + " WHERE status='ACTIVE' ORDER BY activated_at DESC, config_version DESC", Map.of(), FusionConfigRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ConfigRow find(String version) {
        List<ConfigRow> rows = jdbc.query(SELECT + " WHERE config_version=:v", Map.of("v", version), FusionConfigRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 激活前锁目标版本行；当前 ACTIVE 行随后也锁定，两者按 config_version 排序加锁避免死锁。 */
    public ConfigRow lock(String version) {
        List<ConfigRow> rows = jdbc.query(SELECT + " WHERE config_version=:v FOR UPDATE", Map.of("v", version), FusionConfigRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ConfigRow> listAll() {
        return jdbc.query(SELECT + " ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END, created_at DESC, config_version DESC", Map.of(), FusionConfigRepository::row);
    }

    /** 条件更新：version 不符即 0 行，调用方按 VERSION_CONFLICT 处理，不会把状态写到错误的版本上。 */
    public int activate(String version, long expectedVersion, OffsetDateTime activatedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("v", version); p.put("expected", expectedVersion); p.put("at", activatedAt);
        return jdbc.update("UPDATE fusion_config SET status='ACTIVE', activated_at=:at, version=version+1 WHERE config_version=:v AND version=:expected", p);
    }

    /** 旧 ACTIVE→RETIRED 与新版本激活在同一事务内完成；PostgreSQL 的单 ACTIVE 部分唯一索引兜底并发。 */
    public int retireActive(String exceptVersion) {
        return jdbc.update("UPDATE fusion_config SET status='RETIRED', version=version+1 WHERE status='ACTIVE' AND config_version<>:v", Map.of("v", exceptVersion));
    }

    private static ConfigRow row(ResultSet rs, int ignored) throws SQLException {
        return new ConfigRow(rs.getString("config_version"), rs.getString("status"), rs.getString("schema_status"), jsonText(rs.getObject("params")),
                rs.getString("note"), rs.getString("created_by"), time(rs, "created_at"), time(rs, "activated_at"), rs.getLong("version"));
    }

    /** JSON 列在 H2 上经 JDBC 取回是 byte[]，PostgreSQL 是 PGobject/String；统一成文本。 */
    public static String jsonText(Object stored) {
        if (stored == null) return null;
        if (stored instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(stored);
    }

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t;
        if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    public record ConfigRow(String configVersion, String status, String schemaStatus, String paramsJson, String note, String createdBy,
            OffsetDateTime createdAt, OffsetDateTime activatedAt, long version) { }
}
