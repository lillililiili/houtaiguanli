package com.uav.lowaltitude.modules.evidence.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.AccessLogDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.CreatedLinkDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceDetailDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceSummaryDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.HoldDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.DestroyRequest;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.HoldRequest;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.LinkRequest;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.PageDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.VerifyDto;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService.CsvExport;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService.Download;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/evidence-files")
public class EvidenceController {
    private final EvidenceAssociationService evidence;

    public EvidenceController(EvidenceAssociationService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    public ApiResponse<PageDto<EvidenceSummaryDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(evidence.list(parameters));
    }

    @GetMapping("/export.csv")
    public void export(@RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        CsvExport file = evidence.exportCsv(parameters, request.getRemoteAddr(), request.getHeader("User-Agent"));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"");
        response.getWriter().write('\ufeff');
        response.getWriter().write(file.body());
    }

    @GetMapping("/{evidenceId}")
    public ApiResponse<EvidenceDetailDto> detail(@PathVariable String evidenceId) {
        return ApiResponse.ok(evidence.get(evidenceId));
    }

    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String evidenceId) {
        Download download = evidence.openDownload(evidenceId);
        String encoded = URLEncoder.encode(download.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        if (download.sizeBytes() != null) headers.setContentLength(download.sizeBytes());
        return new ResponseEntity<>(new InputStreamResource(download.stream()), headers, HttpStatus.OK);
    }

    @GetMapping("/{evidenceId}/access-logs")
    public ApiResponse<PageDto<AccessLogDto>> accessLogs(@PathVariable String evidenceId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(evidence.accessLogs(evidenceId, parameters));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EvidenceDetailDto> ingest(@RequestPart("file") MultipartFile file,
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(evidence.ingest(file, form, idempotencyKey));
    }

    @PostMapping("/{evidenceId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedLinkDto> link(@PathVariable String evidenceId, @RequestBody(required = false) LinkRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确");
        return ApiResponse.ok(evidence.link(evidenceId, body.subjectKind(), body.subjectId(), idempotencyKey));
    }

    @PostMapping("/{evidenceId}/verify")
    public ApiResponse<VerifyDto> verify(@PathVariable String evidenceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(evidence.verify(evidenceId, idempotencyKey));
    }

    @PostMapping("/{evidenceId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<HoldDto> hold(@PathVariable String evidenceId, @RequestBody(required = false) HoldRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(evidence.hold(evidenceId, body == null ? null : body.reason(), idempotencyKey));
    }

    @PostMapping("/{evidenceId}/holds/{holdId}/release")
    public ApiResponse<HoldDto> release(@PathVariable String evidenceId, @PathVariable String holdId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(evidence.release(evidenceId, holdId, idempotencyKey));
    }

    @PostMapping("/{evidenceId}/destroy")
    public ApiResponse<EvidenceDetailDto> destroy(@PathVariable String evidenceId,
            @RequestBody(required = false) DestroyRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(evidence.destroy(evidenceId, body == null ? null : body.reason(),
                body == null ? null : body.approvalNo(), idempotencyKey));
    }
}
