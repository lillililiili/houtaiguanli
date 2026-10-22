package com.uav.lowaltitude.modules.reporting.application;

import java.time.LocalDate;
import java.util.List;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.Result;
import com.uav.lowaltitude.modules.reporting.infrastructure.ObservationMetricsRepository;
import com.uav.lowaltitude.modules.reporting.infrastructure.ReportingRepository.Scope;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.query.StatisticsScope;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class ObservationMetricsService {
    private final ObservationMetricsRepository repository;
    private final AccessControlService access;
    private final AppClock clock;
    private final StatisticsScope statistics;
    public ObservationMetricsService(ObservationMetricsRepository repository, AccessControlService access, AppClock clock, StatisticsScope statistics) {
        this.repository=repository; this.access=access; this.clock=clock; this.statistics=statistics;
    }
    public Result operations(LocalDate from, LocalDate to, String org) { return read(from,to,org,statistics.sourceModes()); }
    public Result business(LocalDate from, LocalDate to, boolean simulated) { return read(from,to,null,simulated ? List.of("mock","replay") : List.of("live")); }
    private Result read(LocalDate from, LocalDate to, String org, List<String> modes) {
        try {
            var decision = com.uav.lowaltitude.platform.report.ReportAccessContext.require(access,PermissionCode.TARGET_READ);
            String reportOrg=com.uav.lowaltitude.platform.report.ReportAccessContext.orgId();
            return repository.read(from,to,clock.now().toEpochMilli(),new Scope(decision.scopeMode()==ScopeMode.ALL,decision.userId(),reportOrg==null?org:reportOrg),modes);
        } catch (ApiException ex) {
            if (ex.getStatus()!=HttpStatus.FORBIDDEN) throw ex;
            return Result.unavailable("无目标读取权限");
        }
    }
}
