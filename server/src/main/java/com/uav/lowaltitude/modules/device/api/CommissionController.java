package com.uav.lowaltitude.modules.device.api;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.CommissionService;
import com.uav.lowaltitude.modules.device.application.DeviceService.ConnectionProfile;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/commission-tasks")
public class CommissionController {

    private final CommissionService service;

    public CommissionController(CommissionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CommissionService.TaskPage> list(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(deviceId, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<CommissionService.Task> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<CommissionService.Task> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request.deviceId(), request.previousTaskId()));
    }

    @PostMapping("/{id}/connect")
    public ResponseEntity<ApiResponse<CommissionService.Task>> connect(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.connect(id, request.version())));
    }

    @PutMapping("/{id}/configuration")
    public ApiResponse<CommissionService.Task> configuration(@PathVariable String id, @Valid @RequestBody ConfigurationRequest request) {
        return ApiResponse.ok(service.saveConfiguration(id, request.version(), request.toProfile()));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<CommissionService.Task>> start(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.start(id, request.version())));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CommissionService.Task> cancel(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.cancel(id, request.version()));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<CommissionService.EventBatch> events(@PathVariable String id,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(service.events(id, afterSeq, limit));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<CommissionService.Report> report(@PathVariable String id) {
        return ApiResponse.ok(service.report(id));
    }

    public record CreateRequest(@NotBlank String deviceId, String previousTaskId) { }
    public record VersionRequest(@NotNull Long version) { }
    public record ConfigurationRequest(
            @NotNull Long version, @NotBlank String transport, @NotBlank String host, @NotNull Integer port,
            String path, String dataFormat, String charsetName, String authMode, String credentialRef,
            Integer heartbeatIntervalSeconds, Integer reportIntervalMillis, BigDecimal samplingRateHz,
            Boolean compressionEnabled, Boolean retransmissionEnabled, Integer timeoutMillis, Integer retryCount,
            BigDecimal longitudeOffsetDeg, BigDecimal latitudeOffsetDeg, BigDecimal altitudeOffsetM,
            String timeSyncMode, String timeServer, String timezoneName, Integer timeSyncIntervalSeconds) {
        ConnectionProfile toProfile() {
            return new ConnectionProfile(transport, host, port, path, dataFormat, charsetName, authMode, credentialRef,
                    heartbeatIntervalSeconds, reportIntervalMillis, samplingRateHz, compressionEnabled,
                    retransmissionEnabled, timeoutMillis, retryCount, longitudeOffsetDeg, latitudeOffsetDeg,
                    altitudeOffsetM, timeSyncMode, timeServer, timezoneName, timeSyncIntervalSeconds);
        }
    }
}
