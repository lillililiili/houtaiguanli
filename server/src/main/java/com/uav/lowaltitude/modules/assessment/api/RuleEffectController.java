package com.uav.lowaltitude.modules.assessment.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RuleEffectFactDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RuleEffectSummaryDto;
import com.uav.lowaltitude.modules.assessment.application.RuleEffectReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 规则效果事实与汇总（assessment:read + rule:read）。 */
@RestController
@RequestMapping("/api/v1/rule-effects")
public class RuleEffectController {
    private final RuleEffectReadService service;

    public RuleEffectController(RuleEffectReadService service) { this.service = service; }

    @GetMapping("/facts")
    public ApiResponse<PageDto<RuleEffectFactDto>> facts(@RequestParam MultiValueMap<String, String> parameters) { return ApiResponse.ok(service.facts(parameters)); }

    @GetMapping("/summary")
    public ApiResponse<RuleEffectSummaryDto> summary(@RequestParam MultiValueMap<String, String> parameters) { return ApiResponse.ok(service.summary(parameters)); }
}
