package com.uav.lowaltitude.modules.reporting.application;

import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.reporting.infrastructure.BusinessPdfWriter;
import com.uav.lowaltitude.modules.reporting.infrastructure.BusinessWorkbookWriter;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

@Service
public class BusinessReportExportService {
    private final BusinessReportingService reports;
    private final BusinessWorkbookWriter excel;
    private final BusinessPdfWriter pdf;
    private final AuditService audit;
    public BusinessReportExportService(BusinessReportingService reports, BusinessWorkbookWriter excel,
            BusinessPdfWriter pdf, AuditService audit) {
        this.reports=reports;this.excel=excel;this.pdf=pdf;this.audit=audit;
    }
    public record File(String filename, byte[] body, String contentType) { }
    // The read-only snapshot ends before the independent audit write begins.
    public File export(String category, String period, String anchor, boolean asPdf, String ip, String agent) {
        var data=reports.exportData(category,period,anchor,asPdf);
        byte[] bytes=asPdf?pdf.write(data):excel.write(data);
        var p=data.preview();var user=AuthContext.require();
        String extension=asPdf?"pdf":"xlsx";
        String label=switch(p.periodType()){case "DAILY" -> "日报";case "WEEKLY" -> "周报";default -> "月报";};
        String filename=p.title()+label+"-"+p.from()+"-"+p.to()+"."+extension;
        audit.recordStandalone(user.userId(),user.account(),user.roleCode(),"statistics","stats_export_requested",
                "report",category,"format="+extension.toUpperCase()+";category="+category+";period="+period
                +";from="+p.from()+";to="+p.to()+";scope="+user.scopeMode()+";source="+p.sourceMode()
                +";simulated="+p.simulated(),"SUCCESS",ip,agent);
        return new File(filename,bytes,asPdf?"application/pdf":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
