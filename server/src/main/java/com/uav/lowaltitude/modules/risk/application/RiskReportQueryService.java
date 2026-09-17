package com.uav.lowaltitude.modules.risk.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class RiskReportQueryService extends BusinessReportSource {
    private final RiskRepository repository;
    private final AccessControlService access;
    public RiskReportQueryService(ReportDatasetReader reader, RiskRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "risks"; }
    @Override public String title() { return "飞行风险"; }
    @Override public String basis() { return "发生时间"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("severity", "风险等级"));
    }
    @Override protected Dataset dataset(Range range) {

        return repository.reportDataset(reader, range, access.require(PermissionCode.RISK_READ));
    }
}
