package com.uav.lowaltitude.modules.device.api;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.api.WeatherSensorDtos.*;
import com.uav.lowaltitude.modules.device.application.WeatherSensorService;
import com.uav.lowaltitude.platform.api.ApiResponse;
@RestController
@RequestMapping("/api/v1/weather-sensors")
public class WeatherSensorController {
    private final WeatherSensorService service;
    public WeatherSensorController(WeatherSensorService service) { this.service=service; }
    @GetMapping("/{id}") public ApiResponse<Sensor> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }
    @PostMapping public ApiResponse<Sensor> create(@Valid @RequestBody Input input) { return ApiResponse.ok(service.create(input)); }
    @PutMapping("/{id}") public ApiResponse<Sensor> update(@PathVariable String id,@Valid @RequestBody Input input) { return ApiResponse.ok(service.update(id,input)); }
}
