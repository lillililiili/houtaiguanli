package com.uav.lowaltitude.modules.alarm.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.ActiveRequest;
import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.CatalogDto;
import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.RecordingDto;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecordingService;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecordingService.Content;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 电话通知录音：后台“接口配置 → 电话通知录音”上传、试听、选用、停止使用和删除。 */
@RestController
@RequestMapping("/api/v1/advisory-voice-recordings")
public class AdvisoryVoiceRecordingController {
    private final AdvisoryVoiceRecordingService recordings;

    public AdvisoryVoiceRecordingController(AdvisoryVoiceRecordingService recordings) {
        this.recordings = recordings;
    }

    @GetMapping
    public ApiResponse<CatalogDto> catalog() {
        return ApiResponse.ok(recordings.catalog());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecordingDto> upload(@RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "transcript", required = false) String transcript,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(recordings.upload(file, name, transcript, idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    /** 试听：返回录音原始字节，只在页面内播放，不缓存。 */
    @GetMapping("/{recordingId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String recordingId) {
        Content content = recordings.content(recordingId);
        String encoded = URLEncoder.encode(content.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @PatchMapping("/{recordingId}/active")
    public ApiResponse<CatalogDto> setActive(@PathVariable String recordingId,
            @Valid @RequestBody ActiveRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(recordings.setActive(recordingId, body.active(), body.expectedVersion(), idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @DeleteMapping("/{recordingId}")
    public ApiResponse<CatalogDto> delete(@PathVariable String recordingId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(recordings.delete(recordingId, idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }
}
