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
import com.uav.lowaltitude.modules.reporting.application.ReportingService.CsvExport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.ExcelExport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.ReportPreview;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/stats")
public class ReportingController {

    private final ReportingService service;

    public ReportingController(ReportingService service) {
        this.service = service;
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
    public ApiResponse<ReportPreview> preview(
            @RequestParam(name = "report_type") String reportType,
            @RequestParam(name = "anchor_date") String anchorDate) {
        return ApiResponse.ok(service.preview(reportType, anchorDate));
    }

    @GetMapping("/reports/export.xlsx")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(name = "report_type") String reportType,
            @RequestParam(name = "anchor_date") String anchorDate,
            HttpServletRequest request) {
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
}
