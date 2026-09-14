package com.uav.lowaltitude.modules.assessment.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.assessment.api.LegalityDtos.CheckDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityDtos.LegalityAssessmentDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class LegalityReadService {
    private final AccessControlService access; private final LegalityReadRepository repository; private final ObjectMapper json;
    public LegalityReadService(AccessControlService access, LegalityReadRepository repository, ObjectMapper json) { this.access = access; this.repository = repository; this.json = json; }

    @Transactional(readOnly = true)
    public PageDto<LegalityAssessmentDto> history(String planId, MultiValueMap<String, String> parameters) {
        // 先同时校验计划和研判动作，才接触路径 ID；双权限不能降级为任一单项许可。
        AccessDecision flightAccess = access.require(PermissionCode.FLIGHT_READ); access.require(PermissionCode.ASSESSMENT_READ);
        String id = id(planId); Page page = page(parameters);
        if (!repository.visiblePlan(id, flightAccess)) throw notFound();
        long total = repository.countForPlan(id, flightAccess);
        return new PageDto<>(repository.forPlan(id, flightAccess, page.offset(), page.size()).stream().map(this::dto).toList(), page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public LegalityAssessmentDto detail(String assessmentId) {
        AccessDecision assessmentAccess = access.require(PermissionCode.ASSESSMENT_READ);
        LegalityReadRepository.Row row = repository.find(id(assessmentId), assessmentAccess);
        // Repository 已应用完整数据范围；越权与真实不存在统一 404，避免用研判 ID 探测跨域记录。
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "LEGALITY_ASSESSMENT_NOT_FOUND", "合法性研判不存在");
        return dto(row);
    }

    private LegalityAssessmentDto dto(LegalityReadRepository.Row row) {
        return new LegalityAssessmentDto(row.assessmentId(), row.planId(), row.targetId(), row.trackId(), row.routeVersionId(), row.ruleVersionId(), row.ruleVersionCode(), row.assessedAt().toInstant().toEpochMilli(), row.conclusionCode(), checks(row.checks()), strings(row.unknownReasons()), strings(row.evidenceReferences()), row.sourceMode());
    }
    private List<CheckDto> checks(String text) {
        try { JsonNode root = array(text); List<CheckDto> output = new ArrayList<>(); for (JsonNode item : root) { if (!item.isObject() || item.path("rule_code").asText().isBlank()) throw invalidStoredJson(); String result = item.path("result_code").asText(); if (!Set.of("PASS", "FAIL", "UNDETERMINED", "NOT_APPLICABLE").contains(result)) throw invalidStoredJson(); output.add(new CheckDto(item.path("rule_code").asText(), result, item.path("reason_code").isMissingNode() ? null : item.path("reason_code").asText(null))); } return List.copyOf(output); } catch (ApiException ex) { throw ex; } catch (Exception ex) { throw invalidStoredJson(); }
    }
    private List<String> strings(String text) { try { JsonNode root = array(text); List<String> output = new ArrayList<>(); for (JsonNode item : root) { if (!item.isTextual()) throw invalidStoredJson(); output.add(item.textValue()); } return List.copyOf(output); } catch (ApiException ex) { throw ex; } catch (Exception ex) { throw invalidStoredJson(); } }
    private JsonNode array(String text) throws Exception { JsonNode root = json.readTree(text); if (root.isTextual()) root = json.readTree(root.textValue()); if (!root.isArray()) throw invalidStoredJson(); return root; }
    private static ApiException invalidStoredJson() { return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "已保存研判数据格式无效"); }
    private static String id(String value) { String normalized = value == null ? "" : value.trim(); if (normalized.isEmpty() || normalized.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效"); return normalized; }
    private static Page page(MultiValueMap<String, String> parameters) {
        parameters.keySet().stream().filter(key -> !Set.of("page", "size").contains(key)).findFirst().ifPresent(key -> { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", key + " 参数无效"); });
        int page = integer(parameters, "page", 1); int size = integer(parameters, "size", 20); if (page < 1 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效"); return new Page(page, size);
    }
    private static int integer(MultiValueMap<String, String> parameters, String key, int fallback) { if (!parameters.containsKey(key)) return fallback; List<String> values = parameters.get(key); try { if (values == null || values.size() != 1 || values.get(0).isBlank()) throw new NumberFormatException(); return Integer.parseInt(values.get(0)); } catch (RuntimeException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效"); } }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_PLAN_NOT_FOUND", "飞行计划不存在"); }
    private record Page(int page, int size) { int offset() { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效"); } } }
}
