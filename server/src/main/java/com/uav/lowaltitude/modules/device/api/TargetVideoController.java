package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import com.uav.lowaltitude.modules.device.application.EoEvidenceCaptureService;
import com.uav.lowaltitude.modules.device.application.TargetVideoService;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceDetailDto;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
public class TargetVideoController {
    private final TargetVideoService service;
    private final EoEvidenceCaptureService captures;
    public TargetVideoController(TargetVideoService service, EoEvidenceCaptureService captures) {
        this.service = service; this.captures = captures;
    }

    @GetMapping("/api/v1/targets/{targetId}/video")
    public ApiResponse<TargetVideoDto> video(@PathVariable String targetId) {
        return ApiResponse.ok(service.video(targetId));
    }

    @GetMapping("/api/v1/targets/{targetId}/video/streams/{streamId}/{resource}")
    public ResponseEntity<byte[]> resource(@PathVariable String targetId, @PathVariable String streamId,
                                           @PathVariable String resource, @RequestParam MultiValueMap<String,String> query) {
        if (!query.isEmpty() && (query.size() != 1 || !query.containsKey("session") || query.get("session").size() != 1))
            throw new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "VIDEO_RESOURCE_INVALID", "视频资源参数无效");
        byte[] body = service.resource(targetId, streamId, resource, query.getFirst("session"));
        String type = resource.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                : resource.endsWith(".ts") ? "video/mp2t" : "video/mp4";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(type)).body(body);
    }

    /** 跟踪画面截图（EO_STILL）或录像（EO_VIDEO）入证据库，关联目标、来源光电设备和可选的告警事件。 */
    @PostMapping(value = "/api/v1/targets/{targetId}/video/captures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EvidenceDetailDto> capture(@PathVariable String targetId, @RequestPart("file") MultipartFile file,
                                                  @RequestParam MultiValueMap<String, String> form,
                                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(captures.capture(targetId, file, form, idempotencyKey));
    }

    public record TargetVideoDto(String targetId, String taskId, String deviceId, String commandId,
            long checkedAt, String status, String playbackType, boolean simulated, String reason,
            String videoStatus, String sourceMode, String streamId, String playbackUrl) { }
}
