package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 可信来源告警事实：只由平台内部用例（规则引擎 C06、人工转告警）构造，没有 HTTP 入口。
 * detail 只允许白名单键（见 {@link AlarmIngestionService}），不得携带 input_snapshot、坐标或用户文本。
 */
public record TrustedAlarmFact(String sourceId, String sourceAlarmId, String targetId, String alarmType, String severity,
        OffsetDateTime occurredAt, OffsetDateTime receivedAt, Map<String, Object> detail, String sourceMode) {
}
