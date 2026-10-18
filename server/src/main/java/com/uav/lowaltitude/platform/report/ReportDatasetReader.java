package com.uav.lowaltitude.platform.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import static com.uav.lowaltitude.platform.report.BusinessReportSource.*;

/** Common bounded SQL aggregation; it neither grants access nor changes business state. */
@Component
public class ReportDatasetReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgres;
    public ReportDatasetReader(JdbcTemplate template) {
        jdbc = new NamedParameterJdbcTemplate(template);
        postgres = Boolean.TRUE.equals(template.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().contains("PostgreSQL")));
    }
    public String epoch(String column) {
        // H2 DATEDIFF ignores timestamp offsets; EXTRACT(EPOCH) respects the instant on both databases.
        return "CAST(FLOOR(EXTRACT(EPOCH FROM " + column + ") * 1000) AS BIGINT)";
    }
    public static Dataset window(String sql, Map<String,Object> parameters, Range range, String time) {
        Map<String,Object> p = new HashMap<>(parameters);
        p.put("report_start", range.start()); p.put("report_end", range.end());
        return new Dataset(sql + " AND " + time + ">=:report_start AND " + time + "<:report_end", p);
    }
    private String from(Dataset data, SourceScope scope) {
        String predicate = switch (scope) {
            case LIVE -> "source_mode='live'";
            case SIMULATED -> "source_mode IN ('mock','replay')";
        };
        return " FROM (SELECT * FROM (" + data.sql() + ") source_rows WHERE " + predicate + ") report_rows";
    }
    private long count(Dataset data, SourceScope scope) {
        Long n = jdbc.queryForObject("SELECT COUNT(*)" + from(data, scope), data.parameters(), Long.class);
        return n == null ? 0 : n;
    }
    public Summary summarize(Dataset data, Range range, String key, String title, String basis,
            boolean snapshot, List<Dimension> dimensions) {
        List<Distribution> distributions = new ArrayList<>();
        SourceScope scope = range.sourceScope();
        for (Dimension d : dimensions) distributions.add(new Distribution(d.key(), d.title(), group(data, d.key(), scope)));
        distributions.add(new Distribution("region", "区域分布", group(data, "region", scope)));
        List<Day> days = new ArrayList<>();
        if (!snapshot) {
            String date = postgres ? "CAST(TO_TIMESTAMP(at_ms / 1000.0) AT TIME ZONE 'Asia/Shanghai' AS DATE)"
                    : "CAST(DATEADD('MILLISECOND', at_ms + 28800000, TIMESTAMP '1970-01-01 00:00:00') AS DATE)";
            Map<String,Long> counts = new HashMap<>();
            jdbc.query("SELECT " + date + " AS day_key, COUNT(*) AS n" + from(data, scope)
                    + " GROUP BY " + date, data.parameters(), rs -> {
                        counts.put(rs.getString("day_key"), rs.getLong("n"));
                    });
            for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1))
                days.add(new Day(day.toString(), counts.getOrDefault(day.toString(), 0L)));
        }
        return new Summary(key, title, basis, snapshot, true, count(data, scope), days, distributions, group(data, "source_mode", scope));
    }
    private List<Count> group(Dataset data, String column, SourceScope scope) {
        if (!Set.of("state", "kind", "severity", "region", "result", "label", "source_mode").contains(column))
            throw new IllegalArgumentException("Unknown report dimension");
        String value = "COALESCE(NULLIF(" + column + ", ''), 'UNKNOWN')";
        // Device ranking is intentionally Top 10; other distributions remain complete.
        String limit = column.equals("label") ? " FETCH FIRST 10 ROWS ONLY" : "";
        return jdbc.query("SELECT " + value + " AS name,COUNT(*) AS n" + from(data, scope)
                + " GROUP BY " + value + " ORDER BY n DESC,name ASC" + limit, data.parameters(),
                (rs, i) -> new Count(rs.getString("name"), rs.getLong("n")));
    }
    /** 全部明细行（来源口径与 details 相同、顺序相同），供需要逐条按业务服务推导状态的报表使用。 */
    public List<Row> rows(Dataset data, SourceScope scope) {
        return jdbc.query("SELECT *" + from(data, scope) + " ORDER BY at_ms DESC NULLS LAST,id DESC", data.parameters(), ROW);
    }
    /**
     * 与 summarize 同一统计口径，但在 Java 中对已推导好的行聚合：每日趋势按上海时区日期、分布按值计数降序再按名称升序，
     * 设备排行仍只取前 10。行须已按 rows() 取出（即已过来源口径）。
     */
    public static Summary summarizeRows(List<Row> rows, Range range, String key, String title, String basis,
            boolean snapshot, List<Dimension> dimensions) {
        List<Distribution> distributions = new ArrayList<>();
        for (Dimension d : dimensions) distributions.add(new Distribution(d.key(), d.title(), groupRows(rows, d.key())));
        distributions.add(new Distribution("region", "区域分布", groupRows(rows, "region")));
        List<Day> days = new ArrayList<>();
        if (!snapshot) {
            Map<String,Long> counts = new HashMap<>();
            for (Row row : rows) if (row.occurredAt() != null)
                counts.merge(java.time.Instant.ofEpochMilli(row.occurredAt()).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString(), 1L, Long::sum);
            for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1))
                days.add(new Day(day.toString(), counts.getOrDefault(day.toString(), 0L)));
        }
        return new Summary(key, title, basis, snapshot, true, (long) rows.size(), days, distributions, groupRows(rows, "source_mode"));
    }
    public static Page pageRows(List<Row> rows, int page, int size) {
        int from = (int) Math.min(rows.size(), (long) (page - 1) * size);
        return new Page(List.copyOf(rows.subList(from, Math.min(rows.size(), from + size))), page, size, rows.size());
    }
    private static List<Count> groupRows(List<Row> rows, String column) {
        Map<String,Long> counts = new HashMap<>();
        for (Row row : rows) {
            String value = switch (column) {
                case "state" -> row.state(); case "kind" -> row.kind(); case "severity" -> row.severity();
                case "region" -> row.region(); case "result" -> row.result(); case "label" -> row.label();
                case "source_mode" -> row.sourceMode();
                default -> throw new IllegalArgumentException("Unknown report dimension");
            };
            counts.merge(value == null || value.isEmpty() ? "UNKNOWN" : value, 1L, Long::sum);
        }
        var stream = counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey()))
                .map(e -> new Count(e.getKey(), e.getValue()));
        return (column.equals("label") ? stream.limit(10) : stream).toList();
    }
    private static final org.springframework.jdbc.core.RowMapper<Row> ROW = (rs, i) -> new Row(rs.getString("id"), rs.getString("label"),
            rs.getObject("at_ms", Long.class), rs.getString("state"), rs.getString("kind"), rs.getString("severity"), rs.getString("region"),
            rs.getString("source_mode"), rs.getString("related"), rs.getString("result"), rs.getString("note"));
    public Page details(Dataset data, int page, int size) {
        return details(data, page, size, SourceScope.LIVE);
    }
    public Page details(Dataset data, int page, int size, SourceScope scope) {
        Map<String,Object> p = new HashMap<>(data.parameters());
        p.put("report_offset", (long)(page - 1) * size); p.put("report_size", size);
        List<Row> rows = jdbc.query("SELECT *" + from(data, scope)
                + " ORDER BY at_ms DESC NULLS LAST,id DESC OFFSET :report_offset ROWS FETCH NEXT :report_size ROWS ONLY", p, ROW);
        return new Page(rows, page, size, count(data, scope));
    }
}
