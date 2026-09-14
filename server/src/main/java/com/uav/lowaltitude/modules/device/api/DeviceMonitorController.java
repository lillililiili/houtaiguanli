package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceFilter;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class DeviceMonitorController {

    private final DeviceService service;
    private final com.uav.lowaltitude.modules.device.application.ProtocolStatusService protocolStatus;

    public DeviceMonitorController(DeviceService service,
            com.uav.lowaltitude.modules.device.application.ProtocolStatusService protocolStatus) {
        this.service = service;
        this.protocolStatus = protocolStatus;
    }

    @GetMapping("/devices/{deviceId}/protocol-status")
    public ApiResponse<com.uav.lowaltitude.modules.device.application.ProtocolStatusService.ProtocolStatus> protocolStatus(
            @PathVariable String deviceId) {
        return ApiResponse.ok(protocolStatus.get(deviceId));
    }

    @GetMapping("/device-monitor/overview")
    public ApiResponse<DeviceService.DeviceOverview> overview() {
        return ApiResponse.ok(service.overview());
    }

    @GetMapping("/device-monitor/tree")
    public ApiResponse<DeviceService.DeviceTree> tree(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "type_code", required = false) String typeCode,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(service.tree(new DeviceFilter(keyword, typeCode, channel, null, null, null, null)));
    }

    @GetMapping("/devices/{deviceId}/state")
    public ApiResponse<DeviceService.DeviceState> state(@PathVariable String deviceId) {
        return ApiResponse.ok(service.state(deviceId));
    }

    @GetMapping("/devices/{deviceId}/state-history")
    public ApiResponse<DeviceService.StateHistory> history(
            @PathVariable String deviceId,
            @RequestParam(name = "metric_code") String metricCode,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "120") int limit) {
        return ApiResponse.ok(service.history(deviceId, metricCode, from, to, limit));
    }

    @GetMapping("/device-incidents")
    public ApiResponse<DeviceService.IncidentPage> incidents(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.incidents(deviceId, severity, stage, page, size));
    }

    @GetMapping("/device-events")
    public ApiResponse<DeviceService.EventBatch> events(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(service.events(deviceId, afterSeq, limit));
    }
}
