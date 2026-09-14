package com.uav.lowaltitude.modules.airspace.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportBatchDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.ImportDecisionDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceImportService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** GeoJSON 导入：暂存、查看、确认、放弃。确认前不产生任何空域版本。 */
@RestController
@RequestMapping("/api/v1")
public class AirspaceImportController {
    private final AirspaceImportService service;

    public AirspaceImportController(AirspaceImportService service) { this.service = service; }

    @PostMapping("/airspaces/import-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportBatchDto> stage(@RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.stage(request, idempotencyKey));
    }

    @GetMapping("/airspaces/import-batches/{batchId}")
    public ApiResponse<ImportBatchDto> batch(@PathVariable String batchId) {
        return ApiResponse.ok(service.batch(batchId));
    }

    @PostMapping("/airspaces/import-batches/{batchId}/confirm")
    public ApiResponse<ImportDecisionDto> confirm(@PathVariable String batchId,
            @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.confirm(batchId, request, idempotencyKey));
    }

    @PostMapping("/airspaces/import-batches/{batchId}/discard")
    public ApiResponse<ImportDecisionDto> discard(@PathVariable String batchId,
            @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.discard(batchId, request, idempotencyKey));
    }
}
