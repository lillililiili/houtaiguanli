package com.uav.lowaltitude.platform.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 列表导出的公共写法（决策 15-7）。告警与风险共用，免得两边各写一份、慢慢长出不同的分隔符或转义规则。
 *
 * 三件必须做对的事：
 * 1. **BOM**：不带的话 Excel 会按本地代码页解释 UTF-8，中文列头直接是乱码——这是导出功能最常见的投诉。
 * 2. **转义**：字段里出现逗号、引号或换行时用双引号包裹并把引号翻倍，否则一条备注就能把整张表的列错开。
 * 3. **上限**：超过上限拒绝而不是截断。悄悄截断给出的是一份"看起来完整"的表，比报错危险得多。
 * 4. **公式注入**：以 = + - @ 开头的单元格会被 Excel/WPS 当公式执行（决策 15-26）。导出的是业务
 *    文本，谁都不该因为打开一份告警表而执行到别人写进备注里的东西。
 */
public final class CsvExport {
    /** 单次导出的行数上限。放在常量里而不是散在各处的字面量（退出标准要求，也便于契约注明）。 */
    public static final int MAX_ROWS = 5000;
    /** Excel 靠它认出 UTF-8。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));

    private CsvExport() { }

    public static String fileName(String prefix, Instant at) {
        return prefix + "-" + DAY.format(at) + ".csv";
    }

    public static ResponseEntity<byte[]> response(String fileName, List<String> headers, List<List<String>> rows) {
        StringBuilder text = new StringBuilder();
        text.append(line(headers));
        for (List<String> row : rows) text.append(line(row));
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(withBom);
    }

    private static String line(List<String> cells) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) row.append(',');
            row.append(escape(cells.get(i)));
        }
        return row.append('\n').toString();
    }

    /** 空值写成空字符串而不是 "null"——后者会被当成一个真实取值读进去。 */
    private static String escape(String value) {
        if (value == null) return "";
        String cell = formulaGuarded(value);
        if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    /**
     * 公式注入防护（决策 15-26）：首字符是 = + - @ 或制表符/回车时，前面加一个单引号，
     * Excel 与 WPS 都把它读成"这一格是文本"。加在**引号包裹之前**——包裹之后加，前缀就跑到
     * 引号外面去了，反而破坏这一行的结构。
     *
     * 现在告警与风险两张表**没有数值列**（编号、类别、时间、名称，全是文本），所以整列加前缀不会
     * 把数字变成文本。**将来若加了可能为负的数值列**（例如金额、偏差），这条规则要收窄成
     * "不是合法数字才加前缀"，否则 -12.5 会被导出成 '-12.5，在表里不能参与计算。
     */
    private static String formulaGuarded(String value) {
        if (value.isEmpty()) return value;
        char first = value.charAt(0);
        boolean risky = first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
        return risky ? "'" + value : value;
    }
}
