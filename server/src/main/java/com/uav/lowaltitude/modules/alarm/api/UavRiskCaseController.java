package com.uav.lowaltitude.modules.alarm.api;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-risk-cases")
public class UavRiskCaseController {
    private final UavRiskCaseService service;
    public UavRiskCaseController(UavRiskCaseService service){this.service=service;}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable String id,
            @RequestParam(required=false) String purpose){return ApiResponse.ok(service.detail(id,purpose));}
    @PostMapping("/{id}/verifications") public ApiResponse<Map<String,Object>> verify(@PathVariable String id,
            @RequestBody String body,@RequestHeader(value="Idempotency-Key",required=false) String key){
        service.verify(id,body,key);
        // The proxied command has committed before the independent read-only projection.
        // A read failure after this point is an unknown response outcome; the client must
        // re-read the same risk rather than repeat the verification with a new key.
        try { return ApiResponse.ok(service.detail(id)); }
        catch(RuntimeException unavailable) {
            // Even a later 403/404 is a projection failure after this command committed;
            // do not let clients treat it as a rejected, unsaved verification and resubmit.
            var failure=new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "VERIFICATION_RESPONSE_UNAVAILABLE","核实已保存，当前详情暂时无法读取，请刷新同一风险核对结果");
            failure.initCause(unavailable);
            throw failure;
        }
    }
}
