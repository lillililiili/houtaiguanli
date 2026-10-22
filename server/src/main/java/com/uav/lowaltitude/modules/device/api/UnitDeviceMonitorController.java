package com.uav.lowaltitude.modules.device.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.CommissionDeviceInformationService;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceFilter;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.DeviceMonitorController.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations")
public class UnitDeviceMonitorController {
    private final DeviceMonitorController delegate;
    private final UnitOperationsContext context;
    public UnitDeviceMonitorController(DeviceMonitorController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping("/device-monitor/devices/{deviceId}/information")
    public ApiResponse<CommissionDeviceInformationService.Information> information(@PathVariable String deviceId) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.information(deviceId));
    }

    @GetMapping("/devices/{deviceId}/protocol-status")
    public ApiResponse<com.uav.lowaltitude.modules.device.application.ProtocolStatusService.ProtocolStatus> protocolStatus(
            @PathVariable String deviceId) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.protocolStatus(deviceId));
    }

    @GetMapping("/device-monitor/overview")
    public ApiResponse<DeviceService.DeviceOverview> overview(@RequestParam(name = "formal_only", defaultValue = "false") boolean formalOnly,
            @RequestParam(name = "statistics_scope", defaultValue = "false") boolean statisticsScope,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "type_code", required = false) String typeCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String connectivity,
            @RequestParam(required = false) Boolean enabled) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.overview(formalOnly,statisticsScope,keyword,typeCode,channel,region,vendor,connectivity,enabled));
    }

    @GetMapping("/device-monitor/tree")
    public ApiResponse<DeviceService.DeviceTree> tree(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "type_code", required = false) String typeCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(name = "formal_only", defaultValue = "false") boolean formalOnly,
            @RequestParam(name = "statistics_scope", defaultValue = "false") boolean statisticsScope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "500") int size) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.tree(keyword,typeCode,channel,enabled,formalOnly,statisticsScope,page,size));
    }

    @GetMapping("/devices/{deviceId}/state")
    public ApiResponse<DeviceService.DeviceState> state(@PathVariable String deviceId) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.state(deviceId));
    }

    @GetMapping("/devices/{deviceId}/state-history")
    public ApiResponse<DeviceService.StateHistory> history(
            @PathVariable String deviceId,
            @RequestParam(name = "metric_code") String metricCode,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "120") int limit) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.history(deviceId,metricCode,from,to,limit));
    }

    @GetMapping("/device-incidents")
    public ApiResponse<DeviceService.IncidentPage> incidents(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.incidents(deviceId,severity,stage,page,size));
    }

    @GetMapping("/device-events")
    public ApiResponse<DeviceService.EventBatch> events(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean latest) {
        return context.call(MONITORING, "monitoring.read", () -> delegate.events(deviceId,afterSeq,limit,latest));
    }
}
