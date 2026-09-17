package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

@Service
public class DeviceReportQueryService extends BusinessReportSource {
    private final DeviceRepository repository;
    private final DeviceAccessPolicy access;
    public DeviceReportQueryService(ReportDatasetReader reader, DeviceRepository repository, DeviceAccessPolicy access) {
        super(reader); this.repository = repository; this.access = access;
    }
    @Override public String key() { return "devices"; }
    @Override public String title() { return "设备快照"; }
    @Override public String basis() { return "当前设备状态快照，不代表历史周期在线率"; }
    @Override public boolean snapshot() { return true; }
    @Override public List<Dimension> dimensions() { return List.of(new Dimension("state", "连接状态"), new Dimension("kind", "设备类型"), new Dimension("result", "健康状态")); }
    @Override protected Dataset dataset(Range range) { access.requireDevicesRead(); return repository.reportDataset(); }
}
