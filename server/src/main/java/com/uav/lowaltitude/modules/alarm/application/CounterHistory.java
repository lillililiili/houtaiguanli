package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;

/**
 * 这起事件已有的反制授权决定自动反制这一轮能做什么：还没有就新发；只有一条设备忙时建好、
 * 还没发出去的自动反制，就等设备空了补发它；别的情况（有人发起过、已经在反制、反制过、被停过）不再自动发。
 */
record CounterHistory(Next next, AuthorizationRow queued) {
    enum Next { LAUNCH, DISPATCH_QUEUED, NOTHING }

    static CounterHistory of(List<AuthorizationRow> rows, OffsetDateTime now) {
        if (rows.isEmpty()) return new CounterHistory(Next.LAUNCH, null);
        if (rows.size() == 1 && waiting(rows.get(0), now)) return new CounterHistory(Next.DISPATCH_QUEUED, rows.get(0));
        return new CounterHistory(Next.NOTHING, null);
    }

    /** 系统自动发起、已直接授权、一次也没下发出去、仍在有效期内。 */
    static boolean waiting(AuthorizationRow row, OffsetDateTime now) {
        return automatic(row) && DisposalRules.APPROVED.equals(row.status()) && row.executionCommandId() == null
                && row.validFrom() != null && row.validUntil() != null
                && !now.isBefore(row.validFrom()) && now.isBefore(row.validUntil());
    }

    static boolean automatic(AuthorizationRow row) {
        return AutomationPrincipal.USER_ID.equals(row.requestedBy()) && "DIRECT".equals(row.authorizationMode());
    }
}
