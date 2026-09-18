package com.uav.lowaltitude.modules.responseplan.api;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.responseplan.api.ResponsePlanDtos.*;
import com.uav.lowaltitude.modules.responseplan.application.ResponsePlanService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class ResponsePlanController {
    private final ResponsePlanService service;
    public ResponsePlanController(ResponsePlanService service){this.service=service;}
    @GetMapping("/response-plans") public ApiResponse<Page<Version>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.list(page,size));}
    @GetMapping("/response-plans/airspace-options") public ApiResponse<Page<AirspaceOption>> options(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String keyword){return ApiResponse.ok(service.options(page,size,keyword));}
    @GetMapping("/response-plans/{id}/versions") public ApiResponse<List<Version>> versions(@PathVariable String id){return ApiResponse.ok(service.versions(id));}
    @PostMapping("/response-plans") public ApiResponse<Version> create(@Valid @RequestBody Input b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.create(b,key));}
    @PostMapping("/response-plans/{id}/versions") public ApiResponse<Version> newVersion(@PathVariable String id,@Valid @RequestBody Input b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.newVersion(id,b,key));}
    @PatchMapping("/response-plan-versions/{id}") public ApiResponse<Version> update(@PathVariable String id,@Valid @RequestBody Input b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.update(id,b,key));}
    @PostMapping("/response-plan-versions/{id}/publish") public ApiResponse<Version> publish(@PathVariable String id,@Valid @RequestBody Change b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.publish(id,b,key));}
    @PostMapping("/response-plan-versions/{id}/withdraw") public ApiResponse<Version> withdraw(@PathVariable String id,@Valid @RequestBody Change b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.withdraw(id,b,key));}
    @GetMapping("/response-plans/airspaces/{id}") public ApiResponse<AirspacePlans> adminAirspace(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.forAirspace(id,page,size,true));}
    @PutMapping("/response-plans/airspaces/{id}") public ApiResponse<AirspacePlans> bind(@PathVariable String id,@Valid @RequestBody BindingInput b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.bind(id,b,key));}
    @GetMapping("/airspaces/{id}/response-plan") public ApiResponse<AirspacePlans> businessAirspace(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.forAirspace(id,page,size,false));}
}
