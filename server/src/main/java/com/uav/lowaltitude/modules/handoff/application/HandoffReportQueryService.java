package com.uav.lowaltitude.modules.handoff.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class HandoffReportQueryService extends BusinessReportSource {
    private final HandoffRepository repository;
    private final AccessControlService access;
    public HandoffReportQueryService(ReportDatasetReader reader, HandoffRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "handoffs"; }
    @Override public String title() { return "事件交接"; }
    @Override public String basis() { return "周期内新增事件关联的交接；状态截至生成时"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("result", "回执状态"));
    }
    @Override protected Dataset dataset(Range range) {
        access.require(PermissionCode.ALARM_READ);
        return repository.reportDataset(reader, range, access.require(PermissionCode.HANDOFF_READ));
    }
}
