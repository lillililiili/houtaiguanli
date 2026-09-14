package com.uav.lowaltitude.modules.airspace.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository.ConflictRow;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceConflictDto;
import java.util.List;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class AirspaceConflictService {
    private final AccessControlService accessControl;
    private final FlightReadRepository flightRepository;
    private final AirspaceReadRepository airspaceRepository;

    public AirspaceConflictService(AccessControlService accessControl, FlightReadRepository flightRepository, AirspaceReadRepository airspaceRepository) {
        this.accessControl = accessControl;
        this.flightRepository = flightRepository;
        this.airspaceRepository = airspaceRepository;
    }

    @Transactional(readOnly = true)
    public java.util.List<com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceConflictDto> conflicts(String planId) {
        // 两项动作权限按契约顺序检查，任何一项缺失时都不得先解析 ID 或暴露计划存在性。
        AccessDecision flightAccess = accessControl.require(PermissionCode.FLIGHT_READ);
        AccessDecision airspaceAccess = accessControl.require(PermissionCode.AIRSPACE_READ);
        String id = pathId(planId);
        PlanRow plan = flightRepository.findPlan(id, flightAccess);
        if (plan == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_PLAN_NOT_FOUND", "飞行计划不存在");
        }
        if (airspaceRepository.hasAmbiguousEffectiveVersion(plan, airspaceAccess)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_AMBIGUOUS", "空域有效版本重叠");
        }
        List<ConflictRow> facts;
        try { facts = airspaceRepository.conflicts(plan, airspaceAccess); }
        catch (IllegalStateException unavailable) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "PostGIS 空间冲突读取不可用"); }
        return facts.stream().map(row -> dto(plan, row)).toList();
    }

    private static AirspaceConflictDto dto(PlanRow plan, ConflictRow row) {
        List<String> unknown = new java.util.ArrayList<>();
        // repository 已区分缺中心线、缺走廊、缺空域边界和恰触边；这里仅原样传递，不能把所有未知伪装成边界政策问题。
        if ("UNDETERMINED".equals(row.horizontalRelation()) && row.horizontalReason() != null) unknown.add(row.horizontalReason());
        if ("UNDETERMINED".equals(row.heightRelation())) unknown.add("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        if ("UNDETERMINED".equals(row.timeRelation())) unknown.add("PLAN_TIME_UNKNOWN");
        // 缺少任何一个事实维度时不能由读取接口推出合法或非法；只暴露可复核的关系与未知原因。
        String code = unknown.isEmpty() ? ("OVERLAPS".equals(row.horizontalRelation()) && "OVERLAPS".equals(row.heightRelation()) && "OVERLAPS".equals(row.timeRelation()) ? "CONFLICT" : "NO_CONFLICT") : "UNDETERMINED";
        return new AirspaceConflictDto(plan.planId(), plan.routeVersionId(), row.airspaceId(), row.airspaceVersionId(), row.horizontalRelation(), row.heightRelation(), row.timeRelation(), code, List.copyOf(unknown));
    }

    private static String pathId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 36) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        }
        return id;
    }
}
