package com.uav.lowaltitude.modules.assessment.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.assessment.application.LegalityReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class LegalityReadController {
    private final LegalityReadService service;
    public LegalityReadController(LegalityReadService service) { this.service = service; }
    @GetMapping("/flight-plans/{planId}/legality-assessments")
    public ApiResponse<LegalityDtos.PageDto<LegalityDtos.LegalityAssessmentDto>> history(@PathVariable String planId, @RequestParam MultiValueMap<String, String> parameters) { return ApiResponse.ok(service.history(planId, parameters)); }
    @GetMapping("/legality-assessments/{assessmentId}")
    public ApiResponse<LegalityDtos.LegalityAssessmentDto> detail(@PathVariable String assessmentId) { return ApiResponse.ok(service.detail(assessmentId)); }
}
