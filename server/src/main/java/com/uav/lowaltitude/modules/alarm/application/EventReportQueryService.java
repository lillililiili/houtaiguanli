package com.uav.lowaltitude.modules.alarm.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class EventReportQueryService extends BusinessReportSource {
    private final UavEventRepository repository;
    private final AccessControlService access;
    public EventReportQueryService(ReportDatasetReader reader, UavEventRepository repository, AccessControlService access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "events"; }
    @Override public String title() { return "无人机事件"; }
    @Override public String basis() { return "事件创建时间；状态截至生成时"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("kind", "事件类型"));
    }
    @Override protected Dataset dataset(Range range) {

        return repository.reportDataset(reader, range, access.require(PermissionCode.ALARM_READ));
    }
}
