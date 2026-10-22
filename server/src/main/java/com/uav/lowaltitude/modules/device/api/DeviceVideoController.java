package com.uav.lowaltitude.modules.device.api;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.DeviceVideoService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/video")
public class DeviceVideoController {
    private final DeviceVideoService service;
    public DeviceVideoController(DeviceVideoService service){this.service=service;}
    @GetMapping public ApiResponse<DeviceVideoService.Video> video(@PathVariable String deviceId){return ApiResponse.ok(service.video(deviceId));}
    @GetMapping("/streams/{streamId}/{resource}")
    public ResponseEntity<byte[]> resource(@PathVariable String deviceId,@PathVariable String streamId,
            @PathVariable String resource,@RequestParam MultiValueMap<String,String> query) {
        if(!query.isEmpty() && (query.size()!=1 || !query.containsKey("session") || query.get("session").size()!=1))
            throw new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"VIDEO_RESOURCE_INVALID","视频资源参数无效");
        byte[] body=service.resource(deviceId,streamId,resource,query.getFirst("session"));
        String type=resource.endsWith(".m3u8")?"application/vnd.apple.mpegurl":resource.endsWith(".ts")?"video/mp2t":"video/mp4";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(type)).body(body);
    }
}
