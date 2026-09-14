package com.uav.lowaltitude.platform.number;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务编号：平台自己产生的记录（规则引擎告警、空间安全风险）要有能在电话和文书里指代的编号，
 * 格式 {@code 告警-MMDD-NNN} / {@code 风险-MMDD-NNN}，按北京时间当日自增（需求确认表增补四 F11）。
 *
 * <p>来源方自带可读编号的记录沿用来源编号（{@link #readable(String)} 判定），平台编号留空；
 * 只有引擎那种 {@code eval:<uuid>}、{@code C04:<规则>:<计划>:…} 这类技术键才取平台编号。
 *
 * <p>计数行用 SELECT … FOR UPDATE 串行化，同一天并发取号不会重号；行不存在时插入，插入撞唯一键就重读。
 * 取号必须在调用方事务里（REQUIRED），回滚时号一起回滚，序号会留空洞，这是可接受的。
 */
@Service
public class BusinessNumberService {
    public static final String ALARM = "ALARM";
    public static final String RISK = "RISK";
    private static final Map<String, String> PREFIX = Map.of(ALARM, "告警", RISK, "风险");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final NamedParameterJdbcTemplate jdbc;

    public BusinessNumberService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 来源编号能否直接给人看：含冒号的技术键与 UUID 都不能。 */
    public static boolean readable(String sourceNo) {
        String text = sourceNo == null ? "" : sourceNo.trim();
        return !text.isEmpty() && !text.contains(":") && !UUID.matcher(text).matches();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String next(String kind, Instant at) {
        String prefix = PREFIX.get(kind);
        if (prefix == null) throw new IllegalArgumentException("unknown business number kind: " + kind);
        String day = DAY.format((at == null ? Instant.now() : at).atZone(ZONE));
        int value = reserve(kind, day);
        return prefix + "-" + day.substring(4) + "-" + String.format("%03d", value);
    }

    private int reserve(String kind, String day) {
        Map<String, Object> params = Map.of("kind", kind, "day", day);
        for (int attempt = 0; attempt < 2; attempt++) {
            Integer current = jdbc.query("SELECT next_value FROM business_number_counter WHERE kind=:kind AND day_key=:day FOR UPDATE",
                    params, rs -> rs.next() ? rs.getInt(1) : null);
            if (current != null) {
                jdbc.update("UPDATE business_number_counter SET next_value=:next WHERE kind=:kind AND day_key=:day",
                        Map.of("kind", kind, "day", day, "next", current + 1));
                return current;
            }
            try {
                jdbc.update("INSERT INTO business_number_counter (kind, day_key, next_value) VALUES (:kind, :day, 2)", params);
                return 1;
            } catch (DuplicateKeyException race) {
                // 另一事务刚插入了同一天的计数行：重读一次即可。
            }
        }
        throw new IllegalStateException("business number counter contention: " + kind + "/" + day);
    }
}
