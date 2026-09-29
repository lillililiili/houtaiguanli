package com.uav.lowaltitude.modules.airspace.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedAirspaceDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedVersionDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceWriteService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 空域写入与版本差异。读取仍在 AirspaceReadController；这里只放需要 airspace:manage 的写路径与差异查询。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.airspace.legacy-write-enabled", havingValue = "true")
@RequestMapping("/api/v1")
public class AirspaceWriteController {
    private final AirspaceWriteService writes;

    public AirspaceWriteController(AirspaceWriteService writes) {
        this.writes = writes;
    }

    @PostMapping("/airspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedAirspaceDto> create(@RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(writes.create(request, idempotencyKey));
    }

    @PostMapping("/airspaces/{airspaceId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedVersionDto> addVersion(@PathVariable String airspaceId,
            @RequestBody(required = false) String request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(writes.addVersion(airspaceId, request, idempotencyKey));
    }

}
