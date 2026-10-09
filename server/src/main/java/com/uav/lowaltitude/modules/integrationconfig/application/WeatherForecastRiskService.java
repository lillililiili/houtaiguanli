package com.uav.lowaltitude.modules.integrationconfig.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.flight.api.FlightDtos.FlightPlanDto;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.Period;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.WeatherInput;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.WeatherForecastRiskRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.WeatherForecastRiskRepository.PlanCandidate;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 天气预报规则的唯一入口：区域和计划时段同时命中，才生成待核验风险。
 * 这里只提出风险，不确认风险，也不触发通知或处置。
 */
@Service
@Profile(SimulationPolicy.PROFILE)
public class WeatherForecastRiskService {
    public static final String RULE_VERSION = "WEATHER-RULE-V1";
    private static final double STRONG_WIND_MS = 10.0;
    private static final double STRONG_GUST_MS = 15.0;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    /** 依据说明里写中文，不写规则代码（CDX-P06）。 */
    private static final Map<String, String> RULE_TEXT = Map.of("WEATHER_THUNDERSTORM", "雷雨",
            "WEATHER_STRONG_WIND", "大风", "WEATHER_LOW_VISIBILITY", "低能见度");

    private final WeatherForecastRiskRepository repository;
    private final RiskIngestionService risks;
    private final AccessControlService access;
    private final FlightReadService flights;
    private final AppClock clock;

    public WeatherForecastRiskService(WeatherForecastRiskRepository repository, RiskIngestionService risks,
            AccessControlService access, FlightReadService flights, AppClock clock) {
        this.repository = repository;
        this.risks = risks;
        this.access = access;
        this.flights = flights;
        this.clock = clock;
    }

    @Transactional
    public int evaluate(WeatherInput input, String messageId) {
        List<PlanCandidate> plans;
        if (input.planId() != null && !input.planId().isBlank()) {
            FlightPlanDto plan = flights.flightPlan(input.planId());
            plans = areaMatches(input.areaName(), plan.districtName())
                    ? List.of(new PlanCandidate(plan.planId(), plan.route().routeVersionId(), plan.sourceMode(),
                            plan.startAt(), plan.endAt(), plan.districtName()))
                    : List.of();
        } else {
            AccessDecision decision = access.require(PermissionCode.FLIGHT_READ);
            plans = repository.plans(input.areaName(), decision);
        }

        int created = 0;
        OffsetDateTime receivedAt = clock.now().atOffset(ZoneOffset.UTC);
        for (PlanCandidate plan : plans) {
            for (RuleHit hit : hits(input.periods(), plan.startAt(), plan.endAt())) {
                String sourceId = "weather-forecast-rule-" + plan.sourceMode();
                String key = sourceKey(input, plan, hit);
                // 来源编号带冒号是技术键，风险才会取平台编号“风险-MMDD-NNN”（CDX-P06）；
                // 10 月 8 日前用的是“forecast-”开头的编号，同一预报再交时沿用那条风险，不再多生成一条。
                String legacy = "forecast-" + key;
                String sourceRiskId = repository.riskExists(sourceId, legacy) ? legacy : "forecast:" + key;
                OffsetDateTime occurredAt = instant(hit.period().from());
                String reasonText = reason(input, plan, hit);
                String riskId = risks.ingest(new TrustedRiskFact(sourceId, sourceRiskId, plan.planId(),
                        plan.routeVersionId(), null, null, null, "WEATHER", hit.severity(), hit.ruleCode(),
                        reasonText, occurredAt, receivedAt, null, null, plan.sourceMode()));
                repository.insertFact(riskId, messageId, input.areaName(), instant(input.publishedAt()), occurredAt,
                        instant(hit.period().to()), hit.period().summary(), hit.period().windSpeedMs(),
                        hit.period().gustMs(), hit.period().precipitationProbabilityPct(), hit.ruleCode(),
                        RULE_VERSION, plan.sourceMode());
                created++;
            }
        }
        return created;
    }

    private static List<RuleHit> hits(List<Period> periods, long planStart, long planEnd) {
        Map<String, Period> windows = new LinkedHashMap<>();
        Map<String, List<String>> rules = new LinkedHashMap<>();
        for (Period period : periods) {
            if (period.from() >= planEnd || period.to() <= planStart) continue;
            String text = period.summary().toLowerCase(Locale.ROOT);
            String window = period.from() + "|" + period.to();
            windows.putIfAbsent(window, period);
            if (containsAny(text, "雷雨", "雷暴", "强对流", "雷电")) {
                rules.computeIfAbsent(window, ignored -> new ArrayList<>()).add("WEATHER_THUNDERSTORM");
            }
            if (period.windSpeedMs() >= STRONG_WIND_MS || period.gustMs() >= STRONG_GUST_MS) {
                rules.computeIfAbsent(window, ignored -> new ArrayList<>()).add("WEATHER_STRONG_WIND");
            }
            // 当前预报输入没有独立能见度数值，先用预报摘要中的通用天气词触发。
            if (containsAny(text, "低能见度", "大雾", "浓雾", "雾")) {
                rules.computeIfAbsent(window, ignored -> new ArrayList<>()).add("WEATHER_LOW_VISIBILITY");
            }
        }
        List<RuleHit> hits = new ArrayList<>();
        for (var entry : rules.entrySet()) {
            List<String> matched = entry.getValue();
            String trigger = String.join("、", matched.stream().map(RULE_TEXT::get).toList());
            String code = matched.size() == 1 ? matched.get(0) : "WEATHER_MULTI";
            String severity = matched.stream().anyMatch(rule -> !"WEATHER_LOW_VISIBILITY".equals(rule)) ? "HIGH" : "MEDIUM";
            hits.add(new RuleHit(code, trigger, severity, windows.get(entry.getKey())));
        }
        return hits;
    }

    private static String reason(WeatherInput input, PlanCandidate plan, RuleHit hit) {
        return "天气预报：" + hit.triggerText() + "；区域 " + input.areaName() + "；时段 "
                + window(hit.period().from(), hit.period().to()) + "（北京时间）"
                + "。当前状态为待核验，人工确认前不发送通知、不执行处置。";
    }

    /** 例如“10月7日 16:30–17:00”；跨天时结束时间也写日期。 */
    static String window(long from, long to) {
        ZonedDateTime start = Instant.ofEpochMilli(from).atZone(BEIJING), end = Instant.ofEpochMilli(to).atZone(BEIJING);
        String endText = start.toLocalDate().equals(end.toLocalDate()) ? CLOCK.format(end) : day(end) + " " + CLOCK.format(end);
        return day(start) + " " + CLOCK.format(start) + "–" + endText;
    }

    private static String day(ZonedDateTime time) {
        return time.getMonthValue() + "月" + time.getDayOfMonth() + "日";
    }

    private static String sourceKey(WeatherInput input, PlanCandidate plan, RuleHit hit) {
        String key = plan.sourceMode() + "|" + plan.planId() + "|" + normalize(input.areaName()) + "|"
                + input.publishedAt() + "|" + hit.period().from() + "|" + hit.period().to() + "|" + hit.ruleCode();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean areaMatches(String area, String district) {
        String a = normalize(area), d = normalize(district);
        return !a.isEmpty() && !d.isEmpty() && (a.equals(d) || a.contains(d) || d.contains(a));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static OffsetDateTime instant(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);
    }

    private record RuleHit(String ruleCode, String triggerText, String severity, Period period) { }
}
