package com.uav.lowaltitude.modules.disposal.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class DisposalReportQueryService extends BusinessReportSource {
    private final DisposalRepository repository;
    private final AccessControlService access;
    public DisposalReportQueryService(ReportDatasetReader reader, DisposalRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "authorizations"; }
    @Override public String title() { return "处置授权"; }
    @Override public String basis() { return "周期内新增事件关联的处置授权；状态截至生成时"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("result", "执行结果"));
    }
    @Override protected Dataset dataset(Range range) {
        access.require(PermissionCode.ALARM_READ);
        return repository.reportDataset(reader, range, access.require(PermissionCode.DISPOSAL_READ));
    }
}
