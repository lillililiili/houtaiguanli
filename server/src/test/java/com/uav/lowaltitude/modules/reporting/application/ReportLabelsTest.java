package com.uav.lowaltitude.modules.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Row;

/** 报表导出（Excel/PDF）与预览同一套中文：设备编号、设备类型、交接类型、设备执行结果与未知代码兜底（问题清单 #18/#20）。 */
class ReportLabelsTest {
    static Row row(String related, String kind, String result) {
        return new Row("id-1", "模拟 流程验收光电", 1L, "PENDING", kind, null, "东营区", "replay", related, result, null);
    }

    @Test void deviceSectionsUseTheDeviceManagementNumberOtherSectionsKeepTheirIds() {
        assertThat(ReportLabels.cell("maintenance", row("map-sim-eo-acceptance-eo", null, null), "related")).isEqualTo("EO-S67GL");
        assertThat(ReportLabels.cell("devices", row("map-sim-radar-acceptance-risk-radar", null, null), "related")).isEqualTo("RADAR-QMIN7");
        assertThat(ReportLabels.cell("devices", row("QA-LOCAL-CM4", null, null), "related")).isEqualTo("QA-LOCAL-CM4");
        assertThat(ReportLabels.cell("handoffs", row("map-sim-eo-acceptance-eo", null, null), "related")).isEqualTo("map-sim-eo-acceptance-eo");
        assertThat(ReportLabels.cell("maintenance", row(null, null, null), "related")).isEqualTo("—");
        // 处置授权、事件交接写关联告警编号，和告警页同一写法（#21），不再写事件内部 ID。
        assertThat(ReportLabels.cell("authorizations", row("告警-1018-006", null, null), "related")).isEqualTo("1018-006");
        assertThat(ReportLabels.cell("handoffs", row("告警-0902-117", null, null), "related")).isEqualTo("0902-117");
        assertThat(ReportLabels.columns("authorizations")).extracting(ReportLabels.Column::label).contains("关联告警编号").doesNotContain("关联事件 ID");
        assertThat(ReportLabels.columns("handoffs")).extracting(ReportLabels.Column::label).contains("关联告警编号").doesNotContain("关联事件 ID");
    }

    @Test void relayReceiptDetailsAreRewrittenByTheirMeaning() {
        String off = "继电器设置回码已确认，低四位=0；不代表射频已发射";
        assertThat(com.uav.lowaltitude.modules.disposal.application.DisposalResultText.forOperator(off)).isEqualTo("设备已确认全部关闭。");
        assertThat(com.uav.lowaltitude.modules.disposal.application.DisposalResultText.forOperator("继电器设置回码已确认，低四位=d；不代表射频已发射"))
                .startsWith("设备已确认开启").doesNotContain("继电器", "低四位");
        assertThat(com.uav.lowaltitude.modules.disposal.application.DisposalResultText.forOperator("回码继电器状态与请求不一致（低四位=0xf），本次设置未确认；请核查设备实际状态"))
                .doesNotContain("低四位").contains("没有确认");
        assertThat(com.uav.lowaltitude.modules.disposal.application.DisposalResultText.forOperator("设备响应超时，本次设置结果未确认")).isEqualTo("设备响应超时，本次设置结果未确认");
        assertThat(com.uav.lowaltitude.modules.disposal.application.DisposalResultText.forOperator(null)).isNull();
    }

    @Test void typeAndResultCodesAreChineseAndUnknownCodesAreNotShown() {
        assertThat(ReportLabels.cell("devices", row(null, "光电", null), "kind")).isEqualTo("光电");
        assertThat(ReportLabels.text("EO")).isEqualTo("光电");
        assertThat(ReportLabels.text("countermeasure")).isEqualTo("反制设备");
        assertThat(ReportLabels.cell("handoffs", row(null, "UAV_PUNISHMENT", null), "kind")).isEqualTo("处罚移送");
        assertThat(ReportLabels.cell("authorizations", row(null, null, "COUNTERMEASURE_SET_OK"), "result")).isEqualTo("设备已确认执行指令");
        assertThat(ReportLabels.cell("authorizations", row(null, null, "SOME_NEW_DEVICE_CODE"), "result")).isEqualTo("其他");
        assertThat(ReportLabels.cell("maintenance", row(null, null, "已办结：确认设备恢复并办结（设备已恢复）"), "result"))
                .isEqualTo("已办结：确认设备恢复并办结（设备已恢复）");
        assertThat(ReportLabels.cell("maintenance", row(null, null, null), "result")).isEqualTo("未知");
        assertThat(ReportLabels.cell("maintenance", new Row("t", null, 1L, "COMPLETED", null, null, null, "live", null, null, null), "state")).isEqualTo("已办结");
        assertThat(ReportLabels.cell("authorizations", new Row("a", null, 1L, "COMPLETED", null, null, null, "live", null, null, null), "state")).isEqualTo("已完成");
        assertThat(ReportLabels.dictionary()).containsEntry("maintenance.state.COMPLETED", "已办结").containsEntry("PROCESSING", "处理中");
        assertThat(ReportLabels.text("PLANNED_OFFLINE")).isEqualTo("计划下线（模拟器已停止）");
        assertThat(ReportLabels.text("TDOA")).as("名称不是代码，原样显示").isEqualTo("TDOA");
        assertThat(ReportLabels.dictionary()).containsEntry("UAV_PUNISHMENT", "处罚移送").containsEntry("EO", "光电");
    }
}
