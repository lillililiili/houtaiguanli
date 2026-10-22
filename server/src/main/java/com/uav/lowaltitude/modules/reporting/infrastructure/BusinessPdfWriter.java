package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.LongStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.Preview;
import com.uav.lowaltitude.modules.reporting.application.ReportLabels;
import com.uav.lowaltitude.platform.report.BusinessReportSource.*;

/**
 * Searchable A4 report built only from the authorized export snapshot. No database,
 * browser or network dependency; the renderer can also serve a future delivery job.
 */
@Component
public class BusinessPdfWriter {
    private static final Color NAVY = Color.decode("#173751"), INK = Color.decode("#263B4D"),
            BLUE = Color.decode("#1765A5"), TEAL = Color.decode("#087D85"), MUTED = Color.decode("#586D7D"),
            RULE = Color.decode("#D7E1E9"), PALE = Color.decode("#F3F7FA"),
            AMBER = Color.decode("#92540C"), AMBER_PALE = Color.decode("#FFF6E7");

    public byte[] write(ExportData data) {
        try (PDDocument document = new PDDocument(); var fontStream =
                new ClassPathResource("fonts/NotoSansSC-Regular.ttf").getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Preview p = data.preview();
            String title = p.title() + periodName(p.periodType());
            boolean simulated = p.simulated() || "simulated".equals(p.reportScope());
            var info = document.getDocumentInformation();
            info.setTitle((simulated ? "模拟验收 · " : "") + title + " - " + p.periodLabel());
            info.setAuthor("低空安全管理平台");
            info.setSubject(p.from() + " 至 " + p.to() + "；" + scopeLabel(p));
            info.setCreationDate(GregorianCalendar.from(
                    Instant.ofEpochMilli(p.generatedAt()).atZone(ZoneId.of("Asia/Shanghai"))));
            document.getDocumentCatalog().setLanguage("zh-CN");
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            try (Layout layout = new Layout(document, font, p, title, simulated)) {
                layout.overview(data);
                int chapter = 0;
                for (Summary section : p.sections()) {
                    if (!section.accessible()) continue;
                    layout.chapter(String.format(Locale.ROOT, "%02d", ++chapter), section.title(),
                            "统计口径：" + section.basis());
                    layout.sectionTotal(section);
                    if (!section.days().isEmpty()) layout.trend(section.days(), unit(section.key()));
                    layout.distributions(section);
                    if (section.days().isEmpty() && section.distributions().stream().allMatch(d -> d.items().isEmpty()))
                        layout.notice(section.total() == null ? "暂无可用统计依据，不能据此认定数量为零。"
                                : section.total() == 0 ? "本统计范围内暂无符合条件的记录。"
                                : "已取得汇总数量，当前没有可展示的趋势或分类明细。", false);
                }
                if (p.observationMetrics() != null) layout.observations(++chapter);
                int appendix = 0;
                for (Summary section : p.sections()) {
                    // A defensive guard: malformed snapshots cannot leak inaccessible detail rows.
                    if (!section.accessible()) continue;
                    Page detail = data.details().get(section.key());
                    if (detail != null) layout.details(section, detail, ++appendix);
                }
                layout.finish();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("PDF 报表生成失败", ex);
        }
    }

    private static String periodName(String type) {
        return switch (type) { case "DAILY" -> "日报"; case "WEEKLY" -> "周报"; default -> "月报"; };
    }
    private static String scopeLabel(Preview p) {
        return "simulated".equals(p.reportScope()) ? "模拟验收（模拟与回放）" : "正式统计（真实来源）";
    }
    private static String provenance(String mode) {
        // Unknown provenance is not evidence that the report contains no records.
        return mode == null || "unknown".equals(mode) ? "来源未知" : ReportLabels.text(mode);
    }
    private static String unit(String key) {
        return switch (key) { case "devices" -> "台"; case "plans" -> "项"; case "authorizations" -> "份"; default -> "条"; };
    }
    private static String total(Summary section) {
        return !section.accessible() ? "无权限" : section.total() == null ? "暂不可统计"
                : String.format(Locale.ROOT, "%,d", section.total());
    }
    private static String metric(Double value) {
        return value == null ? "暂不可统计" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class Layout implements AutoCloseable {
        private static final float LEFT = 42, WIDTH = PDRectangle.A4.getWidth() - LEFT * 2,
                TOP = 779, BOTTOM = 60, LEADING = 12;
        private final PDDocument doc;
        private final PDType0Font font;
        private final Preview preview;
        private final String title;
        private final boolean simulated;
        private final Map<Integer, String> glyphs = new HashMap<>();
        private final Map<String, Float> advances = new HashMap<>();
        private final List<IndexEntry> index = new ArrayList<>();
        private final PDDocumentOutline outline = new PDDocumentOutline();
        private PDPageContentStream stream;
        private PDPage currentPage, indexPage;
        private String runningTitle = "报告概览";
        private float y, indexY;
        private record IndexEntry(String title, int pageNumber) { }
        private record Note(String title, String text) { }

        Layout(PDDocument doc, PDType0Font font, Preview preview, String title, boolean simulated) throws IOException {
            this.doc = doc; this.font = font; this.preview = preview; this.title = title; this.simulated = simulated;
            doc.getDocumentCatalog().setDocumentOutline(outline);
            page(); bookmark("报告概览");
        }
        private void page() throws IOException {
            if (stream != null) stream.close();
            currentPage = new PDPage(PDRectangle.A4); doc.addPage(currentPage);
            stream = new PDPageContentStream(doc, currentPage);
            text("低空安全管理平台", LEFT, 815, 10, NAVY);
            right(simulated ? "模拟验收" : "运行统计报告", LEFT + WIDTH, 815, 9, simulated ? AMBER : MUTED);
            line(LEFT, 801, LEFT + WIDTH, 801, RULE, .7f);
            if (doc.getNumberOfPages() > 1) {
                text(title + " / " + runningTitle, LEFT, 786, 8, MUTED); y = TOP - 12;
            } else y = TOP;
        }
        private void bookmark(String label) {
            var destination = new PDPageFitDestination(); destination.setPage(currentPage);
            var item = new PDOutlineItem(); item.setTitle(label); item.setDestination(destination); outline.addLast(item);
        }
        private void chapter(String number, String label, String basis) throws IOException {
            runningTitle = label; page(); bookmark(label);
            index.add(new IndexEntry(label, doc.getNumberOfPages()));
            float numberWidth = width(number, 24);
            text(number, LEFT, y - 27, 24, TEAL);
            text(label, LEFT + Math.max(47, numberWidth + 14), y - 26, 20, NAVY);
            y -= 42; line(LEFT, y, LEFT + WIDTH, y, RULE, .7f); y -= 13;
            paragraph(basis, 9.5f, MUTED);
        }

        void overview(ExportData data) throws IOException {
            paragraph(title, 25, NAVY); paragraph(preview.periodLabel(), 13, TEAL); y -= 8;
            float top = y;
            rect(LEFT, y - 94, WIDTH, 94, PALE);
            metadata("统计周期（北京时间）", preview.from() + " 至 " + preview.to(), LEFT + 14, y - 17, 265);
            metadata("生成时间（北京时间）", ReportLabels.time(preview.generatedAt()), LEFT + 290, y - 17, 205);
            metadata("来源口径", scopeLabel(preview), LEFT + 14, y - 59, 265);
            metadata("实际数据来源", provenance(preview.sourceMode()), LEFT + 290, y - 59, 205);
            y = top - 110;
            if (simulated) notice("模拟验收：包含模拟或回放数据，不可作为现场正式报表。", true);
            heading("核心指标"); metrics(preview.sections());
            heading("统计说明");
            paragraph("统计范围：当前导出账号有权查看的数据。", 9.5f, MUTED);
            paragraph(preview.statusNote(), 9.5f, MUTED);
            if (!data.details().isEmpty())
                paragraph("明细附录按分区列示最近记录，每区最多 50 条；完整明细请导出 Excel。", 9.5f, MUTED);
            if (preview.sections().stream().anyMatch(s -> !s.accessible()))
                paragraph("标为“无权限”的指标未纳入本报告，不代表数量为零。", 9.5f, MUTED);
            heading("报告内容"); indexPage = currentPage; indexY = y;
        }
        private void metadata(String label, String value, float x, float baseline, float width) throws IOException {
            text(label, x, baseline, 8, MUTED);
            text(value, x, baseline - 18, fit(value, 10, width), INK);
        }
        private void metrics(List<Summary> sections) throws IOException {
            if (sections.isEmpty()) { paragraph("当前没有可展示的指标。", 11, MUTED); return; }
            int columns = Math.min(5, sections.size());
            float gap = 8, width = (WIDTH - gap * (columns - 1)) / columns;
            for (int start = 0; start < sections.size(); start += columns) {
                space(91);
                for (int c = 0; c < columns && start + c < sections.size(); c++) {
                    Summary section = sections.get(start + c); float x = LEFT + c * (width + gap);
                    rect(x, y - 81, width, 81, PALE);
                    line(x, y, x + width, y, c % 2 == 0 ? BLUE : TEAL, 2);
                    text(section.title(), x + 10, y - 19, fit(section.title(), 9, width - 20), MUTED);
                    text(total(section), x + 10, y - 48, fit(total(section), 23, width - 20), NAVY);
                    text(section.snapshot() ? "当前快照 / " + unit(section.key()) : "本期 / " + unit(section.key()),
                            x + 10, y - 69, 8, MUTED);
                }
                y -= 94;
            }
        }
        void sectionTotal(Summary section) throws IOException {
            paragraph(section.title() + "  " + total(section) + (section.total() == null ? "" : " " + unit(section.key()))
                    + (section.snapshot() ? "（当前快照）" : ""), 13, NAVY);
        }
        void observations(int number) throws IOException {
            var o = preview.observationMetrics();
            chapter(String.format(Locale.ROOT, "%02d", number), "监测时长与里程", "统计口径：" + o.basis());
            notice(o.reason(), false);
            paragraph("有效监测时长（秒）：" + metric(o.durationSeconds()), 14, NAVY);
            paragraph("已观测里程（米）：" + metric(o.distanceMeters()), 14, NAVY);
            paragraph("参与累计目标：" + o.measuredTargets() + "；有效片段：" + o.validSegments()
                    + "；来源：" + (o.sourceModes().isEmpty() ? "来源未知"
                    : String.join("、", o.sourceModes().stream().map(BusinessPdfWriter::provenance).toList())), 10, MUTED);
            if (!o.exclusions().isEmpty()) {
                heading("未计入的观测依据");
                table(List.of("未计入原因", "数量（点或相邻片段）"),
                        o.exclusions().stream().map(e -> List.of(e.reason(), Long.toString(e.count()))).toList(),
                        new float[]{3, 1}, "未计入的观测依据");
            }
            if (!o.days().isEmpty()) {
                heading("每日累计");
                table(List.of("日期（北京时间）", "有效监测时长（秒）", "已观测里程（米）"),
                        o.days().stream().map(d -> List.of(d.date(), metric(d.durationSeconds()), metric(d.distanceMeters()))).toList(),
                        new float[]{1, 1, 1}, "每日累计");
            }
        }
        void details(Summary section, Page detail, int appendix) throws IOException {
            String label = section.title() + "明细";
            chapter("附录", label, "共 " + detail.total() + " 条，本 PDF 展示最近 " + detail.items().size()
                    + " 条；完整明细请导出 Excel。");
            var columns = ReportLabels.columns(section.key());
            List<Note> notes = new ArrayList<>(); List<List<String>> cells = new ArrayList<>();
            for (Row row : detail.items()) {
                List<String> values = new ArrayList<>();
                for (var column : columns) {
                    String value = ReportLabels.cell(section.key(), row, column.field());
                    if (value.codePointCount(0, value.length()) > 160) {
                        String reference = appendix + "." + (notes.size() + 1);
                        notes.add(new Note("附注 " + reference + " / " + column.label() + "（"
                                + ReportLabels.cell(section.key(), row, "label") + "）", value));
                        value = "见附注 " + reference;
                    }
                    if ("occurred_at".equals(column.field())) value = value.replace(' ', '\n');
                    values.add(value);
                }
                cells.add(values);
            }
            float[] weights = new float[columns.size()];
            for (int i = 0; i < weights.length; i++) weights[i] = switch (columns.get(i).field()) {
                case "label", "related" -> 1.35f; case "occurred_at" -> 1.25f; case "note" -> 1.8f;
                case "result" -> 1.2f; case "severity" -> .6f; case "source_mode" -> .9f; default -> 1;
            };
            table(columns.stream().map(ReportLabels.Column::label).toList(), cells, weights, label);
            for (Note note : notes) { heading(note.title()); paragraph(note.text(), 9.5f, INK); }
        }

        private void space(float height) throws IOException { if (y - height < BOTTOM) page(); }
        private String safe(String value) throws IOException {
            if (value == null) return "未知";
            StringBuilder result = new StringBuilder();
            for (int cp : value.codePoints().toArray()) {
                if (cp == '\n') { result.append('\n'); continue; }
                if (Character.isISOControl(cp)) { result.append(' '); continue; }
                String glyph = glyphs.get(cp);
                if (glyph == null) {
                    glyph = new String(Character.toChars(cp));
                    try { font.encode(glyph); } catch (IllegalArgumentException ex) { glyph = "?"; }
                    glyphs.put(cp, glyph);
                }
                result.append(glyph);
            }
            return result.toString();
        }
        private float width(String value, float size) throws IOException {
            float result = 0;
            for (int cp : safe(value).codePoints().toArray()) {
                if (cp == '\n') continue;
                String ch = new String(Character.toChars(cp)); Float advance = advances.get(ch);
                if (advance == null) { advance = font.getStringWidth(ch) / 1000; advances.put(ch, advance); }
                result += advance * size;
            }
            return result;
        }
        private float fit(String value, float size, float available) throws IOException {
            return Math.min(size, size * available / Math.max(1, width(value, size)));
        }
        private void text(String value, float x, float baseline, float size, Color color) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline); stream.showText(safe(value).replace('\n', ' ')); stream.endText();
        }
        private void right(String value, float x, float baseline, float size, Color color) throws IOException {
            text(value, x - width(value, size), baseline, size, color);
        }
        private void rect(float x, float bottom, float width, float height, Color color) throws IOException {
            stream.setNonStrokingColor(color); stream.addRect(x, bottom, width, height); stream.fill();
        }
        private void line(float x1, float y1, float x2, float y2, Color color, float weight) throws IOException {
            stream.setStrokingColor(color); stream.setLineWidth(weight);
            stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke();
        }
        private List<String> wrap(String value, float size, float available) throws IOException {
            List<String> lines = new ArrayList<>(); StringBuilder current = new StringBuilder(); float occupied = 0;
            for (int cp : safe(value).codePoints().toArray()) {
                if (cp == '\n') { lines.add(current.toString()); current.setLength(0); occupied = 0; continue; }
                String ch = new String(Character.toChars(cp)); float advance = width(ch, size);
                if (occupied + advance > available && !current.isEmpty()) {
                    // Keep closing punctuation with the preceding glyph, and opening
                    // punctuation with the following one, without losing any text.
                    String carry = "";
                    int last = current.codePointBefore(current.length());
                    if (current.codePointCount(0, current.length()) > 1
                            && ("，。；：！？、）》】”’".indexOf(cp) >= 0
                            || "（《【“‘".indexOf(last) >= 0)) {
                        int at = current.offsetByCodePoints(current.length(), -1);
                        carry = current.substring(at); current.setLength(at);
                    }
                    lines.add(current.toString()); current.setLength(0);
                    current.append(carry); occupied = width(carry, size);
                }
                current.append(ch); occupied += advance;
            }
            lines.add(current.toString()); return lines;
        }
        void paragraph(String value, float size, Color color) throws IOException {
            float leading = size + 5;
            for (String part : wrap(value, size, WIDTH)) {
                space(leading); text(part, LEFT, y - size, size, color); y -= leading;
            }
            y -= 5;
        }
        void heading(String value) throws IOException {
            var parts = wrap(value, 12, WIDTH); space(parts.size() * 18 + 44); y -= 10;
            for (String part : parts) { text(part, LEFT, y - 12, 12, NAVY); y -= 18; }
            y -= 6;
        }
        void notice(String value, boolean warning) throws IOException {
            var parts = wrap(value, 9.5f, WIDTH - 26); float height = parts.size() * 15 + 18;
            if (height > TOP - BOTTOM - 50) { paragraph(value, 9.5f, warning ? AMBER : INK); return; }
            space(height + 9); rect(LEFT, y - height, WIDTH, height, warning ? AMBER_PALE : PALE);
            rect(LEFT, y - height, 2, height, warning ? AMBER : TEAL);
            for (int i = 0; i < parts.size(); i++) text(parts.get(i), LEFT + 13, y - 17 - i * 15, 9.5f, warning ? AMBER : INK);
            y -= height + 9;
        }

        void trend(List<Day> days, String unit) throws IOException {
            space(195); text("每日趋势", LEFT, y - 12, 12, NAVY);
            right("单位：" + unit + " / 北京时间", LEFT + WIDTH, y - 12, 8, MUTED); y -= 30;
            long max = Math.max(1, days.stream().mapToLong(Day::value).max().orElse(1));
            long[] ticks = LongStream.of(0, max / 2, max).distinct().toArray();
            float plotLeft = LEFT + Math.max(28, width(Long.toString(max), 8) + 12),
                    base = y - 112, plotWidth = LEFT + WIDTH - plotLeft - 12;
            for (long tick : ticks) {
                float gy = base + (float) (112d * tick / max);
                line(plotLeft, gy, plotLeft + plotWidth, gy, RULE, .5f);
                text(Long.toString(tick), LEFT, gy - 3, 8, MUTED);
            }
            stream.setStrokingColor(BLUE); stream.setLineWidth(1.7f);
            for (int i = 0; i < days.size(); i++) {
                float px = plotLeft + (days.size() == 1 ? plotWidth / 2 : plotWidth * i / (days.size() - 1)),
                        py = base + (float) (112d * days.get(i).value() / max);
                if (i == 0) stream.moveTo(px, py); else stream.lineTo(px, py);
            }
            stream.stroke();
            int step = Math.max(1, (int) Math.ceil((days.size() - 1) / 5d));
            for (int i = 0; i < days.size(); i++) {
                float px = plotLeft + (days.size() == 1 ? plotWidth / 2 : plotWidth * i / (days.size() - 1)),
                        py = base + (float) (112d * days.get(i).value() / max);
                rect(px - 1.8f, py - 1.8f, 3.6f, 3.6f, BLUE);
                if (i % step == 0 || i == days.size() - 1) {
                    String date = days.get(i).date(), label = date.length() >= 10 ? date.substring(5) : date;
                    text(label, Math.max(LEFT, Math.min(px - width(label, 8) / 2, LEFT + WIDTH - width(label, 8))),
                            base - 18, 8, MUTED);
                }
            }
            y = base - 41;
        }
        void distributions(Summary section) throws IOException {
            var distributions = section.distributions().stream().filter(d -> !d.items().isEmpty()).toList();
            for (int i = 0; i < distributions.size();) {
                Distribution first = distributions.get(i++);
                if (i < distributions.size() && compact(first) && compact(distributions.get(i))) {
                    Distribution second = distributions.get(i++); float gap = 22, width = (WIDTH - gap) / 2;
                    float height = Math.max(barHeight(section.key(), first, width), barHeight(section.key(), second, width));
                    space(height + 15);
                    bars(section.key(), first, LEFT, width, y);
                    bars(section.key(), second, LEFT + width + gap, width, y);
                    y -= height + 17;
                } else distribution(section.key(), first);
            }
        }
        private boolean compact(Distribution distribution) throws IOException {
            if (distribution.items().size() > 7) return false;
            for (Count row : distribution.items())
                if (width(row.name(), 9) > 115 || Long.toString(row.value()).length() > 9) return false;
            return true;
        }
        private float numberWidth(Distribution d) throws IOException {
            float result = 25;
            for (Count row : d.items()) result = Math.max(result, width(Long.toString(row.value()), 9) + 10);
            return result;
        }
        private float barHeight(String section, Distribution d, float width) throws IOException {
            float height = 32, labelWidth = Math.min(140, width * .43f);
            for (Count row : d.items())
                height += Math.max(29, wrap(ReportLabels.text(section, d.key(), row.name()), 9, labelWidth - 7).size() * 13 + 10);
            return height;
        }
        private void bars(String section, Distribution d, float left, float width, float top) throws IOException {
            text(d.title(), left, top - 12, 11, NAVY);
            line(left, top - 22, left + width, top - 22, RULE, .6f);
            float cursor = top - 32, labelWidth = Math.min(140, width * .43f),
                    barWidth = width - labelWidth - numberWidth(d);
            long maximum = Math.max(1, d.items().stream().mapToLong(Count::value).max().orElse(1));
            for (Count row : d.items()) {
                var parts = wrap(ReportLabels.text(section, d.key(), row.name()), 9, labelWidth - 7);
                for (int n = 0; n < parts.size(); n++) text(parts.get(n), left, cursor - 11 - n * 13, 9, INK);
                rect(left + labelWidth, cursor - 14, barWidth, 8, PALE);
                rect(left + labelWidth, cursor - 14, (float) (barWidth * (double) row.value() / maximum), 8, TEAL);
                right(Long.toString(row.value()), left + width, cursor - 12, 9, INK);
                cursor -= Math.max(29, parts.size() * 13 + 10);
            }
        }
        private void distribution(String section, Distribution d) throws IOException {
            // Splitting across pages preserves the shared scale and every distribution row.
            float labelWidth = 140, barWidth = WIDTH - labelWidth - numberWidth(d);
            long maximum = Math.max(1, d.items().stream().mapToLong(Count::value).max().orElse(1));
            space(80); heading(d.title());
            for (Count row : d.items()) {
                var parts = wrap(ReportLabels.text(section, d.key(), row.name()), 9, labelWidth - 9);
                int start = 0;
                do {
                    int available = (int) ((y - BOTTOM - 10) / 13);
                    if (available < 2) { page(); heading(d.title() + "（续）"); continue; }
                    int end = Math.min(parts.size(), start + available);
                    for (int n = start; n < end; n++) text(parts.get(n), LEFT, y - 11 - (n - start) * 13, 9, INK);
                    if (start == 0) {
                        rect(LEFT + labelWidth, y - 14, barWidth, 8, PALE);
                        rect(LEFT + labelWidth, y - 14, (float) (barWidth * (double) row.value() / maximum), 8, TEAL);
                        right(Long.toString(row.value()), LEFT + WIDTH, y - 12, 9, INK);
                    }
                    y -= Math.max(29, (end - start) * 13 + 10); start = end;
                } while (start < parts.size());
            }
            y -= 10;
        }

        void table(List<String> headers, List<List<String>> rows, float[] weights, String label) throws IOException {
            float total = 0;
            for (float weight : weights) total += weight;
            float[] widths = new float[weights.length];
            for (int i = 0; i < widths.length; i++) widths[i] = WIDTH * weights[i] / total;
            space(65); tableHeader(headers, widths);
            int rowIndex = 0;
            for (var row : rows) {
                List<List<String>> lines = new ArrayList<>(); int count = 1;
                for (int c = 0; c < row.size(); c++) {
                    var parts = wrap(row.get(c), 8, widths[c] - 12); lines.add(parts); count = Math.max(count, parts.size());
                }
                // Keep ordinary rows intact; split only a row too tall for a fresh page.
                float rowHeight = count * LEADING + 12;
                if (y - rowHeight < BOTTOM && rowHeight < TOP - BOTTOM - 110) {
                    page(); continuedTable(label, headers, widths);
                }
                int start = 0;
                while (start < count) {
                    int available = (int) ((y - BOTTOM - 12) / LEADING);
                    if (available < 1) { page(); continuedTable(label, headers, widths); continue; }
                    int end = Math.min(count, start + available); float height = (end - start) * LEADING + 12;
                    if (rowIndex % 2 == 0) rect(LEFT, y - height, WIDTH, height, PALE);
                    float x = LEFT;
                    for (int c = 0; c < lines.size(); c++) {
                        for (int k = start; k < Math.min(end, lines.get(c).size()); k++)
                            text(lines.get(c).get(k), x + 6, y - 14 - (k - start) * LEADING, 8, INK);
                        x += widths[c];
                    }
                    y -= height; line(LEFT, y, LEFT + WIDTH, y, RULE, .35f); start = end;
                }
                rowIndex++;
            }
            y -= 10;
            if (rows.isEmpty()) paragraph("当前周期暂无明细记录。", 10, MUTED);
        }
        private void continuedTable(String label, List<String> headers, float[] widths) throws IOException {
            text(label + "（续表）", LEFT, y - 11, 10, MUTED); y -= 24; tableHeader(headers, widths);
        }
        private void tableHeader(List<String> row, float[] widths) throws IOException {
            List<List<String>> lines = new ArrayList<>(); int count = 1;
            for (int c = 0; c < row.size(); c++) {
                var parts = wrap(row.get(c), 8, widths[c] - 12); lines.add(parts); count = Math.max(count, parts.size());
            }
            float height = count * LEADING + 12; rect(LEFT, y - height, WIDTH, height, NAVY);
            float x = LEFT;
            for (int c = 0; c < lines.size(); c++) {
                for (int n = 0; n < lines.get(c).size(); n++) text(lines.get(c).get(n), x + 6, y - 14 - n * LEADING, 8, Color.WHITE);
                x += widths[c];
            }
            y -= height;
        }
        void finish() throws IOException {
            stream.close(); stream = null;
            try (var contents = new PDPageContentStream(doc, indexPage, PDPageContentStream.AppendMode.APPEND, true)) {
                stream = contents; float cursor = indexY;
                for (IndexEntry entry : index) {
                    if (cursor - 20 < BOTTOM) throw new IOException("报表目录超出摘要页");
                    text(entry.title(), LEFT, cursor - 11, 9, INK);
                    right(Integer.toString(entry.pageNumber()), LEFT + WIDTH, cursor - 11, 9, MUTED);
                    line(LEFT, cursor - 17, LEFT + WIDTH, cursor - 17, RULE, .35f); cursor -= 22;
                }
                if (index.isEmpty()) text("当前没有可展示的分项内容。", LEFT, cursor - 11, 9, MUTED);
            }
            stream = null;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                try (var footer = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    stream = footer; line(LEFT, 45, LEFT + WIDTH, 45, RULE, .6f);
                    text(preview.from() + " 至 " + preview.to() + " / 北京时间", LEFT, 29, 7.5f, MUTED);
                    right("第 " + (i + 1) + " / " + doc.getNumberOfPages() + " 页", LEFT + WIDTH, 29, 8, MUTED);
                }
            }
            stream = null; outline.openNode();
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
