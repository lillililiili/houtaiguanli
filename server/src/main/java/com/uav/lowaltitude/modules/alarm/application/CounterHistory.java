package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;

/**
 * 这起事件已有的反制授权决定自动反制这一轮能做什么：还没有就新发；有一条设备忙时建好、
 * 还没发出去的自动反制，就等设备空了补发它；以前的自动反制都没发出去（启动指令下发前被取消，
 * 或一直没下发就过期）时再发一次，一起事件最多自动发 {@link #MAX_ATTEMPTS} 次（第二批复验 告警-020）。
 * 别的情况（有人发起过、已经在反制、反制过、被停过、设备回过失败或超时）不再自动发。
 */
record CounterHistory(Next next, AuthorizationRow queued, int attempt) {
    enum Next { LAUNCH, DISPATCH_QUEUED, NOTHING }

    static final int MAX_ATTEMPTS = 3;
    /** 启动指令在发出去之前被取消（设备没收到）的结果码，见 Countermeasure4ChControlService.dispatch。 */
    static final Set<String> NOT_SENT = Set.of("AUTHORIZATION_STOPPED", "DEVICE_NOT_OPERABLE");

    static CounterHistory of(List<AuthorizationRow> rows, OffsetDateTime now) {
        AuthorizationRow queued = null;
        int unsent = 0;
        for (AuthorizationRow row : rows) {
            if (!automatic(row)) return nothing();
            if (waiting(row, now)) {
                if (queued != null) return nothing();
                queued = row;
            } else if (neverSent(row)) {
                unsent++;
            } else {
                return nothing();
            }
        }
        if (queued != null) return new CounterHistory(Next.DISPATCH_QUEUED, queued, unsent + 1);
        if (unsent >= MAX_ATTEMPTS) return nothing();
        return new CounterHistory(Next.LAUNCH, null, unsent + 1);
    }

    /** 系统自动发起、已直接授权、一次也没下发出去、仍在有效期内。 */
    static boolean waiting(AuthorizationRow row, OffsetDateTime now) {
        return automatic(row) && DisposalRules.APPROVED.equals(row.status()) && row.executionCommandId() == null
                && row.validFrom() != null && row.validUntil() != null
                && !now.isBefore(row.validFrom()) && now.isBefore(row.validUntil());
    }

    /** 设备没收到过它的启动指令。设备回过失败、超时的不算：设备可能动过，要人核查。 */
    static boolean neverSent(AuthorizationRow row) {
        if (DisposalRules.FAILED.equals(row.status())) return NOT_SENT.contains(row.resultCode());
        return (DisposalRules.EXPIRED.equals(row.status()) || DisposalRules.APPROVED.equals(row.status()))
                && row.executionCommandId() == null;
    }

    static boolean automatic(AuthorizationRow row) {
        return AutomationPrincipal.USER_ID.equals(row.requestedBy()) && "DIRECT".equals(row.authorizationMode());
    }

    private static CounterHistory nothing() { return new CounterHistory(Next.NOTHING, null, 0); }
}
