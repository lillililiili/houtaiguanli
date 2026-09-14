package com.uav.lowaltitude.modules.handoff.api;

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

import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.CreatedDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DeliveryDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.HandoffDetailDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.HandoffDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.PageDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RecipientListDto;
import com.uav.lowaltitude.modules.handoff.application.HandoffReadService;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 交接只有提交与读取；送达、回执、处罚办结没有任何写入口，也不放入设备 Outbox。 */
@RestController
@RequestMapping("/api/v1")
public class HandoffController {
    private final HandoffReadService read;
    private final HandoffSubmissionService submission;

    public HandoffController(HandoffReadService read, HandoffSubmissionService submission) {
        this.read = read;
        this.submission = submission;
    }

    @GetMapping("/handoff-recipients")
    public ApiResponse<RecipientListDto> recipients(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.recipients(parameters));
    }

    @PostMapping("/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedDto> create(@RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(submission.create(request, idempotencyKey));
    }

    @GetMapping("/handoffs")
    public ApiResponse<PageDto<HandoffDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.list(parameters));
    }

    @GetMapping("/handoffs/{handoffId}")
    public ApiResponse<HandoffDetailDto> detail(@PathVariable String handoffId) {
        return ApiResponse.ok(read.detail(handoffId));
    }

    @GetMapping("/handoffs/{handoffId}/deliveries")
    public ApiResponse<PageDto<DeliveryDto>> deliveries(@PathVariable String handoffId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.deliveries(handoffId, parameters));
    }
}
