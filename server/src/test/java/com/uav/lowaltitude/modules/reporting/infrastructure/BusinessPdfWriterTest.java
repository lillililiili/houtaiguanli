package com.uav.lowaltitude.modules.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.Preview;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Day;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Summary;

class BusinessPdfWriterTest {
    @ParameterizedTest
    @CsvSource({
            "0, 0|1", "1, 0|1", "2, 0|1|2", "3, 0|1|3", "4, 0|2|4", "5, 0|2|5",
            "1001, 0|500|1001", "1002, 0|501|1002",
            "2147483647, 0|1073741823|2147483647",
            "9223372036854775807, 0|4611686018427387903|9223372036854775807"
    })
    void countAxisHasDistinctIntegerLabelsAtTheirActualValues(long maximum, String labels) throws Exception {
        LocalDate start = LocalDate.of(2027, 2, 12);
        var summary = new Summary("plans", "飞行任务", "任务开始时间", false, true, maximum,
                List.of(new Day(start.toString(), 0), new Day(start.plusDays(1).toString(), maximum)),
                List.of(), List.of());
        var preview = new Preview("FLIGHT_VERIFICATION", "飞行任务与核验", "WEEKLY", start.toString(),
                "整数刻度回归", start.toString(), start.plusDays(1).toString(), 0, "live", false,
                "测试导出布局", List.of(summary), Map.of(), Map.of(), "live", List.of("live"), null);
        byte[] pdf = new BusinessPdfWriter().write(new ExportData(preview, Map.of()));
        try (var document = Loader.loadPDF(pdf)) {
            var axis = new AxisLabels();
            axis.getText(document);
            axis.ticks.sort(Comparator.comparing(Tick::y).reversed());
            assertThat(axis.ticks.stream().map(Tick::value).toList())
                    .containsExactlyElementsOf(Arrays.stream(labels.split("\\|")).map(Long::valueOf).toList())
                    .doesNotHaveDuplicates().isSorted();
            Tick zero = axis.ticks.get(0), top = axis.ticks.get(axis.ticks.size() - 1);
            assertThat(top.y()).isLessThan(zero.y());
            for (Tick tick : axis.ticks) {
                double renderedFraction = (zero.y() - tick.y()) / (zero.y() - top.y());
                assertThat(renderedFraction).isCloseTo((double) tick.value() / top.value(), within(0.0001));
            }
            Path output = Files.createDirectories(Path.of("target/report-qa/integer-axis"));
            Files.write(output.resolve("maximum-" + maximum + ".pdf"), pdf);
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(axis.chartPage - 1, 96), "png",
                    output.resolve("maximum-" + maximum + ".png").toFile());
        }
    }

    private record Tick(long value, float y) { }

    private static final class AxisLabels extends PDFTextStripper {
        private final List<Tick> ticks = new ArrayList<>();
        private float chartHeadingY = Float.NaN;
        private int chartPage;
        private AxisLabels() throws IOException { }
        @Override protected void writeString(String text, List<TextPosition> positions) {
            if (positions.isEmpty()) return;
            TextPosition first = positions.get(0);
            if (text.contains("每日趋势")) {
                chartHeadingY = first.getYDirAdj();
                chartPage = getCurrentPageNo();
            }
            if (getCurrentPageNo() == chartPage && first.getYDirAdj() > chartHeadingY
                    && first.getYDirAdj() < chartHeadingY + 190
                    && first.getFontSizeInPt() == 8 && text.matches("[0-9]+")) {
                ticks.add(new Tick(Long.parseLong(text), first.getYDirAdj()));
            }
        }
    }
}
