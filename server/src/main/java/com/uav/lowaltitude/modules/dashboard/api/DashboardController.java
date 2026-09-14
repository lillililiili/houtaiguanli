package com.uav.lowaltitude.modules.dashboard.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.SnapshotDto;
import com.uav.lowaltitude.modules.dashboard.application.DashboardSnapshotService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 数据大屏只读快照；不提供写接口。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardSnapshotService snapshot;

    public DashboardController(DashboardSnapshotService snapshot) {
        this.snapshot = snapshot;
    }

    @GetMapping("/snapshot")
    public ApiResponse<SnapshotDto> snapshot(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(snapshot.snapshot(parameters));
    }
}
