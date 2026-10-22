package com.uav.lowaltitude.modules.integrationconfig.api;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalObservationSimulatorService;
import com.uav.lowaltitude.modules.flight.application.LocalRouteInputService;
import com.uav.lowaltitude.modules.handoff.application.LocalInterfaceReceiptService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalObservationSimulatorDtos.*;
import com.uav.lowaltitude.platform.api.ApiResponse;
@RestController @Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
@RequestMapping("/api/v1/local-interface-simulator")
public class LocalInterfaceSimulatorController {
 private final LocalInterfaceSimulatorService service;private final LocalObservationSimulatorService observations;private final LocalInterfaceReceiptService receipts;private final LocalRouteInputService routes;private final LocalObservationInputParser observationInputs;
 @org.springframework.beans.factory.annotation.Autowired private com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport transport;
 public LocalInterfaceSimulatorController(LocalInterfaceSimulatorService service,LocalObservationSimulatorService observations,LocalInterfaceReceiptService receipts,LocalRouteInputService routes,LocalObservationInputParser observationInputs){this.service=service;this.observations=observations;this.receipts=receipts;this.routes=routes;this.observationInputs=observationInputs;}
 @GetMapping("/context") public ApiResponse<Context> context(){return ApiResponse.ok(service.context().withReceiverMessages(transport.pending()));}
 @GetMapping("/session") public ApiResponse<java.util.Map<String,Boolean>> session(){service.checkSession();return ApiResponse.ok(java.util.Map.of("available",true));}
 @GetMapping("/receiver-context") public ApiResponse<java.util.Map<String,java.util.List<Message>>> receiverContext(){service.checkSession();return ApiResponse.ok(java.util.Map.of("receiver_messages",transport.receiverPending()));}
 @PostMapping("/plans") public ApiResponse<Message> plan(@Valid @RequestBody PlanInput input){return ApiResponse.ok(service.plan(input));}
 @PostMapping("/routes") public ApiResponse<Message> route(@RequestBody(required=false) String input,@RequestHeader(name="Idempotency-Key",required=false) String key){return ApiResponse.ok(routes.create(input,key));}
 @GetMapping("/plan-options") public ApiResponse<com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Options> planOptions(){return ApiResponse.ok(service.planOptions());}
 @GetMapping("/plans/{id}/filing") public ApiResponse<com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Detail> planFiling(@PathVariable String id){return ApiResponse.ok(service.planFiling(id));}
 @PostMapping("/plans/{id}/filing") public ApiResponse<Message> updatePlanFiling(@PathVariable String id,@Valid @RequestBody com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Update input){return ApiResponse.ok(service.updatePlanFiling(id,input));}
 @PostMapping("/weather") public ApiResponse<Message> weather(@Valid @RequestBody WeatherInput input){return ApiResponse.ok(service.weather(input));}
 @PostMapping("/observation-devices") public ApiResponse<RegisteredDevice> observationDevice(@RequestBody String input){return ApiResponse.ok(observations.registerObservationDevice(observationInputs.read(input,ObservationDeviceInput.class,false)));}
 @PostMapping("/weather-devices") public ApiResponse<RegisteredDevice> weatherDevice(@RequestBody String input){return ApiResponse.ok(observations.registerWeatherDevice(observationInputs.read(input,WeatherDeviceInput.class,false)));}
 @PostMapping("/target-observations") public ApiResponse<AcceptedInput> targetObservations(@RequestBody String input){return ApiResponse.ok(observations.acceptTargetObservations(observationInputs.read(input,TargetObservationsInput.class,true)));}
 @PostMapping("/weather-observations") public ApiResponse<AcceptedInput> weatherObservations(@RequestBody String input){return ApiResponse.ok(observations.acceptWeatherObservation(observationInputs.read(input,WeatherObservationInput.class,false)));}
 @GetMapping("/weather-devices/{id}/observations") public ApiResponse<WeatherReadback> weatherReadback(@PathVariable String id){return ApiResponse.ok(observations.readWeather(id));}
 @PostMapping("/bindings") public ApiResponse<Binding> binding(@Valid @RequestBody BindingInput input){return ApiResponse.ok("NOTIFICATION_CHANNEL".equals(input.sourceKind())?transport.connect(input):service.bind(input));}
 @GetMapping("/punishment-recipients") public ApiResponse<com.uav.lowaltitude.modules.directory.application.SimulatorNotificationConfiguration.PunishmentRecipients> punishmentRecipients(){return ApiResponse.ok(transport.punishmentRecipients());}
 @PostMapping("/punishment-recipients") public ApiResponse<com.uav.lowaltitude.modules.directory.application.SimulatorNotificationConfiguration.PunishmentRecipients> selectPunishmentRecipients(@Valid @RequestBody PunishmentRecipientsInput input){return ApiResponse.ok(transport.selectPunishmentRecipients(input.orgIds()));}
 @PostMapping("/messages/{id}/receipt") public ApiResponse<Message> receipt(@PathVariable String id,@Valid @RequestBody ReceiptInput input){return ApiResponse.ok(id.startsWith("simn-")?transport.receipt(id,input):receipts.accept(id,input));}
}
