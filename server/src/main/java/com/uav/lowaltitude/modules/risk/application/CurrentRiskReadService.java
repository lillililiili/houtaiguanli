package com.uav.lowaltitude.modules.risk.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskQuery;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.platform.time.AppClock;

/** Current presence is a read projection, never a replacement for verification/notification state. */
@Service
public class CurrentRiskReadService {
    private static final Set<String> PARAMETERS=Set.of("plan_id","page","size","exclude_demo_samples");
    private final AccessControlService access;
    private final RiskRepository risks;
    private final RiskPresenceService presence;
    private final RiskReadService read;
    private final AppClock clock;

    public CurrentRiskReadService(AccessControlService access, RiskRepository risks,
            RiskPresenceService presence, RiskReadService read, AppClock clock) {
        this.access=access; this.risks=risks; this.presence=presence; this.read=read; this.clock=clock;
    }

    public record CurrentItem(RiskDto risk, String currentStatus, String currentReason) { }
    public record CurrentPage(List<CurrentItem> items, int page, int size, long total,
            long currentTotal, long uncertainTotal, long asOf) { }
    private record Candidate(RiskRow row, RiskPresenceService.Presence presence) {
        String status(){return presence.status();}
        String reason(){return presence.reason();}
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CurrentPage list(MultiValueMap<String,String> values) {
        var scope=access.require(PermissionCode.RISK_READ);
        access.require(PermissionCode.FLIGHT_READ);
        if(values.keySet().stream().anyMatch(key -> !PARAMETERS.contains(key)))
            throw RiskReadService.Request.invalid("当前风险查询只支持计划、分页和样例筛选");
        var request=new RiskReadService.Request(values);
        String plan=RiskReadService.id(request.optional("plan_id",36));
        var page=request.page();
        int offset=page.offset();
        long now=clock.nowMillis();
        var query=new RiskQuery(null,null,plan,null,null,null,null,null,null,null,null,List.of(),request.excludeDemoSamples());
        // Presence is evaluated BEFORE pagination. No seven-day cutoff, first-50 filtering or history mutation.
        // Use the existing scoped query; all reads and counts belong to one repeatable-read snapshot.
        var candidates=new ArrayList<Candidate>();
        long current=0, unknown=0;
        for(var row:risks.listForExport(query,scope,Integer.MAX_VALUE,"occurred_at","desc")) {
            Candidate candidate=classify(row,now);
            if(candidate==null) continue;
            candidates.add(candidate);
            if("CURRENT".equals(candidate.status())) current++; else unknown++;
        }
        candidates.sort(Comparator.comparing(c -> "CURRENT".equals(c.status())?0:1));
        int start=Math.min(offset,candidates.size());
        int end=(int)Math.min((long)start+page.size(),candidates.size());
        var items=candidates.subList(start,end).stream()
                .map(c -> new CurrentItem(read.dto(c.row(),c.presence()),c.status(),c.reason())).toList();
        return new CurrentPage(items,page.page(),page.size(),candidates.size(),current,unknown,now);
    }

    private Candidate classify(RiskRow row, long now) {
        var current=presence.read(row,now);
        return Set.of("EXCLUDED","CLEARED","NOT_STARTED").contains(current.status())?null:new Candidate(row,current);
    }
}
