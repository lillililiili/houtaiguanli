package com.uav.lowaltitude.modules.fusion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionMetricsRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionMetricsRepository.DailyRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

/**
 * GET /api/v1/fusion/metrics/daily?domain&from&to（fusion:read）：返回视图 fusion_effect_daily 的行。
 * 比率分母为 0 时 value=null 并给 availability（帧口径 NO_DENOMINATOR；依赖回放真值的口径 NO_GROUND_TRUTH），
 * 不用 0 冒充“无中断/无重复/全部正确”。
 */
@RestController
@RequestMapping("/api/v1/fusion/metrics")
public class FusionMetricsController {
    private static final Set<String> PARAMETERS = Set.of("domain", "from", "to");
    private static final Pattern DOMAIN_KEY = Pattern.compile("^(mock|replay|live)\\|[A-Za-z0-9_.:@+-]{0,36}\\|[A-Za-z0-9_.:@+-]{0,36}$");
    private final AccessControlService access;
    private final FusionMetricsRepository repository;

    public FusionMetricsController(AccessControlService access, FusionMetricsRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @GetMapping("/daily")
    @Transactional(readOnly = true)
    public ApiResponse<DailyMetricsDto> daily(@RequestParam MultiValueMap<String, String> values) {
        // 鉴权先于参数解析，避免用 400 差异探测接口。
        AccessDecision decision = access.require(PermissionCode.FUSION_READ);
        values.keySet().stream().filter(key -> !PARAMETERS.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        OffsetDateTime from = epoch(values, "from"), to = epoch(values, "to");
        if (from == null || to == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "必须给出 from 与 to");
        if (!from.isBefore(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效");
        String domain = single(values, "domain");
        if (domain != null && !DOMAIN_KEY.matcher(domain).matches()) throw invalid("domain 参数无效");
        // 窗口 [from,to) 按 UTC 日历日折算成闭区间 [from 日, (to-1ms) 日]，与视图的 UTC 日历日口径一致。
        LocalDate fromDay = from.toLocalDate(), toDay = to.minusNanos(1_000_000).toLocalDate();
        List<DailyMetricsRowDto> items = repository.daily(domain, fromDay, toDay, decision).stream().map(FusionMetricsController::dto).toList();
        return ApiResponse.ok(new DailyMetricsDto(from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli(), domain, items));
    }

    private static DailyMetricsRowDto dto(DailyRow r) {
        return new DailyMetricsRowDto(r.fusionDomainKey(), r.sourceMode(), r.ownerOrgId(), r.districtId(), r.day().toString(), r.trackedTargets(),
                r.idSwitchCount(), r.shortLostFrames(), r.totalFrames(), ratio(r.interruptRate(), r.totalFrames(), MetricRatioDto.NO_DENOMINATOR),
                r.duplicateTargets(), r.truthTargets(), ratio(r.duplicateTargetRate(), r.truthTargets(), MetricRatioDto.NO_GROUND_TRUTH),
                r.correctAssociations(), r.totalAssociations(), ratio(r.associationAccuracy(), r.totalAssociations(), MetricRatioDto.NO_GROUND_TRUTH));
    }

    private static MetricRatioDto ratio(BigDecimal value, long denominator, String unavailable) {
        if (denominator <= 0 || value == null) return new MetricRatioDto(null, unavailable);
        return new MetricRatioDto(value, MetricRatioDto.AVAILABLE);
    }

    private static OffsetDateTime epoch(MultiValueMap<String, String> values, String name) {
        String text = single(values, name);
        if (text == null) return null;
        try { return Instant.ofEpochMilli(Long.parseLong(text)).atOffset(ZoneOffset.UTC); }
        catch (NumberFormatException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }
    }

    private static String single(MultiValueMap<String, String> values, String name) {
        if (!values.containsKey(name)) return null;
        List<String> found = values.get(name);
        if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效");
        return found.get(0).trim();
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }

    public record DailyMetricsDto(long from, long to, String domain, List<DailyMetricsRowDto> items) { }
    public record DailyMetricsRowDto(String fusionDomainKey, String sourceMode, String ownerOrgId, String districtId, String day, long trackedTargets,
            long idSwitchCount, long shortLostFrames, long totalFrames, MetricRatioDto interruptRate, long duplicateTargets, long truthTargets,
            MetricRatioDto duplicateTargetRate, long correctAssociations, long totalAssociations, MetricRatioDto associationAccuracy) { }
    /** value 显式为 null（全局忽略 null 的例外）：页面要能区分“0”与“没有分母”。 */
    public record MetricRatioDto(@JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal value, String availability) {
        public static final String AVAILABLE = "AVAILABLE";
        public static final String NO_DENOMINATOR = "NO_DENOMINATOR";
        public static final String NO_GROUND_TRUTH = "NO_GROUND_TRUTH";
    }
}
