package com.uav.lowaltitude.platform.report;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备显示编号：与后台“设备管理”页 ruoyi-ui/src/utils/format.js formatDeviceNo 同一算法，
 * 报表预览与导出（Excel/PDF）使用同一写法；原始 device_no 仍用于关联和查询，不回写。
 */
public final class DeviceDisplayNo {
    private DeviceDisplayNo() { }
    private static final Pattern LEGACY = Pattern.compile("^sim-\\d{10}-([0-9a-f]{4})-(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMULATOR = Pattern.compile("^map-sim-([a-z0-9]+)-(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_SUFFIX = Pattern.compile("^d-[^-]+-([a-z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> KINDS = Map.of("5ga", "5G-A", "tdoa", "TDOA", "radar", "RADAR",
            "eo", "EO", "weather", "WX", "countermeasure", "CM");

    public static String format(String value) {
        if (value == null || value.isEmpty()) return null;
        Matcher legacy = LEGACY.matcher(value);
        if (legacy.matches()) return "SIM-" + legacy.group(1) + "-" + legacy.group(2);
        Matcher match = SIMULATOR.matcher(value);
        if (!match.matches()) return value;
        String kind = KINDS.getOrDefault(match.group(1).toLowerCase(Locale.ROOT), match.group(1).toUpperCase(Locale.ROOT));
        String raw = match.group(2);
        Matcher suffix = DEVICE_SUFFIX.matcher(raw);
        String compact = suffix.matches() ? suffix.group(1) : raw.length() <= 8 ? raw : compact(raw);
        return kind + "-" + compact;
    }

    /** FNV-1a 32 位；与前端逐个码点取首个 UTF-16 码元的写法一致。 */
    static String compact(String value) {
        int hash = (int) 2166136261L;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            hash = (hash ^ value.charAt(i)) * 16777619;
            i += Character.charCount(codePoint);
        }
        String text = Long.toString(Integer.toUnsignedLong(hash), 36);
        return text.substring(Math.max(0, text.length() - 5)).toUpperCase(Locale.ROOT);
    }
}
