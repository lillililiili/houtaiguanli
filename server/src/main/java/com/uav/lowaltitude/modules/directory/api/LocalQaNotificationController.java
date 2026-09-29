package com.uav.lowaltitude.modules.directory.api;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.directory.application.LocalQaNotificationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** Explicit local QA preparation; the withdrawn production configuration page stays withdrawn. */
@RestController
@Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.qa.notification-setup", name="enabled", havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/notification-settings")
public class LocalQaNotificationController {
    private final LocalQaNotificationService service;
    public LocalQaNotificationController(LocalQaNotificationService service) { this.service=service; }
    public record Input(@NotBlank @Pattern(regexp="RISK_NOTICE|PLAN_FEEDBACK|ADVISORY_SMS|ADVISORY_VOICE|UAV_PUNISHMENT|DEVICE_MAINTENANCE") String purpose,
            @NotBlank String planId, String contactId, @NotNull @Min(0) Long expectedVersion) { }
    public record Setting(String settingId, String purpose, String channelType, boolean enabled, Long validUntil, long version, String recipientId) { }
    @GetMapping public ApiResponse<List<Setting>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping public ApiResponse<Setting> prepare(@Valid @RequestBody Input input, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.prepare(input,key));
    }
}
