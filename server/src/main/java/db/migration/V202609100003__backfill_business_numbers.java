package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 给已存在的引擎记录补业务编号：来源编号是技术键（含冒号）的告警与风险，按接收时间顺序、按北京时间当日自增，
 * 并把计数表推进到已用序号之后。来源方自带可读编号的记录不动。H2 与 PostgreSQL 都走同一段 JDBC 逻辑。
 */
public class V202609100003__backfill_business_numbers extends BaseJavaMigration {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        Map<String, Integer> counters = new HashMap<>();
        backfill(c, "ALARM", "告警", "SELECT alarm_id, received_at FROM alarm WHERE alarm_no IS NULL AND source_alarm_id LIKE '%:%' ORDER BY received_at, alarm_id",
                "UPDATE alarm SET alarm_no=? WHERE alarm_id=?", counters);
        backfill(c, "RISK", "风险", "SELECT risk_id, received_at FROM flight_risk WHERE risk_no IS NULL AND source_risk_id LIKE '%:%' ORDER BY received_at, risk_id",
                "UPDATE flight_risk SET risk_no=? WHERE risk_id=?", counters);
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO business_number_counter (kind, day_key, next_value) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Integer> entry : counters.entrySet()) {
                String[] key = entry.getKey().split("/");
                insert.setString(1, key[0]); insert.setString(2, key[1]); insert.setInt(3, entry.getValue() + 1);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void backfill(Connection c, String kind, String prefix, String select, String update, Map<String, Integer> counters) throws Exception {
        try (PreparedStatement query = c.prepareStatement(select); ResultSet rs = query.executeQuery();
             PreparedStatement write = c.prepareStatement(update)) {
            while (rs.next()) {
                String id = rs.getString(1);
                Timestamp received = rs.getTimestamp(2);
                String day = DAY.format((received == null ? java.time.Instant.now() : received.toInstant()).atZone(ZONE));
                int value = counters.merge(kind + "/" + day, 1, Integer::sum);
                write.setString(1, prefix + "-" + day.substring(4) + "-" + String.format("%03d", value));
                write.setString(2, id);
                write.addBatch();
            }
            write.executeBatch();
        }
    }
}
