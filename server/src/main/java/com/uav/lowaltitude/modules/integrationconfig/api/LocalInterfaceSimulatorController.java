package com.uav.lowaltitude.modules.integrationconfig.api;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.modules.handoff.application.LocalInterfaceReceiptService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.*;
import com.uav.lowaltitude.platform.api.ApiResponse;
@RestController @Profile("(local | test) & !prod & !production")
@RequestMapping("/api/v1/local-interface-simulator")
public class LocalInterfaceSimulatorController {
 private final LocalInterfaceSimulatorService service;private final LocalInterfaceReceiptService receipts;
 public LocalInterfaceSimulatorController(LocalInterfaceSimulatorService service,LocalInterfaceReceiptService receipts){this.service=service;this.receipts=receipts;}
 @GetMapping("/context") public ApiResponse<Context> context(){return ApiResponse.ok(service.context());}
 @PostMapping("/plans") public ApiResponse<Message> plan(@Valid @RequestBody PlanInput input){return ApiResponse.ok(service.plan(input));}
 @PostMapping("/weather") public ApiResponse<Message> weather(@Valid @RequestBody WeatherInput input){return ApiResponse.ok(service.weather(input));}
 @PostMapping("/bindings") public ApiResponse<Binding> binding(@Valid @RequestBody BindingInput input){return ApiResponse.ok(service.bind(input));}
 @PostMapping("/messages/{id}/receipt") public ApiResponse<Message> receipt(@PathVariable String id,@Valid @RequestBody ReceiptInput input){return ApiResponse.ok(receipts.accept(id,input));}
}
