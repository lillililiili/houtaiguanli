package com.uav.lowaltitude.modules.assessment.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RatioDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RuleEffectFactDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RuleEffectSummaryDto;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService.Page;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService.Request;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService.TimeRange;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.FactQuery;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.FactRow;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.SummaryCounts;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 规则效果事实与汇总（assessment:read + rule:read）。汇总在同一权限范围、同一时间窗口、同一运行模式（默认 ACTIVE）内计算；
 * 比率分母为 0 时返回 {value:null, availability:NO_DENOMINATOR}，不用 0 冒充“没有误报”。
 */
@Service
public class RuleEffectReadService {
    private static final Set<String> MODES = Set.of("ACTIVE", "SHADOW");
    private static final Set<String> LEGAL_STATUSES = Set.of("LEGAL", "ABNORMAL", "ILLEGAL", "UNDETERMINED", "NOT_APPLICABLE");
    private static final Set<String> REVIEW_STATES = Set.of("PENDING_REVIEW", "CONFIRMED", "REJECTED", "OVERRIDDEN", "SUPERSEDED");
    private static final Set<String> SOURCE_MODES = Set.of("mock", "replay", "live");
    private static final Set<String> FACT_PARAMS = Set.of("from", "to", "mode", "legal_status", "review_state", "source_mode", "owner_org_id", "district_id", "page", "size");
    private static final Set<String> SUMMARY_PARAMS = Set.of("from", "to", "timezone", "mode", "source_mode", "owner_org_id", "district_id");
    /** 事实与汇总默认只看 ACTIVE：影子研判只有显式 mode=SHADOW 才计入，不能悄悄混进分母。 */
    private static final String DEFAULT_MODE = "ACTIVE";
    private static final int RATIO_SCALE = 4;
    private final AccessControlService access;
    private final LegalityEvaluationReadRepository repository;

    public RuleEffectReadService(AccessControlService access, LegalityEvaluationReadRepository repository) {
        this.access = access; this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageDto<RuleEffectFactDto> facts(MultiValueMap<String, String> values) {
        AccessDecision decision = requireBoth();
        Request request = new Request(values, FACT_PARAMS);
        Page page = request.page();
        TimeRange range = request.timeRange("from", "to");
        FactQuery query = new FactQuery(range.from(), range.to(), mode(request), request.enumerated("legal_status", LEGAL_STATUSES),
                request.enumerated("review_state", REVIEW_STATES), request.enumerated("source_mode", SOURCE_MODES),
                request.optional("owner_org_id", 36), request.optional("district_id", 36));
        long total = repository.countFacts(query, decision);
        // 事实行的目标/计划/告警引用按各自读权限脱敏；无权限时字段省略而不是整行不可见。
        boolean target = has(PermissionCode.TARGET_READ), plan = has(PermissionCode.FLIGHT_READ), alarm = has(PermissionCode.ALARM_READ);
        return new PageDto<>(repository.facts(query, decision, page.offset(), page.size()).stream().map(row -> dto(row, target, plan, alarm)).toList(), page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public RuleEffectSummaryDto summary(MultiValueMap<String, String> values) {
        AccessDecision decision = requireBoth();
        Request request = new Request(values, SUMMARY_PARAMS);
        TimeRange range = request.timeRange("from", "to");
        if (range.from() == null) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "汇总必须给出 from 与 to");
        String timezone = timezone(request.optional("timezone", 64));
        String mode = mode(request);
        FactQuery query = new FactQuery(range.from(), range.to(), mode, null, null, request.enumerated("source_mode", SOURCE_MODES),
                request.optional("owner_org_id", 36), request.optional("district_id", 36));
        SummaryCounts c = repository.summary(query, decision);
        return new RuleEffectSummaryDto(range.from().toInstant().toEpochMilli(), range.to().toInstant().toEpochMilli(), timezone, mode,
                c.evaluations(), c.alarmWorthy(), c.alarmsCreated(), c.alarmsMerged(), ratio(c.alarmsCreated(), c.alarmWorthy()), c.reviewed(),
                ratio(c.rejected(), c.reviewed()), ratio(c.missed(), c.reviewed()), ratio(c.rejected() + c.overridden(), c.reviewed()));
    }

    private static String mode(Request request) {
        String mode = request.enumerated("mode", MODES);
        return mode == null ? DEFAULT_MODE : mode;
    }

    private AccessDecision requireBoth() {
        AccessDecision decision = access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.RULE_READ);
        return decision;
    }

    /** timezone 只做合法性校验并原样回显，供页面按本地日历解释 from/to；分母口径不受时区影响。 */
    private static String timezone(String value) {
        if (value == null) return "UTC";
        try { return ZoneId.of(value).getId(); } catch (DateTimeException ex) { throw Request.invalid("timezone 参数无效"); }
    }

    static RatioDto ratio(long numerator, long denominator) {
        if (denominator <= 0) return new RatioDto(null, RatioDto.NO_DENOMINATOR);
        return new RatioDto(BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP), RatioDto.AVAILABLE);
    }

    private boolean has(PermissionCode permission) { try { access.require(permission); return true; } catch (ApiException ignored) { return false; } }

    private static RuleEffectFactDto dto(FactRow row, boolean target, boolean plan, boolean alarm) {
        return new RuleEffectFactDto(row.evaluationId(), row.evaluatedAt().toInstant().toEpochMilli(), row.mode(), row.subjectKind(), target ? row.targetId() : null,
                plan ? row.planId() : null, row.ruleSetVersionId(), row.paramStatus(), row.legalStatus(), row.manualStatus(), row.reviewState(), row.hasAlarm(),
                row.mergeKind(), alarm ? row.alarmId() : null, alarm ? row.groupId() : null, row.supersedesEvaluationId(), row.sourceMode(), row.ownerOrgId(), row.districtId());
    }
}
