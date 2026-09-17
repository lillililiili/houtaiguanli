package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class MaintenanceReportQueryService extends BusinessReportSource {
    private final DeviceMaintenanceRepository repository;
    private final DeviceAccessPolicy access;
    public MaintenanceReportQueryService(ReportDatasetReader reader, DeviceMaintenanceRepository repository, DeviceAccessPolicy access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "maintenance"; }
    @Override public String title() { return "运维待办"; }
    @Override public String basis() { return "通知创建时间；处理结果截至生成时"; }
    @Override public boolean snapshot() { return false; }
    @Override public List<Dimension> dimensions() { return List.of(new Dimension("state", "处理状态"), new Dimension("label", "异常设备 Top 10")); }
    @Override protected Dataset dataset(Range range) { return repository.reportDataset(range, access.requireMonitoringRead()); }
}
