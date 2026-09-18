package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.ReportLabels;

@Component
public class BusinessWorkbookWriter {
    public byte[] write(ExportData data) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var p = data.preview();
            Sheet summary = sheet(book, "报表摘要", "项目", "内容");
            row(summary, "报表", p.title()); row(summary, "统计周期", p.periodLabel());
            row(summary, "实际区间", p.from() + " 至 " + p.to());
            row(summary, "生成时间（上海）", ReportLabels.time(p.generatedAt()));
            row(summary, "数据来源", ReportLabels.text(p.sourceMode()));
            row(summary, "口径说明", p.statusNote());
            if (p.simulated()) row(summary, "注意", "包含模拟或回放数据，不可作为现场正式报表");
            Sheet trend = sheet(book, "每日趋势", "业务", "日期", "数量");
            Sheet distribution = sheet(book, "分类与区域", "业务", "分类", "项目", "数量");
            for (var section : p.sections()) {
                row(summary, section.title(), section.accessible() ? section.total() : "无权限");
                row(summary, section.title() + "统计口径", section.basis());
                section.days().forEach(d -> row(trend, section.title(), d.date(), d.value()));
                section.distributions().forEach(d -> d.items().forEach(v ->
                        row(distribution, section.title(), d.title(), ReportLabels.text(section.key(),d.key(),v.name()), v.value())));
                var detail = data.details().get(section.key());
                if (detail != null) {
                    var columns = ReportLabels.columns(section.key());
                    Sheet items = sheet(book, section.title() + "明细", columns.stream().map(ReportLabels.Column::label).toArray(String[]::new));
                    for (var r : detail.items()) row(items, columns.stream().map(c -> ReportLabels.cell(section.key(),r,c.field())).toArray());
                }
            }
            for (Sheet s : book) {
                s.createFreezePane(0, 1);
                int columns = s.getRow(0).getLastCellNum();
                s.setAutoFilter(new CellRangeAddress(0, Math.max(0,s.getLastRowNum()),0,columns-1));
                for (int c = 0; c < columns; c++) s.setColumnWidth(c, (c == columns-1 ? 40 : 24) * 256);
                s.setRepeatingRows(new CellRangeAddress(0,0,-1,-1));
            }
            book.write(out); return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("Excel 报表生成失败",ex); }
    }
    private static Sheet sheet(XSSFWorkbook book, String name, String... headers) {
        Sheet sheet = book.createSheet(name);
        CellStyle style = book.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = book.createFont(); font.setColor(IndexedColors.WHITE.getIndex()); font.setBold(true); style.setFont(font);
        Row header = sheet.createRow(0); header.setHeightInPoints(26);
        for (int i=0;i<headers.length;i++) { Cell cell=header.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(style); }
        return sheet;
    }
    private static void row(Sheet sheet, Object... values) {
        Row row = sheet.createRow(sheet.getLastRowNum()+1);
        for (int i=0;i<values.length;i++) {
            Cell cell=row.createCell(i);
            if (values[i] instanceof Number n) cell.setCellValue(n.doubleValue());
            else {
                String value=values[i]==null?"—":values[i].toString();
                // Explicit STRING cells never become executable formulas.
                if (value.length()>32767) throw com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.bad(
                        "明细字段超过 Excel 单元格长度限制，无法完整导出");
                cell.setCellValue(value);
            }
        }
    }
}
