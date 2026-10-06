package com.uav.lowaltitude.modules.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Entry;

class EvidenceLedgerLabelsTest {
    private static Entry entry(String sourceKind, String category, String name, String status, String mode, String custody) {
        return new Entry(sourceKind, "id-1", category, "NO-1", name, null, status, null, null, mode, null, null, null,
                null, false, custody, 0, null, null);
    }

    @Test
    void exportRowsUseTheSameChineseWordsAsTheLedgerPage() {
        assertThat(EvidenceLedgerLabels.row(entry("FILE", "IMAGE", "现场.jpg", "AVAILABLE", "replay", "HELD")))
                .containsExactly("证据文件", "id-1", "图片", "NO-1", "现场.jpg", "在库", "回放", "冻结保管");
        assertThat(EvidenceLedgerLabels.row(entry("FILE", "VIDEO", "a.mp4", "CORRUPT", "live", "NEARING")))
                .containsExactly("证据文件", "id-1", "录像", "NO-1", "a.mp4", "文件内容不一致", "实时", "临近到期");
        assertThat(EvidenceLedgerLabels.row(entry("TRACK", "TRACK", "TGT-1", "NO_POINTS", "mock", null)))
                .containsExactly("轨迹记录", "id-1", "轨迹", "NO-1", "TGT-1", "暂无观测点", "模拟", null);
        assertThat(EvidenceLedgerLabels.row(entry("COMMAND", "COMMAND", "EO_BEGIN_TRACK", "TIMED_OUT", "replay", null)))
                .containsExactly("指令记录", "id-1", "指令", "NO-1", "开始光电跟踪", "回执超时", "回放", null);
        assertThat(EvidenceLedgerLabels.row(entry("COMMAND", "COMMAND", "REBOOT", "QUEUED", "mock", null)))
                .containsExactly("指令记录", "id-1", "指令", "NO-1", "重启设备", "排队中", "模拟", null);
    }

    @Test
    void unknownCodesStayVisibleAndOnlyCommandNamesAreTranslated() {
        assertThat(EvidenceLedgerLabels.row(entry("ARCHIVE", "AUDIO", "NEW_COMMAND", "RETRYING", "mixed", "LEGAL_HOLD")))
                .containsExactly("ARCHIVE", "id-1", "AUDIO", "NO-1", "NEW_COMMAND", "RETRYING", "mixed", "LEGAL_HOLD");
        assertThat(EvidenceLedgerLabels.row(entry("COMMAND", "COMMAND", "NEW_COMMAND", "SUCCEEDED", null, null)))
                .containsExactly("指令记录", "id-1", "指令", "NO-1", "NEW_COMMAND", "执行完成", null, null);
        // 文件名恰好像指令码时不翻译。
        assertThat(EvidenceLedgerLabels.row(entry("FILE", null, "EMERGENCY_STOP", "PENDING", "live", "KEPT")))
                .containsExactly("证据文件", "id-1", null, "NO-1", "EMERGENCY_STOP", "入库中", "实时", "保管中");
    }
}
