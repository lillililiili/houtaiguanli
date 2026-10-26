package com.uav.lowaltitude.modules.automationrule.api;

import java.util.List;
import jakarta.validation.constraints.*;

public final class ResponseOrderDtos {
    private ResponseOrderDtos() { }
    public record Policy(long version, boolean enabled, String minimumRisk, boolean predictionEnabled,
            boolean observedEnabled, String scopeMode, List<String> airspaceIds, List<String> airspaceNames,
            String scheduleMode, String startTime, String endTime, String timezone, int maxWaitSeconds,
            boolean canManage) { }
    public record Input(@Positive long expectedVersion, boolean enabled, @NotBlank String minimumRisk,
            boolean predictionEnabled, boolean observedEnabled, @NotBlank String scopeMode,
            @NotNull @Size(max=100) List<@NotBlank String> airspaceIds, @NotBlank String scheduleMode,
            @NotNull String startTime, @NotNull String endTime, @NotBlank String timezone,
            @Min(5) @Max(300) int maxWaitSeconds) { }
    public record Change(String changeId,long version,String actor,long createdAt,Policy before,Policy after) { }
    public record Page(List<Change> items,int page,int size,long total) { }
    public record Summary(String mode, boolean matched, String status, String reason, long policyVersion,
            Long priorityStartedAt, Long priorityDeadlineAt, int maxWaitSeconds, boolean phoneReleased,
            String phoneReleaseReason, boolean counterPriority, String voiceStatus, Long voiceEligibleAt,
            boolean voiceAllowed) { }
}
