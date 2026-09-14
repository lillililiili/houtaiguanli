package com.uav.lowaltitude.modules.disposal.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.ActionResultDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.AuthorizationDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.CreatedDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.EventDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.PageDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.PolicyDto;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService.ExecuteOutcome;
import com.uav.lowaltitude.modules.disposal.application.DisposalReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

/**
 * 处置授权接口。请求体以原文传进服务层做严格解析（未知字段一律拒绝），
 * 不用 @RequestBody 绑定成对象——那会把拼错的字段静默丢掉，让人以为自己填的参数生效了。
 */
@RestController
@RequestMapping("/api/v1")
public class DisposalController {
    private final DisposalAuthorizationService service;
    private final DisposalReadService read;

    public DisposalController(DisposalAuthorizationService service, DisposalReadService read) {
        this.service = service; this.read = read;
    }

    @GetMapping("/disposal-authorizations")
    public ApiResponse<PageDto<AuthorizationDto>> list(
            @RequestParam(required = false) String subject_kind, @RequestParam(required = false) String subject_id,
            @RequestParam(required = false) String status, @RequestParam(required = false) String action_type,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(read.list(subject_kind, subject_id, status, action_type, page, size));
    }

    @GetMapping("/disposal-authorizations/{id}")
    public ApiResponse<AuthorizationDto> detail(@PathVariable String id) {
        return ApiResponse.ok(read.detail(id));
    }

    @GetMapping("/disposal-authorizations/{id}/events")
    public ApiResponse<List<EventDto>> events(@PathVariable String id) {
        return ApiResponse.ok(read.events(id));
    }

    @GetMapping("/disposal-policies")
    public ApiResponse<List<PolicyDto>> policies() {
        return ApiResponse.ok(read.policies());
    }

    @PostMapping("/disposal-authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedDto> create(@RequestHeader("Idempotency-Key") String key,
                                          @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.create(body, key));
    }

    @PostMapping("/disposal-authorizations/{id}/approve")
    public ApiResponse<ActionResultDto> approve(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.approve(id, body, key));
    }

    @PostMapping("/disposal-authorizations/{id}/reject")
    public ApiResponse<ActionResultDto> reject(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.reject(id, body, key));
    }

    /**
     * 设备侧执行不了时仍然返回 409，但**事务已经提交**：那条"何时因何不可执行"的事件必须留在库里。
     * 若让服务层直接抛，异常会连同事件一起回滚，页面上只剩一个错误码，事后查不出当时到底发生了什么。
     */
    @PostMapping("/disposal-authorizations/{id}/execute")
    public ApiResponse<ActionResultDto> execute(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        ExecuteOutcome outcome = service.execute(id, body, key);
        if (outcome.rejected())
            throw new ApiException(HttpStatus.CONFLICT, outcome.rejectedCode(), outcome.rejectedDetail());
        return ApiResponse.ok(outcome.dto());
    }

    @PostMapping("/disposal-authorizations/{id}/manual-result")
    public ApiResponse<ActionResultDto> manualResult(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.manualResult(id, body, key));
    }

    @PostMapping("/disposal-authorizations/{id}/stop")
    public ApiResponse<ActionResultDto> stop(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.stop(id, body, key));
    }

    @PostMapping("/disposal-authorizations/{id}/cancel")
    public ApiResponse<ActionResultDto> cancel(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.cancel(id, body, key));
    }
}
