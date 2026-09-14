package com.uav.lowaltitude.modules.alarm.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.PageDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.UavEventDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerifyRequest;
import com.uav.lowaltitude.modules.alarm.application.UavEventVerificationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-events")
public class UavEventController {
    private final UavEventVerificationService service;
    public UavEventController(UavEventVerificationService service) { this.service = service; }

    @GetMapping("/{eventId}")
    public ApiResponse<UavEventDto> event(@PathVariable String eventId) { return ApiResponse.ok(service.event(eventId)); }

    @GetMapping("/{eventId}/verifications")
    public ApiResponse<PageDto<VerificationDto>> history(@PathVariable String eventId,
            @RequestParam MultiValueMap<String, String> parameters) { return ApiResponse.ok(service.history(eventId, parameters)); }

    @PostMapping("/{eventId}/verifications")
    public ApiResponse<UavEventDto> verify(@PathVariable String eventId, @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) { return ApiResponse.ok(service.verify(eventId, rawBody, idempotencyKey)); }
}
