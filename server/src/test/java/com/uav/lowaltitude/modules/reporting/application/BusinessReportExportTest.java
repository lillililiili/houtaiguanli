package com.uav.lowaltitude.modules.reporting.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.reporting.infrastructure.BusinessPdfWriter;
import com.uav.lowaltitude.modules.reporting.infrastructure.BusinessWorkbookWriter;
import static com.uav.lowaltitude.platform.report.BusinessReportSource.*;

class BusinessReportExportTest {
    @Test void labelsReuseDomainWordingInsteadOfConflatingAlarmAndRiskStates() {
        assertThat(ReportLabels.text("alarms","state","PENDING_VERIFICATION")).isEqualTo("待核实");
        assertThat(ReportLabels.text("risks","state","PENDING_VERIFICATION")).isEqualTo("待核验");
        assertThat(ReportLabels.text("events","state","CONFIRMED")).isEqualTo("已核实，待处置");
        assertThat(ReportLabels.dictionary()).containsEntry("events.kind.RULE_LEGALITY","飞行违规");
        assertThat(ReportLabels.text("risks","kind","FUTURE_TYPE")).isEqualTo("FUTURE_TYPE");
    }
    private BusinessReportingService service(BusinessReportSource source) {
        AppClock clock=mock(AppClock.class);when(clock.now()).thenReturn(Instant.parse("2026-09-16T00:00:00Z"));
        return new BusinessReportingService(List.of(source),mock(AccessService.class),new ReportPeriodResolver(clock),clock);
    }
    private Summary summary(long total) {
        return new Summary("plans","飞行计划","计划开始时间",false,true,total,
            List.of(new Day("2026-09-01",total)),List.of(new Distribution("state","当前状态",List.of(new Count("PENDING",total)))),
            List.of(new Count("mock",total)));
    }
    @Test void excelRejectsOversizedExportButPdfRequestsOnlyFiftyRows() {
        BusinessReportSource source=mock(BusinessReportSource.class);when(source.key()).thenReturn("plans");
        when(source.summarize(any())).thenReturn(summary(50001));
        when(source.details(any(),eq(1),eq(50))).thenReturn(new Page(List.of(),1,50,50001));
        var service=service(source);
        assertThatThrownBy(()->service.exportData("FLIGHT_VERIFICATION","MONTHLY","2026-09-16",false))
                .hasMessageContaining("50000");
        var pdf=service.exportData("FLIGHT_VERIFICATION","MONTHLY","2026-09-16",true);
        assertThat(pdf.details().get("plans").total()).isEqualTo(50001);
        verify(source).details(any(),eq(1),eq(50));
    }
    @Test void searchableChineseLongTextAndStringCellsRemainIntact() throws Exception {
        BusinessReportSource source=mock(BusinessReportSource.class);when(source.key()).thenReturn("plans");
        when(source.summarize(any())).thenReturn(summary(1));
        String longNote="跨页核验说明，保留原始业务记录。".repeat(250);
        Row row=new Row("one","=HYPERLINK(\"bad\")",0L,"PENDING",null,null,"测试区域","mock",null,"NOT_VERIFIED",longNote);
        when(source.details(any(),eq(1),eq(50))).thenReturn(new Page(List.of(row),1,50,99));
        var data=service(source).exportData("FLIGHT_VERIFICATION","MONTHLY","2026-09-16",true);
        byte[] pdf=new BusinessPdfWriter().write(data);
        try(var doc=Loader.loadPDF(pdf)) {
            String text=new PDFTextStripper().getText(doc);
            assertThat(text).contains("未核验","不可作为现场正式报表","共 99 条","完整明细请导出 Excel");
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new BusinessWorkbookWriter().write(data)))) {
            var cell=book.getSheet("飞行计划明细").getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(book.getSheet("每日趋势").getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/report-qa"));
        java.nio.file.Files.write(java.nio.file.Path.of("target/report-qa/long-text.pdf"),pdf);
    }
}
