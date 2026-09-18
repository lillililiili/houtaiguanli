package com.uav.lowaltitude.modules.automationrule.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.*;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuleService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/automation-rule-groups/{category}")
public class AutomationRuleController {
    private final AutomationRuleService service;
    public AutomationRuleController(AutomationRuleService service) { this.service=service; }
    @GetMapping public ApiResponse<Group> get(@PathVariable String category) { return ApiResponse.ok(service.get(category)); }
    @PostMapping("/rules") public ApiResponse<Group> create(@PathVariable String category,
            @Valid @RequestBody RuleInput body,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.saveRule(category,null,body,key));
    }
    @PutMapping("/rules/{id}") public ApiResponse<Group> update(@PathVariable String category,@PathVariable String id,
            @Valid @RequestBody RuleInput body,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.saveRule(category,id,body,key));
    }
    @PatchMapping("/rules/{id}/enabled") public ApiResponse<Group> enabled(@PathVariable String category,@PathVariable String id,
            @Valid @RequestBody EnabledInput body,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.enabled(category,id,body,key));
    }
    @PutMapping("/settings") public ApiResponse<Group> settings(@PathVariable String category,
            @Valid @RequestBody SettingsInput body,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.settings(category,body,key));
    }
    @GetMapping("/history") public ApiResponse<Page<Change>> history(@PathVariable String category,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.history(category,page,size));
    }
    @GetMapping("/runs") public ApiResponse<Page<com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.Run>> runs(
            @PathVariable String category,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.ok(service.runs(category,page,size));
    }
}
