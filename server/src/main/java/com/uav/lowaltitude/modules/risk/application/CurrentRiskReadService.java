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
import com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** Current presence is a read projection, never a replacement for verification/notification state. */
@Service
public class CurrentRiskReadService {
    private static final Set<String> PARAMETERS=Set.of("plan_id","page","size","exclude_demo_samples");
    private final AccessControlService access;
    private final RiskRepository risks;
    private final WeatherRiskRepository weather;
    private final RiskReadService read;
    private final AppClock clock;

    public CurrentRiskReadService(AccessControlService access, RiskRepository risks,
            WeatherRiskRepository weather, RiskReadService read, AppClock clock) {
        this.access=access; this.risks=risks; this.weather=weather; this.read=read; this.clock=clock;
    }

    public record CurrentItem(RiskDto risk, String currentStatus, String currentReason) { }
    public record CurrentPage(List<CurrentItem> items, int page, int size, long total,
            long currentTotal, long uncertainTotal, long asOf) { }
    private record Candidate(RiskRow row, String status, String reason) { }

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
                .map(c -> new CurrentItem(read.dto(c.row()),c.status(),c.reason())).toList();
        return new CurrentPage(items,page.page(),page.size(),candidates.size(),current,unknown,now);
    }

    private Candidate classify(RiskRow row, long now) {
        if("EXCLUDED".equals(row.state())) return null;
        if("WEATHER".equals(row.riskType())) {
            var fact=weather.find(row.riskId());
            if(fact==null) return unknown(row,"缺少气象有效时段，当前影响待确认");
            if(fact.validFrom()>=fact.validTo() || fact.publishedAt()>now)
                return unknown(row,"气象依据时间异常，当前影响待确认");
            if(now<fact.validFrom()) return null;
            if(now>=fact.validTo()) return unknown(row,"气象依据已过期，当前影响待确认");
            return new Candidate(row,"CURRENT","气象风险仍在有效时段内");
        }
        // Space facts contain an evaluation window, not an expiry contract. Notifications and
        // recent ingestion do not prove current presence. Do not invent a TTL or a cleared state.
        return unknown(row,"缺少当前持续或解除依据，风险状态待确认");
    }
    private static Candidate unknown(RiskRow row,String reason) { return new Candidate(row,"UNKNOWN",reason); }
}
