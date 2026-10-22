package com.uav.lowaltitude.modules.reporting.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportExportService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.report.ReportAccessContext;

/** Only the current unit's five report categories; platform/legacy reporting endpoints stay unchanged. */
@RestController
@RequestMapping("/api/v1/unit-admin/operations/stats/reports")
public class UnitReportingController {
    private final BusinessReportingService reports;
    private final BusinessReportExportService exports;
    private final ReportAccessContext access;
    public UnitReportingController(BusinessReportingService reports, BusinessReportExportService exports, ReportAccessContext access) {
        this.reports=reports; this.exports=exports; this.access=access;
    }
    @GetMapping("/preview")
    public ApiResponse<?> preview(@RequestParam(name="report_category",defaultValue="OVERVIEW") String category,
            @RequestParam(name="period_type",required=false) String type,
            @RequestParam(name="report_type",required=false) String legacy,
            @RequestParam(name="anchor_date") String anchor,
            @RequestParam(name="source_mode",required=false) String source) {
        return access.call(false,()->ApiResponse.ok(reports.preview(category,period(type,legacy),anchor,source)));
    }
    @GetMapping("/details")
    public ApiResponse<?> details(@RequestParam(name="report_category") String category,
            @RequestParam(name="period_type",required=false) String type,
            @RequestParam(name="report_type",required=false) String legacy,
            @RequestParam(name="anchor_date") String anchor,
            @RequestParam(name="source_mode",required=false) String source, @RequestParam String section,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return access.call(false,()->ApiResponse.ok(reports.details(category,period(type,legacy),anchor,section,page,size,source)));
    }
    @GetMapping({"/export.pdf","/export.xlsx"})
    public ResponseEntity<byte[]> export(@RequestParam(name="report_category",defaultValue="OVERVIEW") String category,
            @RequestParam(name="period_type",required=false) String type,
            @RequestParam(name="report_type",required=false) String legacy,
            @RequestParam(name="anchor_date") String anchor,
            @RequestParam(name="source_mode",required=false) String source, HttpServletRequest request) {
        boolean pdf=request.getRequestURI().endsWith(".pdf");
        return access.call(true,()-> {
            var file=exports.export(category,period(type,legacy),anchor,pdf,request.getRemoteAddr(),request.getHeader("User-Agent"),source);
            String filename=URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.body().length)
                    .header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"report."+(pdf?"pdf":"xlsx")+"\"; filename*=UTF-8''"+filename)
                    .body(file.body());
        });
    }
    private static String period(String current,String legacy) {
        if(current!=null&&legacy!=null&&!current.equals(legacy)) throw BusinessReportingService.bad("周期参数不一致");
        return current==null?legacy:current;
    }
}
