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

import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedAirspaceDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.CreatedVersionDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.VersionDiffDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceDiffService;
import com.uav.lowaltitude.modules.airspace.application.AirspaceWriteService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 空域写入与版本差异。读取仍在 AirspaceReadController；这里只放需要 airspace:manage 的写路径与差异查询。 */
@RestController
@RequestMapping("/api/v1")
public class AirspaceWriteController {
    private final AirspaceWriteService writes;
    private final AirspaceDiffService diffs;

    public AirspaceWriteController(AirspaceWriteService writes, AirspaceDiffService diffs) {
        this.writes = writes; this.diffs = diffs;
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

    @GetMapping("/airspaces/{airspaceId}/versions/{fromVersionId}/diff/{toVersionId}")
    public ApiResponse<VersionDiffDto> diff(@PathVariable String airspaceId, @PathVariable String fromVersionId,
            @PathVariable String toVersionId) {
        return ApiResponse.ok(diffs.diff(airspaceId, fromVersionId, toVersionId));
    }
}
