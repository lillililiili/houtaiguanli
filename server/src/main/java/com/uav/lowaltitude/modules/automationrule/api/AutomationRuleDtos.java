package com.uav.lowaltitude.modules.automationrule.api;

import java.util.List;
import jakarta.validation.constraints.*;

public final class AutomationRuleDtos {
    private AutomationRuleDtos() { }

    public record CatalogItem(String code, String label, String defaultName, String kind,
            String operator, String unit, Integer minValue, Integer maxValue, String fixedValue,
            List<String> options, boolean supportsHold, String source) { }

    public record Rule(String ruleId, String name, String itemCode, String value, int holdSeconds,
            boolean enabled, long updatedAt, String updatedBy) { }

    public record Settings(String scopeMode, List<String> airspaceIds, List<String> airspaceNames,
            String scheduleMode, String startTime, String endTime, String timezone,
            int insufficientWaitSeconds, List<String> actions) { }

    public record Group(String category, long version, Settings settings, List<Rule> rules,
            List<CatalogItem> catalog, String executionStatus, String executionMessage, boolean canManage) { }

    public record RuleInput(@NotBlank @Size(max=64) String name,
            @NotBlank @Size(max=64) String itemCode, @NotBlank @Size(max=200) String value,
            @NotNull @Min(0) @Max(60) Integer holdSeconds, @NotNull Boolean enabled,
            @NotNull @PositiveOrZero Long expectedVersion) { }

    public record EnabledInput(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long expectedVersion) { }

    public record SettingsInput(@NotBlank String scopeMode,
            @NotNull @Size(max=100) List<@NotBlank @Size(max=36) String> airspaceIds,
            @NotBlank String scheduleMode, @NotBlank String startTime, @NotBlank String endTime,
            @NotBlank String timezone, @NotNull @Min(0) @Max(30) Integer insufficientWaitSeconds,
            @NotNull @Size(max=4) List<@NotBlank String> actions,
            @NotNull @PositiveOrZero Long expectedVersion) { }

    public record Change(String changeId, long version, String action, String actor,
            long createdAt, List<String> details) { }
    public record Page<T>(List<T> items, int page, int size, long total) { }
    public record Snapshot(Settings settings, List<Rule> rules) { }
}
