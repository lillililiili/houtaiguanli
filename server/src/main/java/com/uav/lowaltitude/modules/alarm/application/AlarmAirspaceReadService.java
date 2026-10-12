package com.uav.lowaltitude.modules.alarm.application;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AirspaceEvaluationReference;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.VersionContextDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceReadService;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 告警首次及升级发生时的已保存命中，绝不用当前空域或目标最新研判重算。 */
@Service
public class AlarmAirspaceReadService {
    private final AccessControlService access;
    private final AlarmReadRepository repository;
    private final LegalityEvaluationReadService evaluations;
    private final AirspaceReadService airspaces;
    private final ObjectMapper json;
    private static final Set<String> AIRSPACE_RULES = Set.of("C02-1", "C02-2", "C02-8");

    public AlarmAirspaceReadService(AccessControlService access, AlarmReadRepository repository,
            LegalityEvaluationReadService evaluations, AirspaceReadService airspaces, ObjectMapper json) {
        this.access = access; this.repository = repository; this.evaluations = evaluations; this.airspaces = airspaces; this.json = json;
    }
    public record Item(int occurrence, long evaluatedAt, Long observedAt, String ruleCode, String reasonCode, VersionContextDto airspace) { }
    public record Result(String status, List<Item> items) { }

    @Transactional(readOnly = true)
    public Result read(String alarmId) {
        var decision = access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.AIRSPACE_READ);
        access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.TARGET_READ);
        var alarm = repository.find(alarmId, decision);
        if (alarm == null) throw new ApiException(HttpStatus.NOT_FOUND, "ALARM_NOT_FOUND", "告警不存在");
        List<AirspaceEvaluationReference> refs = new ArrayList<>();
        String original = originalEvaluation(alarm.detailJson());
        if (original != null) refs.add(new AirspaceEvaluationReference(original, 0));
        refs.addAll(repository.airspaceEvaluationReferences(alarmId));
        if (refs.isEmpty()) return new Result("UNKNOWN", List.of());
        boolean incomplete = original == null;
        List<Item> items = new ArrayList<>();
        for (var ref : refs) {
            if (ref.evaluationId() == null) { incomplete = true; continue; }
            try {
                var evaluation = evaluations.detail(ref.evaluationId());
                // 研判自身范围与目标引用均由既有读取服务校验，再核对本告警的来源/元组。
                if (evaluation.targetId() == null || !Objects.equals(evaluation.targetId(), alarm.targetId())
                        || !Objects.equals(evaluation.ownerOrgId(), alarm.ownerOrgId())
                        || !Objects.equals(evaluation.districtId(), alarm.districtId())
                        || !Objects.equals(evaluation.sourceMode(), alarm.sourceMode()) || !"ACTIVE".equals(evaluation.mode())
                        || evaluation.hitDetails() == null || evaluation.hitDetails().isEmpty()) {
                    incomplete = true; continue;
                }
                for (var hit : evaluation.hitDetails()) {
                    if (!AIRSPACE_RULES.contains(hit.ruleCode()) || !"FAIL".equals(hit.resultCode())) continue;
                    Object versionId = hit.facts().get("airspace_version_id"), spaceId = hit.facts().get("airspace_id");
                    if (!(versionId instanceof String id) || id.isBlank() || !(spaceId instanceof String)) { incomplete = true; continue; }
                    try {
                        var context = airspaces.versionContext(id);
                        if (!Objects.equals(spaceId, context.airspaceId()) || !Objects.equals(spaceId, context.version().airspaceId())) {
                            incomplete = true; continue;
                        }
                        items.add(new Item(ref.occurrence(), evaluation.evaluatedAt(), evaluation.observedAt(), hit.ruleCode(), hit.reasonCode(), context));
                    } catch (ApiException ex) {
                        if (ex.getStatus() != HttpStatus.NOT_FOUND && ex.getStatus() != HttpStatus.FORBIDDEN) throw ex;
                        incomplete = true;
                    }
                }
            } catch (ApiException ex) {
                if (ex.getStatus() != HttpStatus.NOT_FOUND && ex.getStatus() != HttpStatus.FORBIDDEN) throw ex;
                incomplete = true;
            }
        }
        return new Result(incomplete ? "PARTIAL" : items.isEmpty() ? "NO_HIT" : "AVAILABLE", List.copyOf(items));
    }

    private String originalEvaluation(String detail) {
        if (detail == null || detail.isBlank()) return null;
        try {
            var node = json.readTree(detail);
            if (node.isTextual()) node = json.readTree(node.asText());
            String id = node.path("evaluation_id").asText(null);
            return id == null || id.isBlank() ? null : id;
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_STORED_JSON", "告警历史依据读取失败");
        }
    }
}
