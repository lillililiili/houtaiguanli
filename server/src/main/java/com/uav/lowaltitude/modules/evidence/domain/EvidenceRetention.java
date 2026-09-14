package com.uav.lowaltitude.modules.evidence.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * 平台缺省留存期。按文件种类从取证时刻（缺则入库时刻）起算到期日。
 * 不是已确认的档案制度：到期只改变保管结论，不删除文件，也不开放销毁入口。
 */
public final class EvidenceRetention {
    public static final int NEARING_DAYS = 30;

    public static final String CUSTODY_KEPT = "KEPT";
    public static final String CUSTODY_NEARING = "NEARING";
    public static final String CUSTODY_DUE = "DUE";
    public static final String CUSTODY_HELD = "HELD";

    private EvidenceRetention() { }

    public record Policy(String kindCode, Period period, String label, String note) { }

    public static Policy policy(String kindCode) {
        String kind = kindCode == null ? "" : kindCode;
        return switch (kind) {
            case "COMMISSION_REPORT" -> new Policy(kind, Period.ofDays(90), "90 天", "设备建设期记录，非案件证据");
            case "SCENE_PHOTO" -> new Policy(kind, Period.ofYears(1), "1 年", "辅助取证材料");
            case "EO_VIDEO", "EO_STILL" -> new Policy(kind, Period.ofYears(3), "3 年", "影像取证材料");
            case "TRACK_SNAPSHOT" -> new Policy(kind, Period.ofYears(5), "5 年", "定性依据，与案卷同期");
            case "NOTICE_RECEIPT" -> new Policy(kind, Period.ofYears(5), "5 年", "对外通报凭据");
            case "COMMAND_LOG" -> new Policy(kind, Period.ofYears(5), "5 年", "处置过程审计");
            case "PENALTY_DOCUMENT" -> new Policy(kind, Period.ofYears(5), "5 年", "法律文书，与案卷同期");
            default -> new Policy(kind, Period.ofYears(5), "5 年", "未列种类按案卷同期");
        };
    }

    public static Instant until(String kindCode, Instant capturedAt, Instant storedAt) {
        Instant base = capturedAt != null ? capturedAt : storedAt;
        if (base == null) return null;
        return base.atOffset(ZoneOffset.UTC).plus(policy(kindCode).period()).toInstant();
    }

    public static Instant effectiveUntil(Instant storedUntil, String kindCode, Instant capturedAt, Instant storedAt) {
        return storedUntil != null ? storedUntil : until(kindCode, capturedAt, storedAt);
    }

    public static String custody(Instant retainUntil, Instant now, boolean held) {
        if (held) return CUSTODY_HELD;
        if (retainUntil == null || now == null) return CUSTODY_KEPT;
        if (!retainUntil.isAfter(now)) return CUSTODY_DUE;
        if (!retainUntil.isAfter(now.plus(Duration.ofDays(NEARING_DAYS)))) return CUSTODY_NEARING;
        return CUSTODY_KEPT;
    }
}
