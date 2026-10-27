package com.uav.lowaltitude.modules.flightavoidance.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.modules.flightavoidance.infrastructure.PingStationUdpListener;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/adsb-receivers")
public class AdsbReceiverController {
    private final AdsbReceiverService service;
    private final PingStationUdpListener listener;
    public AdsbReceiverController(AdsbReceiverService service, PingStationUdpListener listener) { this.service = service; this.listener = listener; }
    /** 登记页提示用：最近送来数据但识别码没登记的接收机，以及本服务收数据的 UDP 端口。 */
    @GetMapping("/unmatched-stations") public ApiResponse<UnmatchedStations> unmatched() {
        return ApiResponse.ok(service.unmatchedStations(listener));
    }
    @GetMapping("/{id}") public ApiResponse<ReceiverProfile> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }
    @com.uav.lowaltitude.platform.security.BackendOnly
    @PostMapping public ApiResponse<ReceiverProfile> create(@Valid @RequestBody ReceiverInput input) { return ApiResponse.ok(service.create(input)); }
    @com.uav.lowaltitude.platform.security.BackendOnly
    @PutMapping("/{id}") public ApiResponse<ReceiverProfile> update(@PathVariable String id, @Valid @RequestBody ReceiverInput input) {
        return ApiResponse.ok(service.update(id, input));
    }
}
