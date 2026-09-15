package com.uav.lowaltitude.modules.target.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.target.api.TargetDtos.PageDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetDetailDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSummaryDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackPointDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackSummaryDto;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class TargetReadController {

    private final TargetReadService service;

    public TargetReadController(TargetReadService service) {
        this.service = service;
    }

    @GetMapping("/targets")
    public ApiResponse<PageDto<TargetSummaryDto>> targets(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.targets(parameters));
    }

    @GetMapping("/targets/{targetId}")
    public ApiResponse<TargetDetailDto> target(@PathVariable String targetId) {
        return ApiResponse.ok(service.target(targetId));
    }

    @GetMapping("/targets/{targetId}/tracks")
    public ApiResponse<PageDto<TrackSummaryDto>> tracks(
            @PathVariable String targetId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.tracks(targetId, parameters));
    }

    @GetMapping("/tracks/{trackId}/points")
    public ApiResponse<PageDto<TrackPointDto>> points(
            @PathVariable String trackId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.points(trackId, parameters));
    }
}
