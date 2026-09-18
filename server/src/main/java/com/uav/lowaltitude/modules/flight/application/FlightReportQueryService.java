package com.uav.lowaltitude.modules.flight.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class FlightReportQueryService extends BusinessReportSource {
    private final FlightReadRepository repository;
    private final AccessControlService access;
    public FlightReportQueryService(ReportDatasetReader reader, FlightReadRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "plans"; }
    @Override public String title() { return "飞行计划"; }
    @Override public String basis() { return "计划开始时间；最新核验结果截至生成时"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("result", "最新核验结果"));
    }
    @Override protected Dataset dataset(Range range) {

        return repository.reportDataset(reader, range, access.require(PermissionCode.FLIGHT_READ));
    }
}
