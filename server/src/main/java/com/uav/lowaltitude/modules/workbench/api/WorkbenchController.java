package com.uav.lowaltitude.modules.workbench.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.DetailDto;
import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.ListDto;
import com.uav.lowaltitude.modules.workbench.application.WorkbenchReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 工作台没有任何写接口；核实走源模块 verifications，通知走 handoffs。 */
@RestController
@RequestMapping("/api/v1/workbench")
public class WorkbenchController {
    private final WorkbenchReadService read;

    public WorkbenchController(WorkbenchReadService read) { this.read = read; }

    @GetMapping("/items")
    public ApiResponse<ListDto> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.list(parameters));
    }

    @GetMapping("/items/{kind}/{sourceId}")
    public ApiResponse<DetailDto> detail(@PathVariable String kind, @PathVariable String sourceId) {
        return ApiResponse.ok(read.detail(kind, sourceId));
    }
}
