package com.uav.lowaltitude.modules.assessment.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.assessment.application.ExecutionEvidenceReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/legality-evaluations")
public class ExecutionEvidenceController {
    private final ExecutionEvidenceReadService service;
    public ExecutionEvidenceController(ExecutionEvidenceReadService service){this.service=service;}
    @GetMapping("/{evaluationId}/execution-evidence")
    public ApiResponse<ExecutionEvidenceReadService.Result> read(@PathVariable String evaluationId){return ApiResponse.ok(service.read(evaluationId));}
}
