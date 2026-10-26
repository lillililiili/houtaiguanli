package com.uav.lowaltitude.modules.alarm.application;

import java.util.List;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.report.BusinessReportSource;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

/**
 * 事件处置报表的“当前状态”与业务前台告警列表“状态”列同一套名称（问题清单 #19）：
 * 逐条使用告警导出“处置进度”的同一判断（UavAdvisoryService.progressLabel：不反制决定、通知决定、
 * 处罚移送是否真正发出、反制/干扰进度、短信电话通知阶段），没有处置进度的仍写核实结论。只读，不建任务、不发送。
 */
@Service
public class EventReportQueryService extends BusinessReportSource {
    private final UavEventRepository repository;
    private final AccessControlService access;
    private final UavAdvisoryService advisory;
    private AlarmReadService riskReads;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskReads(AlarmReadService value){riskReads=value;}
    public EventReportQueryService(ReportDatasetReader reader, UavEventRepository repository, AccessControlService access,
            UavAdvisoryService advisory) {
        super(reader); this.repository = repository; this.access = access; this.advisory = advisory;
    }
    @Override public String key() { return "events"; }
    @Override public String title() { return "无人机风险"; }
    @Override public String basis() { return "持续风险首次创建时间；同次风险计一次，状态截至生成时"; }
    @Override public List<Dimension> dimensions() {
        return List.of(new Dimension("state", "当前状态"), new Dimension("kind", "事件类型"));
    }
    @Override protected Dataset dataset(Range range) {
        return repository.reportDataset(reader, range, com.uav.lowaltitude.platform.report.ReportAccessContext.require(access,PermissionCode.ALARM_READ));
    }
    @Override public Summary summarize(Range range) {
        return ReportDatasetReader.summarizeRows(rows(range), range, key(), title(), basis(), snapshot(), dimensions());
    }
    @Override public Page details(Range range, int page, int size) {
        return ReportDatasetReader.pageRows(rows(range), page, size);
    }
    private List<Row> rows(Range range) {
        AccessDecision alarms = com.uav.lowaltitude.platform.report.ReportAccessContext.require(access,PermissionCode.ALARM_READ);
        // 反制进度和告警页、告警导出一样，只写给有处置查看权限的人。
        AccessDecision disposals;
        try { disposals = com.uav.lowaltitude.platform.report.ReportAccessContext.require(access,PermissionCode.DISPOSAL_READ); } catch (ApiException denied) { disposals = null; }
        AccessDecision disposalScope = disposals;
        return reader.rows(repository.reportDataset(reader, range, alarms), range.sourceScope()).stream().map(row -> {
            String eventId=row.related()==null?row.id():row.related();
            String caseId=row.id().equals(eventId)?null:row.id();
            String progress = riskReads==null?advisory.progressLabel(eventId,alarms,disposalScope)
                    :riskReads.riskProgress(caseId,eventId,alarms,disposalScope);
            return progress == null ? row : new Row(row.id(), row.label(), row.occurredAt(), progress, row.kind(), row.severity(),
                    row.region(), row.sourceMode(), row.related(), row.result(), row.note());
        }).toList();
    }
}
