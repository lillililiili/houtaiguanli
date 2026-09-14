package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.AlarmInsert;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.AlarmLink;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository.TargetScope;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 可信来源告警入库（镜像 {@code RiskIngestionService}）：仅供规则引擎 C06 与人工转告警调用，没有 HTTP 入口。
 * 校验 → 锁来源 → 目标元组存在且目录启用 → (source_id, source_alarm_id) 幂等 → 插 alarm → 建 uav_event(PENDING_VERIFICATION)。
 */
@Service
public class AlarmIngestionService {
    public static final String ALARM_TYPE_RULE_LEGALITY = "RULE_LEGALITY";
    public static final String SOURCE_PREFIX = "rule-engine-legality-";
    private static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN");
    private static final Set<String> MODES = Set.of("mock", "replay", "live");
    /** detail 白名单：只允许研判摘要字段，禁止 input_snapshot、坐标、sn、用户文本等进入告警行。 */
    private static final Set<String> DETAIL_KEYS = Set.of("evaluation_id", "rule_set_id", "rule_set_version_id", "legal_status", "plan_match_code",
            "grade", "score", "violation_reasons", "trigger", "merge_kind");
    private final AlarmMergeRepository repository;
    private final UavEventRepository events;
    private final ObjectMapper objectMapper;
    private final com.uav.lowaltitude.platform.number.BusinessNumberService numbers;

    public AlarmIngestionService(AlarmMergeRepository repository, UavEventRepository events, ObjectMapper objectMapper,
            com.uav.lowaltitude.platform.number.BusinessNumberService numbers) {
        this.repository = repository; this.events = events; this.objectMapper = objectMapper; this.numbers = numbers;
    }

    /** 来源按目标 source_mode 取 rule-engine-legality-{mode}，避免回放/模拟研判产生的告警混入实测来源。 */
    public static String sourceIdFor(String sourceMode) { return SOURCE_PREFIX + sourceMode; }

    @Transactional
    public IngestResult ingest(TrustedAlarmFact fact) {
        validate(fact);
        String sourceId = fact.sourceId().trim(), sourceAlarmId = fact.sourceAlarmId().trim(), mode = fact.sourceMode().trim();
        // 以来源根行串行化同一 source 的入库；来源停用时拒绝，而不是静默降级。
        if (!repository.lockSource(sourceId, mode)) throw invalid("来源无效或未启用");
        TargetScope target = repository.targetScope(fact.targetId().trim(), mode);
        if (target == null) throw invalid("目标不存在、模式不一致或其组织/区域目录已停用");
        AlarmLink existing = repository.findAlarmBySource(sourceId, sourceAlarmId);
        if (existing != null) return new IngestResult(existing.alarmId(), existing.eventId(), false);
        String alarmId = UUID.randomUUID().toString();
        // 来源编号能给人看就沿用，技术键（引擎 eval:…）才取平台编号。
        String alarmNo = com.uav.lowaltitude.platform.number.BusinessNumberService.readable(sourceAlarmId) ? null
                : numbers.next(com.uav.lowaltitude.platform.number.BusinessNumberService.ALARM, fact.receivedAt().toInstant());
        repository.insertAlarm(new AlarmInsert(alarmId, target.targetId(), sourceId, sourceAlarmId, fact.alarmType().trim(), fact.severity().trim(),
                fact.occurredAt(), fact.receivedAt(), detailJson(fact.detail()), mode, target.ownerOrgId(), target.districtId(), alarmNo));
        String eventId = UUID.randomUUID().toString();
        // 同一告警只建一次事件：alarm_id 唯一约束兜底，重复时回读既有事件而不是报错。
        if (!events.createForAlarm(eventId, alarmId, PENDING_VERIFICATION, target.ownerOrgId(), target.districtId(), fact.receivedAt())) {
            eventId = repository.eventIdOfAlarm(alarmId);
        }
        return new IngestResult(alarmId, eventId, true);
    }

    private static void validate(TrustedAlarmFact f) {
        if (f == null) throw invalid("告警事实不能为空");
        required(f.sourceId(), 36, "来源"); required(f.sourceAlarmId(), 128, "来源告警 ID"); required(f.targetId(), 36, "目标");
        required(f.alarmType(), 64, "告警类型");
        if (f.receivedAt() == null) throw invalid("接收时间不能为空");
        if (f.severity() == null || !SEVERITIES.contains(f.severity().trim())) throw invalid("告警等级无效");
        if (f.sourceMode() == null || !MODES.contains(f.sourceMode().trim())) throw invalid("来源模式无效");
        if (!sourceIdFor(f.sourceMode().trim()).equals(f.sourceId().trim())) throw invalid("来源与模式不一致");
        if (f.detail() != null) for (String key : f.detail().keySet()) if (!DETAIL_KEYS.contains(key)) throw invalid("告警明细字段不在白名单内：" + key);
    }

    private String detailJson(Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (detail != null) detail.forEach((key, value) -> { if (value != null) safe.put(key, value); });
        try { return objectMapper.writeValueAsString(safe); }
        catch (JsonProcessingException ex) { throw invalid("告警明细无法序列化"); }
    }

    private static void required(String value, int max, String field) { if (value == null || value.trim().isEmpty() || value.trim().length() > max) throw invalid(field + "无效"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALARM_FACT", message); }

    /** created=false 表示 (source_id, source_alarm_id) 已存在，返回既有告警与事件。 */
    public record IngestResult(String alarmId, String eventId, boolean created) { }
}
