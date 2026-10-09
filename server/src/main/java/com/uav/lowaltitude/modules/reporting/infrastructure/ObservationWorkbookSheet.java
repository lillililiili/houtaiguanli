package com.uav.lowaltitude.modules.reporting.infrastructure;

import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.Result;
import org.apache.poi.ss.usermodel.*;

/** Shared observation section for operations and business exports; all values come from the preview result. */
final class ObservationWorkbookSheet {
    private ObservationWorkbookSheet() { }
    static void append(Workbook book, Result result) {
        if (result == null) return;
        Sheet sheet = book.createSheet("监测时长与里程");
        row(sheet,"项目／日期","有效监测时长（秒）","已观测里程（米）","说明");
        row(sheet,"合计",result.durationSeconds(),result.distanceMeters(),result.reason());
        row(sheet,"口径",null,null,result.basis());
        row(sheet,"来源",null,null,String.join("、",result.sourceModes()));
        row(sheet,"配置版本",null,null,String.join("、",result.configVersions()));
        row(sheet,"参与累计目标数",null,null,result.measuredTargets()+" 个，不是飞行架次");
        row(sheet,"有效片段数",null,null,result.validSegments()+" 个，不跨断点或轨迹连接");
        for (var day:result.days()) row(sheet,day.date(),day.durationSeconds(),day.distanceMeters(),"北京时间");
        for (var e:result.exclusions()) row(sheet,"未计入："+e.reason(),null,null,e.count()+" 个点或相邻片段，不代表缺失时长");
        CellStyle header=book.createCellStyle(); Font font=book.createFont();font.setBold(true);header.setFont(font);
        for(Cell cell:sheet.getRow(0))cell.setCellStyle(header);
        sheet.setColumnWidth(0,34*256);sheet.setColumnWidth(1,25*256);sheet.setColumnWidth(2,25*256);sheet.setColumnWidth(3,80*256);
        sheet.createFreezePane(0,1);
        if(result.sourceModes().stream().anyMatch(m->!m.equals("live")))sheet.getHeader().setCenter("模拟验收 · 不可作为现场正式报表");
    }
    private static void row(Sheet sheet,Object... values) {
        Row row=sheet.createRow(sheet.getPhysicalNumberOfRows());
        for(int i=0;i<values.length;i++) {
            Cell cell=row.createCell(i);
            if(values[i] instanceof Number n)cell.setCellValue(n.doubleValue());
            else cell.setCellValue(values[i]==null?"—":values[i].toString());
        }
    }
}
