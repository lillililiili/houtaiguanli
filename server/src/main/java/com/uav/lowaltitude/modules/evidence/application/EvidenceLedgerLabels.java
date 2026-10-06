package com.uav.lowaltitude.modules.evidence.application;

import static java.util.Map.entry;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Entry;

/**
 * 材料台账导出正文里枚举列的中文，与业务前台证据台账同一套说法：逐字取自 dongying-vue
 * `src/services/evidenceLedger.js`（EVIDENCE_CATEGORY_LABEL / COMMAND_STATE_LABEL / COMMAND_TYPE_LABEL）、
 * `src/ui/labels.js`（EVIDENCE_STATUS_LABEL / EVIDENCE_CUSTODY_LABEL / SOURCE_MODE_LABEL）和台账页的轨迹状态。
 * 与 CsvLabels 相同：未知码原样输出，空值仍写成空单元格。
 */
final class EvidenceLedgerLabels {
    static final List<String> HEADERS = List.of("来源", "记录ID", "类别", "编号", "名称", "状态", "数据模式", "保管状态");

    private static final Map<String, String> SOURCE_KIND = Map.of("FILE", "证据文件", "TRACK", "轨迹记录", "COMMAND", "指令记录");
    private static final Map<String, String> CATEGORY = Map.of("VIDEO", "录像", "TRACK", "轨迹", "IMAGE", "图片", "COMMAND", "指令");
    /** 文件、轨迹、指令三种记录的状态码互不重复，用一张表翻译。 */
    private static final Map<String, String> STATUS = Map.ofEntries(
            entry("PENDING", "入库中"), entry("AVAILABLE", "在库"), entry("MISSING", "文件缺失"),
            entry("CORRUPT", "文件内容不一致"), entry("DESTROYED", "已销毁"),
            entry("OBSERVED", "可查看"), entry("NO_POINTS", "暂无观测点"),
            entry("QUEUED", "排队中"), entry("SENT", "已下发"), entry("ACCEPTED", "设备已受理"),
            entry("SUCCEEDED", "执行完成"), entry("FAILED", "执行失败"), entry("TIMED_OUT", "回执超时"),
            entry("CANCELLED", "已取消"));
    private static final Map<String, String> SOURCE_MODE = Map.of("mock", "模拟", "replay", "回放", "live", "实时");
    private static final Map<String, String> CUSTODY = Map.of("KEPT", "保管中", "NEARING", "临近到期", "DUE", "已到期", "HELD", "冻结保管");
    /** 指令记录的“名称”是指令类型码。 */
    private static final Map<String, String> COMMAND_TYPE = Map.of("EO_BEGIN_TRACK", "开始光电跟踪", "EO_END_TRACK", "结束光电跟踪",
            "EO_TRACK_BEGIN", "开始光电跟踪", "EO_TRACK_END", "结束光电跟踪", "LINGYUN_CONTROL", "设备控制",
            "COUNTERMEASURE_4CH", "四通道控制", "EMERGENCY_STOP", "设备急停", "REBOOT", "重启设备");

    private EvidenceLedgerLabels() { }

    static List<String> row(Entry e) {
        String name = "COMMAND".equals(e.sourceKind()) ? label(COMMAND_TYPE, e.originalName()) : e.originalName();
        return Arrays.asList(label(SOURCE_KIND, e.sourceKind()), e.sourceId(), label(CATEGORY, e.category()), e.evidenceNo(),
                name, label(STATUS, e.status()), label(SOURCE_MODE, e.sourceMode()), label(CUSTODY, e.custody()));
    }

    private static String label(Map<String, String> dictionary, String code) {
        return code == null ? null : dictionary.getOrDefault(code, code);
    }
}
