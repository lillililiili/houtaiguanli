package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.IntegrationSourceService;
import com.uav.lowaltitude.modules.device.application.IntegrationSourceService.Mutation;
import com.uav.lowaltitude.modules.device.application.IntegrationSourceService.Source;
import com.uav.lowaltitude.modules.device.application.IntegrationSourceService.SourcePage;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/integration-sources")
public class IntegrationSourceController {

    private final IntegrationSourceService service;

    public IntegrationSourceController(IntegrationSourceService service) { this.service = service; }

    @GetMapping
    public ApiResponse<SourcePage> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Source> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }

    @PostMapping
    public ApiResponse<Source> create(@Valid @RequestBody SourceRequest request) {
        return ApiResponse.ok(service.create(request.toMutation()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Source> update(@PathVariable String id, @Valid @RequestBody SourceRequest request) {
        if (request.version() == null) throw new com.uav.lowaltitude.platform.api.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "version 必填");
        return ApiResponse.ok(service.update(id, request.version(), request.toMutation()));
    }

    @PatchMapping("/{id}/enabled")
    public ApiResponse<Source> enabled(@PathVariable String id, @Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.setEnabled(id, request.version(), request.enabled(), request.reason()));
    }

    public record SourceRequest(Long version, @NotBlank String sourceCode, @NotBlank String name,
                                @NotBlank String protocolCode, String protocolVersion,
                                String credentialRef, String allowedCidrs) {
        Mutation toMutation() { return new Mutation(sourceCode, name, protocolCode, protocolVersion, credentialRef, allowedCidrs); }
    }
    public record EnabledRequest(@NotNull Boolean enabled, @NotNull Long version, @NotBlank String reason) { }
}
