package com.uav.lowaltitude.modules.risk.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.PageDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.RunDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SpaceFactDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SubtypeDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SummaryDto;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 空间安全风险读侧与评估触发。风险本体的读取仍走既有 /risks 接口，这里只补空间事实与汇总。 */
@RestController
@RequestMapping("/api/v1")
public class SpaceRiskController {
    private final SpaceRiskReadService service;

    public SpaceRiskController(SpaceRiskReadService service) { this.service = service; }

    @GetMapping("/space-object-subtypes")
    public ApiResponse<List<SubtypeDto>> subtypes() { return ApiResponse.ok(service.subtypes()); }

    @GetMapping("/risks/{riskId}/space-fact")
    public ApiResponse<SpaceFactDto> spaceFact(@PathVariable String riskId) { return ApiResponse.ok(service.spaceFact(riskId)); }

    @GetMapping("/space-risks/summary")
    public ApiResponse<SummaryDto> summary(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.summary(parameters));
    }

    @GetMapping("/rule-evaluations")
    public ApiResponse<PageDto<RunDto>> runs(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.runs(parameters));
    }

    /** 202：评估已受理并已留下运行记录；空间后端不可用时状态为 UNAVAILABLE，不是错误响应。 */
    @PostMapping("/rule-evaluations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RunDto> trigger(@RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.trigger(body, idempotencyKey));
    }
}
