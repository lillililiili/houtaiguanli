package com.uav.lowaltitude.modules.assessment.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.assessment.api.RuleDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleRunDto;
import com.uav.lowaltitude.modules.assessment.application.RuleSetManagementService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 规则运行记录只读；引擎运行本身不走 HTTP，手动触发研判见执行者 2 的 LegalityEvaluationController。 */
@RestController
@RequestMapping("/api/v1/rule-runs")
public class RuleRunController {
    private final RuleSetManagementService service;

    public RuleRunController(RuleSetManagementService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageDto<RuleRunDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.listRuns(parameters));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RuleRunDto> detail(@PathVariable String runId) {
        return ApiResponse.ok(service.runDetail(runId));
    }
}
