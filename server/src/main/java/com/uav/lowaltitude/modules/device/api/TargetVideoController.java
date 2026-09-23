package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.device.application.TargetVideoService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
public class TargetVideoController {
    private final TargetVideoService service;
    public TargetVideoController(TargetVideoService service) { this.service = service; }

    @GetMapping("/api/v1/targets/{targetId}/video")
    public ApiResponse<TargetVideoDto> video(@PathVariable String targetId) {
        return ApiResponse.ok(service.video(targetId));
    }

    public record TargetVideoDto(String targetId, String taskId, String deviceId, String commandId,
            long checkedAt, String status, String playbackType, boolean simulated, String reason) { }
}
