package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Convert only numbers produced by the old local input service, retaining their provenance. */
public class V202610060004__shorten_existing_local_plan_numbers extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            if (connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))
                statement.execute("LOCK TABLE flight_plan IN SHARE ROW EXCLUSIVE MODE");
            statement.execute("""
                    CREATE TABLE local_flight_plan_number_change (
                        plan_id VARCHAR(36) PRIMARY KEY REFERENCES flight_plan(plan_id) ON DELETE RESTRICT,
                        old_plan_no VARCHAR(64) NOT NULL UNIQUE,
                        new_plan_no VARCHAR(64) NOT NULL UNIQUE,
                        converted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        var plans = new ArrayList<String[]>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT p.plan_id,p.plan_no FROM flight_plan p
                WHERE p.source_mode IN ('mock','replay') AND p.plan_no = 'EXT-SIM-' || p.plan_id
                  AND EXISTS (SELECT 1 FROM local_interface_message m
                      WHERE m.kind='FLIGHT_PLAN' AND m.direction='IN' AND m.subject_id=p.plan_id)
                ORDER BY p.created_at,p.plan_id
                """)) {
            while (rows.next()) plans.add(new String[]{rows.getString(1), rows.getString(2)});
        }
        try (var record = connection.prepareStatement("INSERT INTO local_flight_plan_number_change(plan_id,old_plan_no,new_plan_no) VALUES(?,?,?)");
                var update = connection.prepareStatement("UPDATE flight_plan SET plan_no=? WHERE plan_id=? AND plan_no=?")) {
            for (String[] plan : plans) {
                String number = nextNumber(connection);
                record.setString(1, plan[0]); record.setString(2, plan[1]); record.setString(3, number);
                record.executeUpdate();
                // Identity, business timestamps, revision, relationships and frozen receipts are unchanged.
                update.setString(1, number); update.setString(2, plan[0]); update.setString(3, plan[1]);
                if (update.executeUpdate() != 1) throw new SQLException("Local plan number changed during migration");
            }
        }
    }

    private String nextNumber(Connection connection) throws SQLException {
        try (var sequence = connection.createStatement();
                var exists = connection.prepareStatement("SELECT COUNT(*) FROM flight_plan WHERE plan_no=?")) {
            while (true) {
                String number;
                try (var row = sequence.executeQuery("SELECT nextval('local_flight_plan_no_seq')")) {
                    row.next();
                    number = String.format(Locale.ROOT, "SIM-%06d", row.getLong(1));
                }
                exists.setString(1, number);
                try (var row = exists.executeQuery()) {
                    row.next();
                    if (row.getLong(1) == 0) return number;
                }
            }
        }
    }
}
