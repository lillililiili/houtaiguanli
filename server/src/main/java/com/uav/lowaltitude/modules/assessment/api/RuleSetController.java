package com.uav.lowaltitude.modules.assessment.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.assessment.api.RuleDtos.ActivationDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetVersionDetailDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetVersionDto;
import com.uav.lowaltitude.modules.assessment.application.RuleSetManagementService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 规则集读取与激活/回滚/影子。请求体以原文交给应用层做严格白名单解析，鉴权先于一切解析。 */
@RestController
@RequestMapping("/api/v1")
public class RuleSetController {
    private final RuleSetManagementService service;

    public RuleSetController(RuleSetManagementService service) { this.service = service; }

    @GetMapping("/rule-sets")
    public ApiResponse<PageDto<RuleSetDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.listRuleSets(parameters));
    }

    @GetMapping("/rule-sets/{code}/versions")
    public ApiResponse<PageDto<RuleSetVersionDto>> versions(@PathVariable String code, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.listVersions(code, parameters));
    }

    @GetMapping("/rule-set-versions/{id}")
    public ApiResponse<RuleSetVersionDetailDto> version(@PathVariable String id) {
        return ApiResponse.ok(service.versionDetail(id));
    }

    @GetMapping("/rule-sets/{code}/activations")
    public ApiResponse<PageDto<ActivationDto>> activations(@PathVariable String code, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.listActivations(code, parameters));
    }

    @PostMapping("/rule-sets/{code}/activate")
    public ApiResponse<RuleSetDto> activate(@PathVariable String code, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.activate(code, request, idempotencyKey));
    }

    @PostMapping("/rule-sets/{code}/rollback")
    public ApiResponse<RuleSetDto> rollback(@PathVariable String code, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.rollback(code, request, idempotencyKey));
    }

    @PostMapping("/rule-sets/{code}/shadow")
    public ApiResponse<RuleSetDto> shadow(@PathVariable String code, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.shadow(code, request, idempotencyKey));
    }
}
