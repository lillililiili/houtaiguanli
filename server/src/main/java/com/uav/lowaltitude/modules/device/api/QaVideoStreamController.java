package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.QaVideoStreamService;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.video",name="qa-enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/video-streams")
public class QaVideoStreamController {
    private final QaVideoStreamService service;
    public QaVideoStreamController(QaVideoStreamService service){this.service=service;}
    public record Input(@NotBlank String deviceId, String targetId) { }
    @PutMapping("/{taskId}") public ApiResponse<VideoStreamRegistry.Stream> register(@PathVariable String taskId,
            @Valid @RequestBody Input input){return ApiResponse.ok(service.register(taskId,input.deviceId(),input.targetId()));}
    @DeleteMapping("/{taskId}") public ApiResponse<java.util.Map<String,Boolean>> remove(@PathVariable String taskId){
        service.remove(taskId);return ApiResponse.ok(java.util.Map.of("removed",true));
    }
}
