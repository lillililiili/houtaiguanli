package com.uav.lowaltitude.modules.reporting.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.time.AppClock;

/** 仅 local/test 在 app.dev-seed.enabled 时写入运行统计样本事实；production 与 production,local 都不得注册。 */
@Component
// 开发种子必须双门禁：production 或 production,local 组合下都不得注册，否则生产环境会因查不到开发组织而启动失败或写入样本事实。
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(30)
public class LocalReportingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalReportingSeeder.class);
    private static final List<Weighted<String>> DISTRICTS = List.of(
            w("东营区", 31), w("广饶县", 24), w("河口区", 15), w("垦利区", 13), w("利津县", 11), w("东营港经济区", 6));
    private static final List<Weighted<String>> OBJECT_TYPES = List.of(
            w("无人机", 53), w("鸟", 24), w("未知", 12), w("识别中", 6), w("船", 3), w("车", 2));
    private static final List<Weighted<String>> UAV_LEGAL = List.of(
            w("合法", 38), w("非法", 34), w("异常", 10), w("待确认", 18));
    private static final List<Weighted<String>> PARTNERS = List.of(
            w("东营通航服务有限公司", 22), w("黄河口无人机应用公司", 18), w("东营启航科技", 14),
            w("山东云翼智能", 12), w("东营智航科技", 10), w("胜利油田巡检队", 8),
            w("未知(无报备)", 16));
    private static final List<Weighted<String>> PENALTIES = List.of(w("警告", 55), w("罚款", 30), w("驱离", 15));
    private static final List<Weighted<Integer>> FINES = List.of(w(2000, 40), w(3000, 30), w(5000, 20), w(8000, 10));

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public LocalReportingSeeder(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM report_airborne_target", Integer.class);
        if (existing != null && existing > 0) return;
        String orgId = jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_code='ORG-DEV'", String.class);
        String districtId = jdbc.queryForObject("SELECT district_id FROM app_district WHERE district_code='DIST-DEV'", String.class);
        if (orgId == null || districtId == null) {
            throw new IllegalStateException("reporting sample seed requires ORG-DEV and DIST-DEV");
        }
        LocalDate to = clock.now().atZone(ReportingService.ZONE).toLocalDate();
        LocalDate from = to.minusDays(29);
        long now = clock.nowMillis();
        Rng rng = new Rng(20260826L);
        List<Object[]> targets = new ArrayList<>();
        List<Object[]> cases = new ArrayList<>();
        int seq = 0;
        int caseSeq = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int offset = (int) java.time.temporal.ChronoUnit.DAYS.between(from, day);
            boolean weekend = day.getDayOfWeek().getValue() >= 6;
            int base = (int) Math.round((22 + offset * 0.55) * (weekend ? 1.18 : 1));
            int n = Math.max(8, base + rng.ri(-3, 3));
            int daySeq = 0;
            for (int i = 0; i < n; i++) {
                seq++;
                daySeq++;
                String type = rng.pick(OBJECT_TYPES);
                boolean uav = "无人机".equals(type);
                String legal = uav ? rng.pick(UAV_LEGAL) : "不适用";
                String risk = riskOf(legal, rng);
                int duration = rng.ri(3, 145);
                BigDecimal track = BigDecimal.valueOf(duration * (uav ? 0.38 : 0.22) * (0.5 + rng.next()))
                        .setScale(1, java.math.RoundingMode.HALF_UP);
                BigDecimal alt = BigDecimal.valueOf(uav ? rng.ri(25, 320) : rng.ri(20, 260));
                String district = rng.pick(DISTRICTS);
                String prefix = switch (type) {
                    case "无人机" -> "UAV";
                    case "鸟" -> "BRD";
                    case "未知" -> "UNK";
                    case "识别中" -> "IDN";
                    case "船" -> "SHP";
                    default -> "VEH";
                };
                String no = prefix + day.toString().replace("-", "") + String.format("%03d", daySeq);
                String targetId = stable("report-target:" + no);
                targets.add(new Object[] {
                        targetId, no, Date.valueOf(day), district, type, legal, risk, duration, track, alt,
                        orgId, districtId, "mock", Boolean.TRUE, now
                });
                if (uav && "非法".equals(legal) && rng.next() < 0.72) {
                    caseSeq++;
                    String penalty = rng.pick(PENALTIES);
                    int fine = "罚款".equals(penalty) ? rng.pick(FINES) : 0;
                    String caseNo = "CF" + day.toString().replace("-", "") + String.format("%03d", caseSeq);
                    cases.add(new Object[] {
                            stable("report-case:" + caseNo), caseNo, Date.valueOf(day), district,
                            rng.pick(PARTNERS), penalty, fine, orgId, districtId, "mock", Boolean.TRUE, now
                    });
                }
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO report_airborne_target (
                    target_id, target_no, occurred_on, district_name, object_type, legal_status, risk_level,
                    duration_min, track_km, altitude_amsl_m, owner_org_id, district_id, source_mode, simulated, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, targets);
        if (!cases.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO report_penalty_case (
                        case_id, case_no, occurred_on, district_name, partner_name, penalty_type, fine_amount,
                        owner_org_id, district_id, source_mode, simulated, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, cases);
        }
        log.info("seeded {} airborne targets and {} penalty cases for reporting sample facts", targets.size(), cases.size());
    }

    private static String riskOf(String legal, Rng rng) {
        return switch (legal) {
            case "非法" -> rng.pick(List.of(w("超高风险", 20), w("高风险", 50), w("中风险", 30)));
            case "异常" -> rng.pick(List.of(w("高风险", 30), w("中风险", 50), w("低风险", 20)));
            case "待确认" -> rng.pick(List.of(w("中风险", 40), w("低风险", 60)));
            case "合法" -> rng.pick(List.of(w("低风险", 85), w("中风险", 15)));
            default -> rng.pick(List.of(w("低风险", 50), w("中风险", 30), w("高风险", 15), w("超高风险", 5)));
        };
    }

    private static String stable(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static <T> Weighted<T> w(T value, int weight) {
        return new Weighted<>(value, weight);
    }

    private record Weighted<T>(T value, int weight) { }

    private static final class Rng {
        private long seed;

        private Rng(long seed) {
            this.seed = seed & 0xffffffffL;
        }

        private double next() {
            seed = (seed * 1664525L + 1013904223L) & 0xffffffffL;
            return seed / 4294967296.0;
        }

        private int ri(int a, int b) {
            return a + (int) Math.floor(next() * (b - a + 1));
        }

        private <T> T pick(List<Weighted<T>> items) {
            double total = 0;
            for (Weighted<T> item : items) total += item.weight();
            double r = next() * total;
            for (Weighted<T> item : items) {
                r -= item.weight();
                if (r <= 0) return item.value();
            }
            return items.get(0).value();
        }
    }
}
