package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.reporting.application.ReportPeriodResolver.ReportPeriod;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.DayPoint;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.NamedCount;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.PartnerRank;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.RegionPoint;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.Summary;

@Component
public class OperationsWorkbookWriter {

    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] write(ReportPeriod period, OperationsReport report, Instant generatedAt) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setTitle(period.label());
            workbook.getProperties().getCoreProperties().setCreator("低空安全管理平台");
            Styles styles = styles(workbook);
            summarySheet(workbook, styles, period, report, generatedAt);
            trendSheet(workbook, styles, period, report.days());
            distributionSheet(workbook, styles, period, report);
            regionSheet(workbook, styles, period, report);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 Excel 报表失败", ex);
        }
    }

    private static void summarySheet(XSSFWorkbook workbook, Styles styles, ReportPeriod period,
            OperationsReport report, Instant generatedAt) {
        Sheet sheet = sheet(workbook, "报表摘要", new int[] { 22, 24, 24, 24, 24 });
        title(sheet, styles, period.label(), 4);
        int rowIndex = 2;
        rowIndex = metadata(sheet, styles, rowIndex, "报表类型", period.type().label());
        rowIndex = metadata(sheet, styles, rowIndex, "统计区间", report.from() + " 至 " + report.to());
        rowIndex = metadata(sheet, styles, rowIndex, "生成时间",
                generatedAt.atZone(ReportingService.ZONE).format(GENERATED_AT) + "（Asia/Shanghai）");
        rowIndex = metadata(sheet, styles, rowIndex, "数据来源", sourceLabel(report.sourceMode()));
        rowIndex = metadata(sheet, styles, rowIndex, "数据标记",
                report.simulated() ? "模拟数据，不可作为现场正式报表" : "按当前账号数据范围生成");

        rowIndex++;
        Row header = sheet.createRow(rowIndex++);
        text(header, 0, "关键指标", styles.header());
        text(header, 1, "数值", styles.header());
        text(header, 2, "说明", styles.header());
        for (int column = 3; column <= 4; column++) text(header, column, "", styles.header());

        Summary summary = report.summary();
        rowIndex = metric(sheet, styles, rowIndex, "新增目标数", summary.total(), "按首次发现时间去重统计");
        rowIndex = metric(sheet, styles, rowIndex, "非法目标数", summary.illegal(), rate(summary.illegal(), summary.total()));
        rowIndex = metric(sheet, styles, rowIndex, "处罚案件数", summary.punish(), "统计窗口内立案数");
        rowIndex = metric(sheet, styles, rowIndex, "高风险目标数", summary.highRisk(), rate(summary.highRisk(), summary.total()));
        rowIndex = metric(sheet, styles, rowIndex, "无人机次数", summary.uav(), rate(summary.uav(), summary.total()));
        rowIndex = metric(sheet, styles, rowIndex, "异常目标数", summary.abnormal(), rate(summary.abnormal(), summary.total()));
        if (report.devices() != null) {
            rowIndex = metric(sheet, styles, rowIndex, "接入设备总数", report.devices().total(), "当前权限范围设备快照，排除已删除设备");
            rowIndex = metric(sheet, styles, rowIndex, "在线设备数", report.devices().online(),
                    report.devices().onlineRate() == null ? "暂无在线率" : "在线率 " + report.devices().onlineRate() + "%");
        }

        for (var entry : report.availability().entrySet()) {
            rowIndex = metadata(sheet,styles,rowIndex,entry.getKey(),entry.getValue().status() + "：" + entry.getValue().reason());
        }
        rowIndex++;
        Row note = sheet.createRow(rowIndex);
        text(note, 0, "口径说明", styles.section());
        text(note, 1, "本文件与页面预览使用同一聚合口径和当前账号数据范围（状态截至各次生成时）；缺失设备摘要不代表设备数为零。", styles.text());
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, 4));
        sheet.createFreezePane(0, 2);
    }

    private static void trendSheet(XSSFWorkbook workbook, Styles styles, ReportPeriod period, List<DayPoint> days) {
        Sheet sheet = sheet(workbook, "每日趋势", new int[] { 16, 18, 18, 18, 18 });
        title(sheet, styles, period.label() + " · 每日趋势", 4);
        Row header = sheet.createRow(2);
        String[] labels = { "日期", "新增目标数", "非法飞行", "处罚案件", "高风险目标" };
        for (int i = 0; i < labels.length; i++) text(header, i, labels[i], styles.header());
        int rowIndex = 3;
        for (DayPoint day : days) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, day.date(), styles.text());
            number(row, 1, day.total(), styles.integer());
            number(row, 2, day.illegal(), styles.integer());
            number(row, 3, day.punish(), styles.integer());
            number(row, 4, day.highRisk(), styles.integer());
        }
        sheet.createFreezePane(0, 3);
        sheet.setAutoFilter(new CellRangeAddress(2, Math.max(2, rowIndex - 1), 0, 4));
    }

    private static void distributionSheet(XSSFWorkbook workbook, Styles styles, ReportPeriod period,
            OperationsReport report) {
        Sheet sheet = sheet(workbook, "分类分布", new int[] { 20, 22, 16, 16, 14 });
        title(sheet, styles, period.label() + " · 分类分布", 4);
        Row header = sheet.createRow(2);
        String[] labels = { "分类维度", "分组", "数量", "占比", "单位" };
        for (int i = 0; i < labels.length; i++) text(header, i, labels[i], styles.header());
        int rowIndex = 3;
        rowIndex = distributionRows(sheet, styles, rowIndex, "风险等级", report.byRisk(), report.summary().total(), "目标");
        rowIndex = distributionRows(sheet, styles, rowIndex, "目标类型", report.byType(), report.summary().total(), "目标");
        rowIndex = distributionRows(sheet, styles, rowIndex, "飞行时长（分钟）", report.byDuration(), report.summary().total(), "次");
        rowIndex = distributionRows(sheet, styles, rowIndex, "轨迹长度（公里）", report.byTrack(), report.summary().total(), "次");
        rowIndex = distributionRows(sheet, styles, rowIndex, "飞行高度（AMSL 米）", report.altBands(), report.altTotal(), "目标");
        distributionRows(sheet, styles, rowIndex, "处罚类型", report.byPenalty(), report.summary().punish(), "案件");
        sheet.createFreezePane(0, 3);
        sheet.setAutoFilter(new CellRangeAddress(2, sheet.getLastRowNum(), 0, 4));
    }

    private static void regionSheet(XSSFWorkbook workbook, Styles styles, ReportPeriod period,
            OperationsReport report) {
        Sheet sheet = sheet(workbook, "区域与处置", new int[] { 22, 18, 18, 18, 18 });
        title(sheet, styles, period.label() + " · 区域与处置", 4);
        Row regionHeader = sheet.createRow(2);
        String[] regionLabels = { "区域", "新增目标数", "非法飞行", "处罚案件", "高风险目标" };
        for (int i = 0; i < regionLabels.length; i++) text(regionHeader, i, regionLabels[i], styles.header());
        int rowIndex = 3;
        for (RegionPoint region : report.regions()) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, region.name(), styles.text());
            number(row, 1, region.total(), styles.integer());
            number(row, 2, region.illegal(), styles.integer());
            number(row, 3, region.punish(), styles.integer());
            number(row, 4, region.highRisk(), styles.integer());
        }
        sheet.setAutoFilter(new CellRangeAddress(2, Math.max(2, rowIndex - 1), 0, 4));

        rowIndex++;
        Row partnerTitle = sheet.createRow(rowIndex++);
        text(partnerTitle, 0, "违规主体排行", styles.section());
        for (int column = 1; column <= 4; column++) text(partnerTitle, column, "", styles.section());
        Row partnerHeader = sheet.createRow(rowIndex++);
        String[] partnerLabels = { "名次", "主体", "案件数", "罚款（元）", "说明" };
        for (int i = 0; i < partnerLabels.length; i++) text(partnerHeader, i, partnerLabels[i], styles.header());
        int rank = 1;
        for (PartnerRank partner : report.partners()) {
            Row row = sheet.createRow(rowIndex++);
            number(row, 0, rank++, styles.integer());
            text(row, 1, partner.name(), styles.text());
            number(row, 2, partner.caseCount(), styles.integer());
            number(row, 3, partner.fine(), styles.money());
            text(row, 4, partner.fine() == null ? "尚无完整有效处罚金额" : "", styles.text());
        }
        sheet.createFreezePane(0, 3);
    }

    private static int distributionRows(Sheet sheet, Styles styles, int rowIndex, String category,
            List<NamedCount> items, Integer denominator, String unit) {
        for (NamedCount item : items) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, category, styles.text());
            text(row, 1, item.name(), styles.text());
            number(row, 2, item.value(), styles.integer());
            if (denominator != null && denominator > 0) decimal(row, 3, item.value() / (double) denominator, styles.percent());
            else text(row, 3, "—", styles.text());
            text(row, 4, unit, styles.text());
        }
        return rowIndex;
    }

    private static int metadata(Sheet sheet, Styles styles, int rowIndex, String label, String value) {
        Row row = sheet.createRow(rowIndex++);
        text(row, 0, label, styles.label());
        text(row, 1, value, styles.text());
        sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 1, 4));
        return rowIndex;
    }

    private static int metric(Sheet sheet, Styles styles, int rowIndex, String label, Integer value, String note) {
        Row row = sheet.createRow(rowIndex++);
        text(row, 0, label, styles.text());
        number(row, 1, value, styles.integer());
        text(row, 2, note, styles.text());
        sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 2, 4));
        return rowIndex;
    }

    private static String rate(Integer value, Integer total) {
        return value == null || total == null || total == 0 ? "暂无占比" : "占比 %.1f%%".formatted(value * 100.0 / total);
    }

    private static String sourceLabel(String value) {
        return switch (value == null ? "" : value) {
            case "live" -> "现场数据（live）";
            case "mock" -> "模拟数据（mock）";
            case "mixed" -> "混合来源（mixed）";
            default -> value == null || value.isBlank() ? "来源未声明" : value;
        };
    }

    private static Sheet sheet(XSSFWorkbook workbook, String name, int[] widths) {
        Sheet sheet = workbook.createSheet(name);
        sheet.setDisplayGridlines(false);
        sheet.setPrintGridlines(false);
        sheet.setDefaultRowHeightInPoints(20);
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        return sheet;
    }

    private static void title(Sheet sheet, Styles styles, String value, int lastColumn) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(30);
        for (int column = 0; column <= lastColumn; column++) text(row, column, column == 0 ? value : "", styles.title());
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, Number value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) cell.setCellValue("暂不可统计"); else cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void decimal(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static Styles styles(XSSFWorkbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle title = base(workbook);
        title.setFont(titleFont);
        title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title.setAlignment(HorizontalAlignment.CENTER);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = base(workbook);
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);

        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle section = base(workbook);
        section.setFont(boldFont);
        section.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        section.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle label = base(workbook);
        label.setFont(boldFont);
        label.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        label.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle text = base(workbook);
        text.setWrapText(true);
        CellStyle integer = base(workbook);
        integer.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        CellStyle percent = base(workbook);
        percent.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
        CellStyle money = base(workbook);
        money.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00"));
        return new Styles(title, header, section, label, text, integer, percent, money);
    }

    private static CellStyle base(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        return style;
    }

    private record Styles(CellStyle title, CellStyle header, CellStyle section, CellStyle label,
            CellStyle text, CellStyle integer, CellStyle percent, CellStyle money) { }
}
