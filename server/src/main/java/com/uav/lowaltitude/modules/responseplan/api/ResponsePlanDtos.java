package com.uav.lowaltitude.modules.responseplan.api;
import java.util.List;
import jakarta.validation.constraints.*;

public final class ResponsePlanDtos {
    private ResponsePlanDtos() { }
    public record Page<T>(List<T> items, int page, int size, long total) { }
    public record Input(@NotBlank @Size(max=36) String airspaceId,
            @NotBlank @Size(max=128) String name,
            @NotBlank @Size(max=4000) String triggerBasis,
            @NotBlank @Size(max=4000) String actionSteps,
            @NotBlank @Size(max=4000) String manualConditions,
            @NotBlank @Size(max=4000) String failureHandling,
            @NotNull @Pattern(regexp="live|mock|replay") String sourceMode,
            @NotNull @PositiveOrZero Long validFrom, @PositiveOrZero Long validTo,
            @PositiveOrZero Long expectedVersion) { }
    public record Change(@NotNull @PositiveOrZero Long expectedVersion,
                         @NotBlank @Size(max=1000) String reason) { }
    public record BindingInput(@Size(max=36) String versionId,
                               @Size(max=36) String expectedBindingId,
                               @NotBlank @Size(max=1000) String reason) { }
    public record Version(String planId, String versionId, String airspaceId, String airspaceName,
                          int revision, String name, String triggerBasis, String actionSteps,
                          String manualConditions, String failureHandling, String sourceMode,
                          long validFrom, Long validTo, String status, long version,
                          long createdAt, long updatedAt, Long publishedAt, String publishedBy,
                          Long withdrawnAt, String withdrawnReason) { }
    public record Binding(String bindingId, String airspaceId, Version plan,
                          long boundAt, String boundBy, String reason,
                          Long endedAt, String endedBy, String endReason,
                          String applicability, String applicabilityReason) { }
    public record AirspacePlans(String airspaceId, long checkedAt, String executionMode,
                                Binding current, Page<Binding> history) { }
    public record AirspaceOption(String airspaceId, String name) { }
}
