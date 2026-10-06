package com.uav.lowaltitude.modules.fusion.domain;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 机身序列号（uavSN）是唯一能指向"具体这一台"的身份证据（ZT-01）。
 *
 * 两架都报出了序列号、而且序列号不同的无人机，不论离得多近都不是同一架：关联、已有 link 的直连、自动合并
 * 一律不许把它们并到一起。只认来源**明确报出的序列号**（映射器写进 quality.uav_sn），不认型号——
 * 同一型号的两架无人机很常见，型号相同不能当成同一架，型号不同也不该拿来否决。
 * 一边没有序列号（雷达通常报不出来）时不做判断，交回位置/运动/类别去关联。
 */
public final class IdentitySerials {
    /** 映射器写入观测 quality 的键：只有协议里明确的序列号字段才写。 */
    public static final String QUALITY_KEY = "uav_sn";

    private IdentitySerials() { }

    /** 去首尾空白、统一大写；空串视为没有。大小写不同的同一串按同一台处理，免得格式差异把一架拆成两个目标。 */
    public static String normalize(Object raw) {
        if (raw == null) return null;
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    /** 观测/估计 quality 里的序列号（已归一）。 */
    public static String of(Map<String, ?> quality) {
        return quality == null ? null : normalize(quality.get(QUALITY_KEY));
    }

    public static String of(SourceObservation observation) {
        return observation == null ? null : of(observation.quality());
    }

    /** 观测的序列号与目标已知序列号冲突：两边都有，且目标已知的序列号里没有它。 */
    public static boolean conflicts(String serial, Set<String> known) {
        return serial != null && known != null && !known.isEmpty() && !known.contains(serial);
    }

    /** 两个目标的已知序列号冲突：两边都有，且没有任何一个相同。 */
    public static boolean conflicts(Set<String> a, Set<String> b) {
        return a != null && b != null && !a.isEmpty() && !b.isEmpty() && Collections.disjoint(a, b);
    }
}
