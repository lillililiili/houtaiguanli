package com.uav.lowaltitude.modules.reporting.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.time.AppClock;
import static com.uav.lowaltitude.platform.report.BusinessReportSource.*;

@Service
public class BusinessReportingService {
    private final Map<String,BusinessReportSource> sources = new LinkedHashMap<>();
    private final AccessService access;
    private final ReportPeriodResolver periods;
    private final AppClock clock;
    public BusinessReportingService(List<BusinessReportSource> sources, AccessService access,
            ReportPeriodResolver periods, AppClock clock) {
        sources.forEach(s -> this.sources.put(s.key(), s));
        this.access = access; this.periods = periods; this.clock = clock;
    }
    public enum Category {
        OVERVIEW("综合运行", List.of("targets","alarms","risks","plans","events")),
        DEVICE_OPERATIONS("设备运维", List.of("devices","maintenance")),
        ALARM_RISK("告警与风险", List.of("alarms","risks")),
        FLIGHT_VERIFICATION("飞行计划与核验", List.of("plans")),
        EVENT_DISPOSAL("事件处置", List.of("events","authorizations","handoffs"));
        public final String title;
        public final List<String> keys;
        Category(String title, List<String> keys) { this.title = title; this.keys = keys; }
        public static Category parse(String text) {
            try { return valueOf(text); }
            catch (IllegalArgumentException | NullPointerException ex) { throw bad("无效的报表类型"); }
        }
    }
    public record Preview(String reportCategory, String title, String periodType, String anchorDate,
            String periodLabel, String from, String to, long generatedAt, String sourceMode,
            boolean simulated, String statusNote, List<Summary> sections,
            Map<String,List<ReportLabels.Column>> columns, Map<String,String> labels) { }
    public record ExportData(Preview preview, Map<String,Page> details) { }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String categoryText, String type, String anchor) {
        access.requireBusinessData("statistics.read");
        Category category = Category.parse(categoryText);
        var period = periods.resolve(type, anchor);
        Range range = new Range(period.from(), period.to());
        List<Summary> sections = new ArrayList<>();
        for (String key : category.keys) {
            BusinessReportSource source = sources.get(key);
            try { sections.add(source.summarize(range)); }
            catch (ApiException ex) {
                if (category != Category.OVERVIEW || ex.getStatus() != HttpStatus.FORBIDDEN) throw ex;
                sections.add(new Summary(key, source.title(), source.basis(), source.snapshot(), false,
                        null, List.of(), List.of(), List.of()));
            }
        }
        var modes = sections.stream().flatMap(s -> s.sources().stream()).filter(s -> s.value() > 0)
                .map(Count::name).distinct().toList();
        String mode = modes.isEmpty() ? "unknown" : modes.size() == 1 ? modes.get(0) : "mixed";
        boolean simulated = modes.stream().anyMatch(m -> m.equals("mock") || m.equals("replay"));
        return new Preview(category.name(), category.title, period.type().name(), period.anchor().toString(),
                period.label(), period.from().toString(), period.to().toString(), clock.now().toEpochMilli(),
                mode, simulated, "状态截至生成时；设备状态为当前快照。不同业务对象分别计数，不相加为事件总量。", List.copyOf(sections),
                category.keys.stream().collect(java.util.stream.Collectors.toMap(k -> k, ReportLabels::columns)), ReportLabels.dictionary());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page details(String categoryText, String type, String anchor, String section, int page, int size) {
        access.requireBusinessData("statistics.read");
        Category category = Category.parse(categoryText);
        if (category == Category.OVERVIEW || !category.keys.contains(section)) throw bad("此报表不包含该明细分区");
        if (page < 1 || size < 1 || size > 100) throw bad("页码从 1 开始，每页 1–100 条");
        var period = periods.resolve(type, anchor);
        // A category requires every constituent permission, even when requesting only one detail section.
        for (String key : category.keys) sources.get(key).requireAccess(new Range(period.from(), period.to()));
        return sources.get(section).details(new Range(period.from(), period.to()), page, size);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportData exportData(String category, String type, String anchor, boolean pdf) {
        Preview preview = preview(category, type, anchor);
        Map<String,Page> details = new LinkedHashMap<>();
        if (!category.equals("OVERVIEW")) {
            long total = preview.sections().stream().mapToLong(s -> s.total()).sum();
            if (!pdf && total > 50000) throw bad("明细超过 50000 条，请改用周报或日报缩短统计周期");
            Range range = new Range(java.time.LocalDate.parse(preview.from()), java.time.LocalDate.parse(preview.to()));
            for (Summary section : preview.sections())
                details.put(section.key(), sources.get(section.key()).details(range, 1, pdf ? 50 : 50000));
        }
        return new ExportData(preview, details);
    }
    public static ApiException bad(String text) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", text); }
}
