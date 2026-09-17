package com.uav.lowaltitude.modules.reporting.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportExportService;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.CsvExport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.ExcelExport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.ReportPreview;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/stats")
public class ReportingController {

    private final ReportingService service;
    private final BusinessReportingService business;
    private final BusinessReportExportService exports;

    public ReportingController(ReportingService service, BusinessReportingService business, BusinessReportExportService exports) {
        this.service = service;
        this.business = business;
        this.exports = exports;
    }

    @GetMapping("/operations")
    public ApiResponse<OperationsReport> operations(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.operations(from, to));
    }

    @GetMapping("/operations/export.csv")
    public void exportCsv(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        CsvExport file = service.exportCsv(from, to, request.getRemoteAddr(), request.getHeader("User-Agent"));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"");
        response.getWriter().write('\ufeff');
        response.getWriter().write(file.body());
    }

    @GetMapping("/reports/preview")
    public ApiResponse<?> preview(
            @RequestParam(name = "report_category", required = false) String category,
            @RequestParam(name = "period_type", required = false) String periodType,
            @RequestParam(name = "report_type", required = false) String reportType,
            @RequestParam(name = "anchor_date") String anchorDate) {
        if (category != null || periodType != null)
            return ApiResponse.ok(business.preview(category == null ? "OVERVIEW" : category, period(periodType, reportType), anchorDate));
        return ApiResponse.ok(service.preview(reportType, anchorDate));
    }

    @GetMapping("/reports/export.xlsx")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(name = "report_category", required = false) String category,
            @RequestParam(name = "period_type", required = false) String periodType,
            @RequestParam(name = "report_type", required = false) String reportType,
            @RequestParam(name = "anchor_date") String anchorDate,
            HttpServletRequest request) {
        if (category != null || periodType != null)
            return file(exports.export(category == null ? "OVERVIEW" : category, period(periodType,reportType),
                    anchorDate,false,request.getRemoteAddr(),request.getHeader("User-Agent")));
        ExcelExport file = service.exportExcel(reportType, anchorDate,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.body().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"operations-report.xlsx\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.body());
    }

    @GetMapping("/reports/details")
    public ApiResponse<?> details(@RequestParam(name="report_category") String category,
            @RequestParam(name="period_type",required=false) String periodType,
            @RequestParam(name="report_type",required=false) String legacyType,
            @RequestParam(name="anchor_date") String anchor, @RequestParam String section,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(business.details(category,period(periodType,legacyType),anchor,section,page,size));
    }

    @GetMapping("/reports/export.pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestParam(name="report_category",defaultValue="OVERVIEW") String category,
            @RequestParam(name="period_type",required=false) String periodType,
            @RequestParam(name="report_type",required=false) String legacyType,
            @RequestParam(name="anchor_date") String anchor, HttpServletRequest request) {
        return file(exports.export(category,period(periodType,legacyType),anchor,true,request.getRemoteAddr(),request.getHeader("User-Agent")));
    }
    private static String period(String current,String legacy) {
        if(current!=null && legacy!=null && !current.equals(legacy)) throw BusinessReportingService.bad("周期参数不一致");
        return current==null?legacy:current;
    }
    private static ResponseEntity<byte[]> file(BusinessReportExportService.File file) {
        String encoded=URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.body().length).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"report."+ (file.contentType().equals("application/pdf")?"pdf":"xlsx")
                        + "\"; filename*=UTF-8''"+encoded).body(file.body());
    }
}
