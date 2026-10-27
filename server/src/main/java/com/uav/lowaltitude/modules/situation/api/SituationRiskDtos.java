package com.uav.lowaltitude.modules.situation.api;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class SituationRiskDtos {
    private SituationRiskDtos() { }
    public enum Kind { ALL, UAV, SPACE_OBJECT, WEATHER }
    public enum View { ACTIVE, PROCESSING }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Counts(Long all, Long uav, Long spaceObject, Long weather) { }
    public record Availability(String uav, String spaceObject, String weather) { }
    public record Page(List<Item> items, int page, int size, long total, Counts counts,
            Availability availability, long asOf) { }
    public record Position(BigDecimal longitude, BigDecimal latitude, Long observedAt, String basis) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String key, Kind kind, String riskCaseId, Long riskCaseVersion,
            String eventId, Long eventVersion, String alarmId, String riskId,
            String targetId, String targetNo, String planId, String planNo, String routeVersionId,
            String areaId, String areaName, String severity, String reasonCode, String reasonText,
            Long occurredAt, Long updatedAt, String stage, String state, String sourceMode,
            Boolean simulated, Position position, String currentStatus, String presenceReason) { }
}
