package com.uav.lowaltitude.modules.device.api;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.QaDeviceVideoService;
import com.uav.lowaltitude.modules.device.application.VideoStreamRegistry;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.video",name="qa-enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/device-video-streams/{deviceId}")
public class QaDeviceVideoController {
    private final QaDeviceVideoService service;
    public QaDeviceVideoController(QaDeviceVideoService service){this.service=service;}
    @PutMapping public ApiResponse<VideoStreamRegistry.Stream> register(@PathVariable String deviceId){return ApiResponse.ok(service.register(deviceId));}
    @DeleteMapping public ApiResponse<Map<String,Boolean>> remove(@PathVariable String deviceId){service.remove(deviceId);return ApiResponse.ok(Map.of("removed",true));}
    @PutMapping("/frames") public ApiResponse<VideoStreamRegistry.Frame> frame(@PathVariable String deviceId,@RequestBody QaDeviceVideoService.FrameInput input){return ApiResponse.ok(service.frame(deviceId,input));}
    @PutMapping("/frames/batch") public ApiResponse<QaDeviceVideoService.BatchResult> batch(@PathVariable String deviceId,@RequestBody QaDeviceVideoService.BatchInput input){return ApiResponse.ok(service.batch(deviceId,input));}
    @GetMapping("/observation-associations") public ApiResponse<Map<String,?>> observations(@PathVariable String deviceId,@RequestParam("task_id") String taskId){return ApiResponse.ok(Map.of("items",service.observationAssociations(deviceId,taskId)));}
}
