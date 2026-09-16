package com.uav.lowaltitude.modules.disposal.application;

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
    public DisposalCommandGuard(EmergencyStopRepository stops,DisposalRepository authorizations,UavAdvisoryRepository advisory) {
        this.stops=stops;this.authorizations=authorizations;this.advisory=advisory;
    }
    public boolean mayStart(String authorizationId) {
        var initial=authorizations.findUnlocked(authorizationId);
        if(initial!=null && "UAV_EVENT".equals(initial.subjectKind())) {
            stops.lockEvent(initial.subjectId());
            if(java.util.Set.of("COUNTERMEASURE","JAMMING").contains(initial.actionType())
                    && !advisory.records(initial.subjectId()).isEmpty()
                    && !advisory.counterBlockReason(initial.subjectId()).isEmpty()) return false;
        }
        String status=stops.lockAuthorizationStatus(authorizationId);
        return status==null || (!"STOPPED".equals(status) && !stops.covered(authorizationId));
    }
}
