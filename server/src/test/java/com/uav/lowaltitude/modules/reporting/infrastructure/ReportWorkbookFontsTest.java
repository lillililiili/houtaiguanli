package com.uav.lowaltitude.modules.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FontScheme;
import org.apache.poi.ss.usermodel.HeaderFooter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.Preview;
import com.uav.lowaltitude.modules.reporting.application.ReportPeriodResolver.ReportPeriod;
import com.uav.lowaltitude.modules.reporting.application.ReportPeriodResolver.ReportType;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.AirborneTypes;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.DayPoint;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.Summary;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics;
import com.uav.lowaltitude.platform.report.BusinessReportSource;

class ReportWorkbookFontsTest {
    @ParameterizedTest
    @CsvSource({"business,false,0", "business,true,9", "operations,false,23", "operations,true,46"})
    void exportedWorkbooksUseOpenFontsWithoutChangingData(String writer, boolean simulated, int daysAgo)
            throws Exception {
        LocalDate date = LocalDate.now(ObservationMetrics.ZONE).minusDays(daysAgo);
        String source = simulated ? "replay" : "live";
        String title = "字体核验-" + UUID.randomUUID();
        var observations = new ObservationMetrics.Result("AVAILABLE", "完整观测", ObservationMetrics.BASIS,
                13.0, 27.5, 1, 1, 1, List.of(source), List.of("test"),
                List.of(), List.of(new ObservationMetrics.Day(date.toString(), 13, 27.5)));
        byte[] bytes = writer.equals("business")
                ? businessWorkbook(date, simulated, source, title, observations)
                : operationsWorkbook(date, simulated, source, title, observations);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfFonts()).isGreaterThan(1);
            for (int index = 0; index < workbook.getNumberOfFonts(); index++) {
                assertThat(workbook.getFontAt(index).getFontName()).isEqualTo("Noto Sans SC");
                assertThat(workbook.getFontAt(index).getScheme()).isEqualTo(FontScheme.NONE);
            }
            for (Sheet sheet : workbook) {
                assertOpenFont(sheet.getHeader());
                assertOpenFont(sheet.getFooter());
            }
            var observationSheet = workbook.getSheet("监测时长与里程");
            assertThat(observationSheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(observationSheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(13);
            assertThat(observationSheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(27.5);
            assertThat(workbook.getFontAt(observationSheet.getRow(0).getCell(0)
                    .getCellStyle().getFontIndex()).getBold()).isTrue();
            if (writer.equals("business")) {
                assertThat(workbook.getSheetAt(0).getHeader().getCenter()).contains(title);
                assertThat(workbook.getSheetAt(0).getFooter().getLeft().contains("模拟验收"))
                        .isEqualTo(simulated);
            } else {
                assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo(title);
                assertThat(observationSheet.getHeader().getCenter().contains("模拟验收"))
                        .isEqualTo(simulated);
            }
        }
    }

    private static void assertOpenFont(HeaderFooter part) {
        for (String value : List.of(part.getLeft(), part.getCenter(), part.getRight())) {
            if (!value.isEmpty()) assertThat(value).startsWith("&\"Noto Sans SC,Regular\"");
        }
    }

    private static byte[] businessWorkbook(LocalDate date, boolean simulated, String source, String title,
            ObservationMetrics.Result observations) {
        var section = new BusinessReportSource.Summary("plans", "飞行任务", "任务开始时间", false, true, 1L,
                List.of(new BusinessReportSource.Day(date.toString(), 1)), List.of(),
                List.of(new BusinessReportSource.Count(source, 1)));
        var preview = new Preview("FLIGHT_VERIFICATION", title, "DAILY", date.toString(), date.toString(),
                date.toString(), date.toString(), Instant.now().toEpochMilli(), source, simulated, "测试",
                List.of(section), Map.of(), Map.of(), simulated ? "simulated" : "formal", List.of(source),
                observations);
        return new BusinessWorkbookWriter().write(new ExportData(preview, Map.of()));
    }

    private static byte[] operationsWorkbook(LocalDate date, boolean simulated, String source, String title,
            ObservationMetrics.Result observations) {
        var period = new ReportPeriod(ReportType.DAILY, date, date, date, title, "test.xlsx");
        var report = new OperationsReport(date.toString(), date.toString(), source, simulated,
                new Summary(1, 0, 0, 0, 1, 0), null,
                List.of(new DayPoint(date.toString(), date.toString(), 1, 0, 0, 0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0,
                List.of(), List.of(), List.of(), Instant.now().toEpochMilli(), Map.of(), null,
                observations, List.of(), new AirborneTypes(List.of(), 1, 0));
        return new OperationsWorkbookWriter().write(period, report, Instant.now());
    }
}
