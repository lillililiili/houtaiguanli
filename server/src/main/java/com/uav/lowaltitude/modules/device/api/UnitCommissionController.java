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

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.CommissionController.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations/commission-tasks")
public class UnitCommissionController {
    private final CommissionController delegate;
    private final UnitOperationsContext context;
    public UnitCommissionController(CommissionController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping
    public ApiResponse<CommissionService.TaskPage> list(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return context.call(COMMISSIONING, "commissioning.read", () -> delegate.list(deviceId,status,page,size));
    }

    @GetMapping("/{id}")
    public ApiResponse<CommissionService.Task> get(@PathVariable String id) {
        return context.call(COMMISSIONING, "commissioning.read", () -> delegate.get(id));
    }

    @PostMapping
    public ApiResponse<CommissionService.Task> create(@Valid @RequestBody CreateRequest request) {
        return context.call(COMMISSIONING, "commissioning.op", () -> delegate.create(request));
    }

    @PostMapping("/{id}/connect")
    public ResponseEntity<ApiResponse<CommissionService.Task>> connect(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return context.call(COMMISSIONING, "commissioning.op", () -> delegate.connect(id,request));
    }

    @PutMapping("/{id}/configuration")
    public ApiResponse<CommissionService.Task> configuration(@PathVariable String id, @Valid @RequestBody ConfigurationRequest request) {
        return context.call(COMMISSIONING, "commissioning.op", () -> delegate.configuration(id,request));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<CommissionService.Task>> start(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return context.call(COMMISSIONING, "commissioning.op", () -> delegate.start(id,request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CommissionService.Task> cancel(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return context.call(COMMISSIONING, "commissioning.op", () -> delegate.cancel(id,request));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<CommissionService.EventBatch> events(@PathVariable String id,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return context.call(COMMISSIONING, "commissioning.read", () -> delegate.events(id,afterSeq,limit));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<CommissionService.Report> report(@PathVariable String id) {
        return context.call(COMMISSIONING, "commissioning.read", () -> delegate.report(id));
    }
}
