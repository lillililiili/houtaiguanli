package com.uav.lowaltitude.modules.alarm.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class AlarmReportQueryService extends BusinessReportSource {
    private final AlarmReadRepository repository;
    private final AccessControlService access;
    public AlarmReportQueryService(ReportDatasetReader reader, AlarmReadRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "alarms"; }
    @Override public String title() { return "告警"; }
    @Override public String basis() { return "发生时间"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("severity", "告警等级"));
    }
    @Override protected Dataset dataset(Range range) {

        return repository.reportDataset(reader, range, access.require(PermissionCode.ALARM_READ));
    }
}
