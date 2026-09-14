package com.uav.lowaltitude.modules.airspace.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.DiffFieldDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.DiffGeometryDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceWriteDtos.VersionDiffDto;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository.GeometryDiff;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository.VersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 两个版本的差异：字段差在应用层比对，几何差交给数据库。
 * 几何差只在 PostGIS 上计算：面积必须在 geography 上算才有米制含义，H2 没有等价能力，
 * 这时如实返回"暂不可用"，而不是给一个看似精确的错数。
 */
@Service
public class AirspaceDiffService {
    private final AccessControlService access;
    private final AirspaceWriteRepository repository;

    public AirspaceDiffService(AccessControlService access, AirspaceWriteRepository repository) {
        this.access = access; this.repository = repository;
    }

    @Transactional(readOnly = true)
    public VersionDiffDto diff(String airspaceId, String fromVersionId, String toVersionId) {
        AccessDecision decision = access.require(PermissionCode.AIRSPACE_READ);
        String id = AirspaceWriteService.identifier(airspaceId);
        // 只读事务里不能加行锁（PostgreSQL 直接报错）：这里只需要判断这片空域对当前用户是否可见。
        if (repository.findAirspace(id, AirspaceWriteService.scopeUser(decision)) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "空域不存在或不可见");
        }
        VersionRow from = version(id, fromVersionId), to = version(id, toVersionId);
        List<DiffFieldDto> fields = new ArrayList<>();
        field(fields, "kind_code", from.kindCode(), to.kindCode());
        field(fields, "min_altitude_m", text(from.minAltitudeM()), text(to.minAltitudeM()));
        field(fields, "max_altitude_m", text(from.maxAltitudeM()), text(to.maxAltitudeM()));
        field(fields, "altitude_datum", from.altitudeDatum(), to.altitudeDatum());
        field(fields, "valid_from", text(from.validFrom()), text(to.validFrom()));
        field(fields, "valid_to", text(from.validTo()), text(to.validTo()));
        field(fields, "change_reason", from.changeReason(), to.changeReason());
        GeometryDiff geometry = repository.geometryDiff(from.airspaceVersionId(), to.airspaceVersionId());
        DiffGeometryDto geometryDto = geometry.available()
                ? new DiffGeometryDto(geometry.changed(), geometry.areaDeltaM2(), "AVAILABLE")
                : new DiffGeometryDto(false, null, "UNAVAILABLE");
        return new VersionDiffDto(id, from.airspaceVersionId(), to.airspaceVersionId(), from.versionNo(), to.versionNo(),
                List.copyOf(fields), geometryDto);
    }

    private VersionRow version(String airspaceId, String versionId) {
        VersionRow row = repository.findVersion(AirspaceWriteService.identifier(versionId));
        // 版本必须属于这个空域：否则可以拿别的空域的版本 ID 来比对，越权读到不可见空域的字段。
        if (row == null || !row.airspaceId().equals(airspaceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "空域版本不存在或不属于该空域");
        }
        return row;
    }

    private static void field(List<DiffFieldDto> fields, String name, String from, String to) {
        if (!Objects.equals(from, to)) fields.add(new DiffFieldDto(name, from, to));
    }

    private static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }

    private static String text(Instant value) { return value == null ? null : Long.toString(value.toEpochMilli()); }
}
