package com.uav.lowaltitude.platform.report;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Read-only application port implemented by each business module. */
public abstract class BusinessReportSource {
    protected final ReportDatasetReader reader;
    protected BusinessReportSource(ReportDatasetReader reader) { this.reader = reader; }
    public abstract String key();
    public abstract String title();
    public abstract String basis();
    public abstract List<Dimension> dimensions();
    protected abstract Dataset dataset(Range range);
    public boolean snapshot() { return false; }
    public void requireAccess(Range range) { dataset(range); }
    public Summary summarize(Range range) {
        return reader.summarize(dataset(range), range, key(), title(), basis(), snapshot(), dimensions());
    }
    public Page details(Range range, int page, int size) {
        return reader.details(dataset(range), page, size);
    }
    public record Range(LocalDate from, LocalDate to) {
        public long start() { return from.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
        public long end() { return to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    }
    /** SQL is supplied only by repositories, never by an HTTP parameter. */
    public record Dataset(String sql, Map<String, Object> parameters) { }
    public record Dimension(String key, String title) { }
    public record Count(String name, long value) { }
    public record Distribution(String key, String title, List<Count> items) { }
    public record Day(String date, long value) { }
    public record Summary(String key, String title, String basis, boolean snapshot, boolean accessible,
            Long total, List<Day> days, List<Distribution> distributions, List<Count> sources) { }
    public record Row(String id, String label, Long occurredAt, String state, String kind, String severity,
            String region, String sourceMode, String related, String result, String note) { }
    public record Page(List<Row> items, int page, int size, long total) { }
}
