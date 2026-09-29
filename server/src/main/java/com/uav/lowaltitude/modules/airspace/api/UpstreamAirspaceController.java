package com.uav.lowaltitude.modules.airspace.api;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.airspace.application.UpstreamAirspaceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/integrations/airspaces")
public class UpstreamAirspaceController {
    private final UpstreamAirspaceService service;
    public UpstreamAirspaceController(UpstreamAirspaceService service){this.service=service;}
    @PostMapping("/messages")
    public ApiResponse<Map<String,Object>> receive(@RequestBody(required=false) String request){return ApiResponse.ok(service.receiveLive(request));}
}
