package com.uav.lowaltitude.modules.reporting.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Component
public class ReportPeriodResolver {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter COMPACT_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final WeekFields ISO_WEEK = WeekFields.ISO;

    private final AppClock clock;

    public ReportPeriodResolver(AppClock clock) {
        this.clock = clock;
    }

    public ReportPeriod resolve(String typeText, String anchorText) {
        ReportType type = ReportType.parse(typeText);
        LocalDate anchor = parseAnchor(anchorText);
        LocalDate today = clock.now().atZone(ReportingService.ZONE).toLocalDate();
        if (anchor.isAfter(today)) {
            throw bad("anchor_date 不能晚于今天");
        }

        return switch (type) {
            case DAILY -> new ReportPeriod(type, anchor, anchor, anchor,
                    "%d年%d月%d日".formatted(anchor.getYear(), anchor.getMonthValue(), anchor.getDayOfMonth()),
                    "低空安全运行日报-" + anchor.format(COMPACT_DATE) + ".xlsx");
            case WEEKLY -> weekly(anchor, today);
            case MONTHLY -> monthly(anchor, today);
        };
    }

    private static ReportPeriod weekly(LocalDate anchor, LocalDate today) {
        LocalDate from = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate naturalTo = from.plusDays(6);
        LocalDate to = naturalTo.isAfter(today) ? today : naturalTo;
        int week = anchor.get(ISO_WEEK.weekOfWeekBasedYear());
        int weekYear = anchor.get(ISO_WEEK.weekBasedYear());
        String range = "%d月%d日—%d月%d日".formatted(
                from.getMonthValue(), from.getDayOfMonth(), to.getMonthValue(), to.getDayOfMonth());
        String label = "%d年第%d周（%s%s）".formatted(weekYear, week, range,
                to.isBefore(naturalTo) ? "，截至今日" : "");
        String filename = "低空安全运行周报-" + from.format(COMPACT_DATE) + "-" + to.format(COMPACT_DATE) + ".xlsx";
        return new ReportPeriod(ReportType.WEEKLY, anchor, from, to, label, filename);
    }

    private static ReportPeriod monthly(LocalDate anchor, LocalDate today) {
        LocalDate from = anchor.withDayOfMonth(1);
        LocalDate naturalTo = anchor.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate to = naturalTo.isAfter(today) ? today : naturalTo;
        String label = "%d年%d月%s".formatted(anchor.getYear(), anchor.getMonthValue(),
                to.isBefore(naturalTo) ? "（截至%d月%d日）".formatted(to.getMonthValue(), to.getDayOfMonth()) : "");
        String filename = "低空安全运行月报-" + anchor.format(COMPACT_MONTH) + ".xlsx";
        return new ReportPeriod(ReportType.MONTHLY, anchor, from, to, label, filename);
    }

    private static LocalDate parseAnchor(String text) {
        if (text == null || text.isBlank()) {
            throw bad("anchor_date 为必填项");
        }
        try {
            return LocalDate.parse(text.trim(), DATE);
        } catch (DateTimeParseException ex) {
            throw bad("anchor_date 必须是 YYYY-MM-DD");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public enum ReportType {
        DAILY("日报"), WEEKLY("周报"), MONTHLY("月报");

        private final String label;

        ReportType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static ReportType parse(String text) {
            if (text == null || text.isBlank()) {
                throw bad("report_type 为必填项");
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw bad("report_type 必须是 DAILY、WEEKLY 或 MONTHLY");
            }
        }
    }

    public record ReportPeriod(ReportType type, LocalDate anchor, LocalDate from, LocalDate to,
            String label, String filename) { }
}
