package com.uav.lowaltitude.modules.assessment.api;

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

import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EscalationResultDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluateResultDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluationDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RevisionDto;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.assessment.application.LegalityReviewService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 阶段 7 引擎研判读取与写动作；请求体以原始字符串交给应用层做严格白名单解析。 */
@RestController
@RequestMapping("/api/v1/legality-evaluations")
public class LegalityEvaluationController {
    private final LegalityEvaluationReadService read;
    private final LegalityReviewService review;

    public LegalityEvaluationController(LegalityEvaluationReadService read, LegalityReviewService review) { this.read = read; this.review = review; }

    @GetMapping
    public ApiResponse<PageDto<EvaluationDto>> list(@RequestParam MultiValueMap<String, String> parameters) { return ApiResponse.ok(read.list(parameters)); }

    @GetMapping("/{evaluationId}")
    public ApiResponse<EvaluationDto> detail(@PathVariable String evaluationId) { return ApiResponse.ok(read.detail(evaluationId)); }

    @GetMapping("/{evaluationId}/revisions")
    public ApiResponse<PageDto<RevisionDto>> revisions(@PathVariable String evaluationId, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.revisions(evaluationId, parameters));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EvaluateResultDto> evaluate(@RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(review.evaluate(request, idempotencyKey));
    }

    @PostMapping("/{evaluationId}/revisions")
    public ApiResponse<EvaluationDto> revise(@PathVariable String evaluationId, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(review.revise(evaluationId, request, idempotencyKey));
    }

    @PostMapping("/{evaluationId}/recompute")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EvaluationDto> recompute(@PathVariable String evaluationId, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(review.recompute(evaluationId, request, idempotencyKey));
    }

    @PostMapping("/{evaluationId}/alarms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EscalationResultDto> escalate(@PathVariable String evaluationId, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(review.escalate(evaluationId, request, idempotencyKey));
    }
}
