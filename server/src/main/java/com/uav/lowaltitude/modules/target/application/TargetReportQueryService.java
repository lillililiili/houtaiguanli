package com.uav.lowaltitude.modules.target.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class TargetReportQueryService extends BusinessReportSource {
    private final TargetReadRepository repository;
    private final AccessControlService access;
    public TargetReportQueryService(ReportDatasetReader reader, TargetReadRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "targets"; }
    @Override public String title() { return "新增目标"; }
    @Override public String basis() { return "首次发现时间"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("kind", "目标识别类型"));
    }
    @Override protected Dataset dataset(Range range) {

        return repository.reportDataset(reader, range, access.require(PermissionCode.TARGET_READ));
    }
}
