package com.uav.lowaltitude.modules.risk.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.risk.api.RiskDtos.PageDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.VerificationDto;
import com.uav.lowaltitude.modules.risk.application.RiskReadService;
import com.uav.lowaltitude.modules.risk.application.RiskVerificationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/risks")
public class RiskController {
    private final RiskReadService read;
    private final RiskVerificationService verification;

    public RiskController(RiskReadService read, RiskVerificationService verification) {
        this.read = read;
        this.verification = verification;
    }

    @GetMapping
    public ApiResponse<PageDto<RiskDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.list(parameters));
    }

    /** 区域筛选项：与导出同理，必须排在 /{riskId} 之前。 */
    @GetMapping("/districts")
    public ApiResponse<java.util.List<RiskDtos.DistrictOptionDto>> districts() {
        return ApiResponse.ok(read.districts());
    }

    /** 导出：必须排在 /{riskId} 之前，否则 export.csv 会被当成一个风险 id 走进详情。 */
    @GetMapping("/export.csv")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam MultiValueMap<String, String> parameters) {
        return read.export(parameters);
    }

    @GetMapping("/{riskId}")
    public ApiResponse<RiskDto> detail(@PathVariable String riskId) {
        return ApiResponse.ok(read.detail(riskId));
    }

    @GetMapping("/{riskId}/verifications")
    public ApiResponse<PageDto<VerificationDto>> history(@PathVariable String riskId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(verification.history(riskId, parameters));
    }

    @PostMapping("/{riskId}/verifications")
    public ApiResponse<RiskDto> verify(@PathVariable String riskId, @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(verification.verify(riskId, request, idempotencyKey));
    }
}
