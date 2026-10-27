package com.uav.lowaltitude.modules.device.api;
import com.uav.lowaltitude.modules.device.application.EoSituationService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/devices/eo-situation")
public class EoSituationController {
    private final EoSituationService service;
    public EoSituationController(EoSituationService service) {this.service=service;}
    @GetMapping
    public ApiResponse<EoSituationService.Page> read(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="100") int size) {return ApiResponse.ok(service.read(page,size));}
}
