package com.uav.lowaltitude.modules.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.Category;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.Preview;
import com.uav.lowaltitude.modules.reporting.application.ReportLabels;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Count;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Day;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Distribution;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Page;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Row;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Summary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pure in-memory export fixtures: no Spring context, database, runtime seeds or real customer data. */
class BusinessPdfCustomerReportTest {
    private static final LocalDate FROM = LocalDate.of(2028, 4, 1);
    private static final LocalDate TO = FROM.plusDays(19);
    private static final long GENERATED_AT = TO.atTime(14, 35)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    private static final String NOTE = "状态截至生成时；设备状态为当前快照。不同业务对象分别计数，不相加为事件总量。";
    private static final Path OUTPUT = Path.of("target/report-qa/customer-report");
    private static final Map<String, String> TITLES = Map.of(
            "targets", "新增目标", "alarms", "告警", "risks", "飞行风险",
            "plans", "飞行任务", "events", "无人机事件", "devices", "设备",
            "maintenance", "设备异常处置", "authorizations", "反制授权", "handoffs", "通知移送");

    static Stream<Arguments> categoriesAndScopes() {
        return Stream.of(Category.values()).flatMap(category ->
                Stream.of(false, true).map(simulated -> Arguments.of(category, simulated)));
    }

    @ParameterizedTest(name = "{0} simulated={1}")
    @MethodSource("categoriesAndScopes")
    void fiveReportTypesKeepScopeReadableTextAndMetadata(Category category, boolean simulated) throws Exception {
        ExportData data = fixture(category, simulated, 4);
        byte[] pdf = new BusinessPdfWriter().write(data);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            String firstPage = pageText(document, 1);
            assertThat(firstPage).contains(category.title, data.preview().periodLabel(),
                    FROM.toString(), TO.toString(), "生成时间", simulated ? "模拟验收" : "正式统计");
            assertThat(text).contains("统计", "当前快照", "不相加为事件总量");
            assertThat(document.getDocumentInformation().getTitle())
                    .contains(category.title, data.preview().periodLabel());
            assertThat(document.getDocumentInformation().getAuthor()).contains("低空安全管理平台");
            assertThat(document.getDocumentCatalog().getDocumentOutline()).isNotNull();
            assertThat(document.getDocumentCatalog().getDocumentOutline().hasChildren()).isTrue();
            for (Summary section : data.preview().sections()) {
                assertThat(text).contains(section.title());
                Page detail = data.details().get(section.key());
                if (detail != null) {
                    for (Row row : detail.items()) assertThat(compact(text)).contains(compact(row.label()));
                    assertThat(text).contains("完整明细请导出 Excel");
                }
            }
            assertPageGeometryFontsAndFooters(document, simulated);
            saveSample(category.name().toLowerCase(Locale.ROOT) + (simulated ? "-simulated" : "-formal"), pdf, document);
        }
    }

    @Test
    void longTableAndNotesContinueWithoutDroppingContent() throws Exception {
        ExportData original = fixture(Category.FLIGHT_VERIFICATION, true, 50);
        List<Row> rows = new ArrayList<>(original.details().get("plans").items());
        String longNote = "长文开始标记。" + "核验材料包含任务范围、观测依据和后续处理说明，保留原始记录并等待有效回执。".repeat(135)
                + "长文结束标记。";
        Row first = rows.get(0);
        rows.set(0, new Row(first.id(), first.label(), first.occurredAt(), first.state(), first.kind(),
                first.severity(), first.region(), first.sourceMode(), first.related(), first.result(), longNote));
        ExportData data = new ExportData(original.preview(), Map.of("plans", new Page(rows, 1, 50, 687)));
        byte[] pdf = new BusinessPdfWriter().write(data);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(4);
            assertThat(text).contains("共 687 条", "最近 50 条", "完整明细请导出 Excel", "续表");
            assertThat(compact(text)).contains("长文开始标记", "长文结束标记");
            for (Row row : rows) assertThat(compact(text)).contains(compact(row.label()));
            // The complete supplied note remains searchable, including text that crossed page boundaries.
            String normalized = compact(new BodyText().getText(document));
            String repeated = compact("核验材料包含任务范围、观测依据和后续处理说明，保留原始记录并等待有效回执。");
            assertThat(occurrences(normalized, repeated)).isEqualTo(135);
            assertThat(normalized).contains(compact(longNote));
            int tablePageCount = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                String body = compact(pageText(document, page));
                long rowIdentifiers = rows.stream().filter(row -> body.contains(row.label())).count();
                if (rowIdentifiers >= 2) {
                    tablePageCount++;
                    assertThat(body).contains("任务编号", "任务开始时间", "任务状态", "最新核验");
                }
            }
            assertThat(tablePageCount).isGreaterThan(1);
            assertPageGeometryFontsAndFooters(document, true);
            saveSample("long-table-and-notes-simulated", pdf, document);
        }
    }

    @Test
    void realZeroUnavailableMetricAndDeniedSectionStayDistinct() throws Exception {
        List<Summary> sections = List.of(
                new Summary("targets", "新增目标", "首次发现时间", false, true, 0L,
                        List.of(), List.of(), List.of()),
                new Summary("alarms", "告警", "发生时间", false, true, null,
                        List.of(), List.of(), List.of()),
                new Summary("risks", "飞行风险", "发生时间", false, false, null,
                        List.of(), List.of(), List.of()));
        Preview preview = preview(Category.OVERVIEW, false, sections,
                ObservationMetrics.Result.unavailable("没有符合累计条件的有效观测，不能推算时长或里程"));
        Row denied = new Row("denied-id", "不应输出的受限明细", GENERATED_AT, "PENDING", "WEATHER",
                "HIGH", "受限区域", "live", null, null, "受限内容不能被 PDF 绕过");
        ExportData data = new ExportData(preview, Map.of("risks", new Page(List.of(denied), 1, 50, 1)));
        byte[] pdf = new BusinessPdfWriter().write(data);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("新增目标", "0", "告警", "飞行风险", "无权限", "暂不可统计",
                    "没有符合累计条件的有效观测");
            assertThat(text).doesNotContain("null", "不应输出的受限明细", "受限内容不能被 PDF 绕过");
            assertThat(compact(text)).doesNotContain("有效监测时长（秒）：0.000", "已观测里程（米）：0.000");
            assertPageGeometryFontsAndFooters(document, false);
            saveSample("zero-unavailable-denied-formal", pdf, document);
        }
    }

    @Test
    void emptyFormalReportHasNoInventedActivityOrSimulationWarning() throws Exception {
        Summary zero = new Summary("plans", "飞行任务", "任务开始时间", false, true, 0L,
                List.of(), List.of(), List.of());
        ExportData data = new ExportData(preview(Category.FLIGHT_VERIFICATION, false, List.of(zero), null),
                Map.of("plans", new Page(List.of(), 1, 50, 0)));
        byte[] pdf = new BusinessPdfWriter().write(data);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("正式统计", "飞行任务", "0", "暂无");
            assertThat(text).doesNotContain("不可作为现场正式报表", "模拟验收", "null");
            assertPageGeometryFontsAndFooters(document, false);
            saveSample("empty-formal", pdf, document);
        }
    }

    private static ExportData fixture(Category category, boolean simulated, int detailCount) {
        List<Summary> sections = new ArrayList<>();
        Map<String, Page> details = new LinkedHashMap<>();
        int index = 0;
        for (String key : category.keys) {
            int offset = ++index;
            List<Day> days = new ArrayList<>();
            for (int i = 0; i < 20; i++) days.add(new Day(FROM.plusDays(i).toString(), (i * 7L + offset * 3) % 23));
            long total = days.stream().mapToLong(Day::value).sum();
            List<Distribution> distributions = List.of(
                    new Distribution("state", key.equals("devices") ? "连接状态" : "办理状态",
                            List.of(new Count(firstState(key), total * 3 / 5),
                                    new Count(secondState(key), total - total * 3 / 5))),
                    new Distribution("region", "区域分布",
                            List.of(new Count("东部工业园区", total / 2),
                                    new Count("南部物流园及临港作业区", total - total / 2))));
            sections.add(new Summary(key, TITLES.get(key), basis(key), key.equals("devices"), true, total,
                    key.equals("devices") ? List.of() : days, distributions,
                    List.of(new Count(simulated ? "replay" : "live", total))));
            if (category != Category.OVERVIEW) {
                List<Row> rows = new ArrayList<>();
                for (int i = 0; i < detailCount; i++) {
                    String number = String.format(Locale.ROOT, "%s-%03d", prefix(key), i + 1);
                    rows.add(new Row(key + "-fixture-" + i, number, GENERATED_AT - (i + 1) * 3_600_000L,
                            i % 2 == 0 ? firstState(key) : secondState(key), kind(key),
                            i % 3 == 0 ? "HIGH" : "LOW",
                            i % 2 == 0 ? "东部工业园区" : "南部物流园及临港作业区",
                            simulated ? "replay" : "live", "关联-202804-" + (i + 1),
                            key.equals("plans") ? "NOT_VERIFIED" : "UNKNOWN",
                            "按已保存的业务记录展示。通知提交与接收回执分别记录，不推断处置完成。"));
                }
                details.put(key, new Page(rows, 1, 50, total));
            }
        }
        ObservationMetrics.Result observed = category == Category.OVERVIEW
                ? new ObservationMetrics.Result("PARTIAL", "仅累计有效片段，部分点或相邻片段未计入",
                        ObservationMetrics.BASIS, 4167.125, 34890.731, 42, 36, 252,
                        List.of(simulated ? "replay" : "live"), List.of("测试配置"),
                        List.of(new ObservationMetrics.Exclusion("GAP", "超过连续监测间隔", 5)),
                        List.of(new ObservationMetrics.Day(FROM.toString(), 4167.125, 34890.731)))
                : null;
        return new ExportData(preview(category, simulated, sections, observed), details);
    }

    private static Preview preview(Category category, boolean simulated, List<Summary> sections,
            ObservationMetrics.Result observed) {
        return new Preview(category.name(), category.title, "MONTHLY", TO.toString(),
                "2028年4月（截至4月20日）", FROM.toString(), TO.toString(), GENERATED_AT,
                simulated ? "replay" : "live", simulated, NOTE, sections, Map.of(),
                ReportLabels.dictionary(), simulated ? "simulated" : "live",
                simulated ? List.of("mock", "replay") : List.of("live"), observed);
    }

    private static String firstState(String key) {
        return switch (key) {
            case "devices" -> "ONLINE";
            case "maintenance" -> "PROCESSING";
            case "authorizations" -> "REQUESTED";
            case "handoffs" -> "SUBMITTED";
            case "alarms", "risks", "events" -> "PENDING_VERIFICATION";
            default -> "ACTIVE";
        };
    }

    private static String secondState(String key) {
        return switch (key) {
            case "devices" -> "OFFLINE";
            case "maintenance", "authorizations" -> "COMPLETED";
            case "handoffs", "risks" -> "ACKNOWLEDGED";
            case "alarms", "events" -> "DISPOSED";
            default -> "FINISHED";
        };
    }

    private static String kind(String key) {
        return switch (key) {
            case "devices" -> "RADAR";
            case "risks" -> "WEATHER";
            case "handoffs" -> "UAV_PUNISHMENT";
            case "authorizations" -> "COUNTERMEASURE";
            default -> "RULE_LEGALITY";
        };
    }

    private static String basis(String key) {
        return switch (key) {
            case "devices" -> "当前启用设备快照";
            case "targets" -> "首次发现时间";
            case "plans" -> "任务开始时间；最新核验结果截至生成时";
            case "events", "authorizations", "handoffs" -> "关联事件创建时间；状态截至生成时";
            default -> "发生时间";
        };
    }

    private static String prefix(String key) {
        return switch (key) {
            case "plans" -> "P";
            case "devices" -> "D";
            case "maintenance" -> "M";
            case "authorizations" -> "A";
            case "handoffs" -> "H";
            default -> "B";
        };
    }

    private static void assertPageGeometryFontsAndFooters(PDDocument document, boolean simulated) throws IOException {
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            var page = document.getPage(i);
            assertThat(page.getMediaBox().getWidth()).isCloseTo(PDRectangle.A4.getWidth(), within(.1f));
            assertThat(page.getMediaBox().getHeight()).isCloseTo(PDRectangle.A4.getHeight(), within(.1f));
            String text = pageText(document, i + 1);
            assertThat(compact(text)).contains("第" + (i + 1) + "/" + document.getNumberOfPages() + "页");
            if (simulated) assertThat(text).contains("模拟验收");
            for (var name : page.getResources().getFontNames()) {
                assertThat(page.getResources().getFont(name).isEmbedded()).as("embedded font on page %s", i + 1).isTrue();
            }
        }
        var bounds = new ContentBounds();
        bounds.getText(document);
        assertThat(bounds.outsidePage).as("Text must remain inside printable page bounds").isEmpty();
        assertThat(bounds.footerOverlap).as("Body text must not collide with the page-number footer").isEmpty();
    }

    private static String pageText(PDDocument document, int page) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }

    private static void saveSample(String name, byte[] pdf, PDDocument document) throws IOException {
        Path directory = Files.createDirectories(OUTPUT.resolve(name));
        Files.write(directory.resolve(name + ".pdf"), pdf);
        var renderer = new PDFRenderer(document);
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            ImageIO.write(renderer.renderImageWithDPI(page, 96), "png",
                    directory.resolve(String.format(Locale.ROOT, "page-%02d.png", page + 1)).toFile());
        }
    }

    private static int occurrences(String text, String needle) {
        int total = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) total++;
        return total;
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    private record TextRun(int page, String text, float left, float top, float right, float bottom) { }

    /** Running page headers and footers must not break the searched note at a page boundary. */
    private static final class BodyText extends PDFTextStripper {
        private BodyText() throws IOException { }
        @Override protected void processTextPosition(TextPosition position) {
            // The section running header sits 55.89 pt from the A4 top; body text starts below 60 pt.
            if (position.getYDirAdj() > 60 && position.getYDirAdj() < PDRectangle.A4.getHeight() - 48) {
                super.processTextPosition(position);
            }
        }
    }

    private static final class ContentBounds extends PDFTextStripper {
        private final List<String> outsidePage = new ArrayList<>();
        private final List<String> footerOverlap = new ArrayList<>();
        private final List<TextRun> runs = new ArrayList<>();
        private ContentBounds() throws IOException { }
        @Override protected void writeString(String text, List<TextPosition> positions) {
            if (positions.isEmpty()) return;
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = 0, bottom = 0;
            for (TextPosition position : positions) {
                left = Math.min(left, position.getXDirAdj());
                top = Math.min(top, position.getYDirAdj() - position.getHeightDir());
                right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
                bottom = Math.max(bottom, position.getYDirAdj());
            }
            TextRun run = new TextRun(getCurrentPageNo(), text, left, top, right, bottom);
            if (left < 20 || right > PDRectangle.A4.getWidth() - 20
                    || top < 12 || bottom > PDRectangle.A4.getHeight() - 12) {
                outsidePage.add("page " + run.page() + ": " + text);
            }
            // Compare actual bounding boxes, independent of how the new header/footer is positioned.
            for (TextRun other : runs) {
                if (run.page() != other.page() || (!isPageNumber(run.text()) && !isPageNumber(other.text()))) continue;
                if (run.left() < other.right() && run.right() > other.left()
                        && run.top() < other.bottom() && run.bottom() > other.top()) {
                    footerOverlap.add("page " + run.page() + ": " + run.text() + " / " + other.text());
                }
            }
            runs.add(run);
        }
        private static boolean isPageNumber(String text) { return compact(text).matches("第\\d+/\\d+页"); }
    }
}
