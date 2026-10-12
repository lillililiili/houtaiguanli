package com.uav.lowaltitude.modules.alarm.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;

/** 历史自动授权夹具，仅用于保留记录的读取和重试阻断检证。 */
class AlarmRuleCounterTest {
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");
    static DisposalRepository.AuthorizationRow authorization(String id, String status, String requester, String commandId,
            String resultCode) {
        OffsetDateTime from = NOW.minusSeconds(30).atOffset(ZoneOffset.UTC);
        return new DisposalRepository.AuthorizationRow(id, "AUTH-" + id, DisposalRules.COUNTERMEASURE, "UAV_EVENT", "event-1",
                "target-1", "cm4", DisposalRules.COUNTERMEASURE_4CH, "反制规则已满足，系统自动发起。", requester, from, null, null,
                null, from, from.plusMinutes(10), status, commandId, resultCode, null, "demo-v1", "org", "district", "mock",
                1L, null, null, AutomationPrincipal.USER_ID.equals(requester) ? "DIRECT" : "REVIEW");
    }

}
