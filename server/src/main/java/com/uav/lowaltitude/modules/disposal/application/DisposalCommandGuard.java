package com.uav.lowaltitude.modules.disposal.application;

import java.util.Set;

import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;

/** 对已知授权的设备启动与现场核查、急停事务串行；外部协议授权保持既有兼容。 */
@Component
public class DisposalCommandGuard {
    private final EmergencyStopRepository stops;
    private final DisposalRepository authorizations;
    private final UavAdvisoryRepository advisory;
    private final DirectDisposalAccess directAccess;

    public DisposalCommandGuard(EmergencyStopRepository stops, DisposalRepository authorizations,
                                UavAdvisoryRepository advisory, DirectDisposalAccess directAccess) {
        this.stops = stops;
        this.authorizations = authorizations;
        this.advisory = advisory;
        this.directAccess = directAccess;
    }

    public boolean mayStart(String authorizationId) {
        var initial = authorizations.findUnlocked(authorizationId);
        if (initial != null && "UAV_EVENT".equals(initial.subjectKind())) {
            stops.lockEvent(initial.subjectId());
            if (Set.of("COUNTERMEASURE", "JAMMING").contains(initial.actionType())
                    && !advisory.records(initial.subjectId()).isEmpty()
                    && !advisory.counterBlockReason(initial.subjectId()).isEmpty()) return false;
        }
        String status = stops.lockAuthorizationStatus(authorizationId);
        if ("STOPPED".equals(status) || (status != null && stops.covered(authorizationId))) return false;
        // 取得授权锁后重新读，排队期间发生的过期、停用、撤权和范围变化必须在实际发送前生效。
        var current = authorizations.findUnlocked(authorizationId);
        if (current != null && "DIRECT".equals(current.authorizationMode())) {
            return status != null && Set.of("APPROVED", "EXECUTING").contains(status)
                    && directAccess.eligibleRequester(current, true) != null;
        }
        return initial == null || !"DIRECT".equals(initial.authorizationMode());
    }
}
