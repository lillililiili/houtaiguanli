package db.migration;

import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 补充说明可留空；保留操作者、时间、结论和状态转换约束，不回填历史。 */
public class V202609280007__optional_business_notes extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        boolean pg = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
        String query = pg ? """
            SELECT conname AS name, pg_get_constraintdef(oid) AS clause FROM pg_constraint
            WHERE conrelid='ops_device_maintenance_task'::regclass AND contype='c'
            """ : """
            SELECT c.CONSTRAINT_NAME AS name,c.CHECK_CLAUSE AS clause
            FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS c JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
              ON c.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG AND c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA
              AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
            WHERE LOWER(t.TABLE_NAME)='ops_device_maintenance_task' AND t.TABLE_SCHEMA=SCHEMA()
            """;
        var names = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                if (rows.getString("clause").toLowerCase(Locale.ROOT).contains("handling_note")) names.add(rows.getString("name"));
            }
        }
        if (names.size() != 1) throw new IllegalStateException("Expected one maintenance handling constraint");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE flight_risk_verification DROP CONSTRAINT ck_stage4_risk_verification_note");
            statement.execute("ALTER TABLE flight_risk_verification ADD CONSTRAINT ck_stage4_risk_verification_note CHECK (LENGTH(note) <= 1000)");
            statement.execute("ALTER TABLE legality_review_history DROP CONSTRAINT ck_stage7_review_history_note");
            statement.execute("ALTER TABLE legality_review_history ADD CONSTRAINT ck_stage7_review_history_note CHECK (LENGTH(note) <= 1000)");
            statement.execute("ALTER TABLE punishment_case_lead DROP CONSTRAINT ck_stage14_lead_resolution");
            statement.execute("""
                ALTER TABLE punishment_case_lead ADD CONSTRAINT ck_stage14_lead_resolution CHECK (
                  (resolved=FALSE AND resolved_by IS NULL AND resolved_at IS NULL AND resolved_note IS NULL)
                  OR (resolved=TRUE AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
                """);
            statement.execute("ALTER TABLE ops_device_maintenance_task DROP CONSTRAINT \"" + names.get(0).replace("\"", "\"\"") + "\"");
            statement.execute("""
                ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_handling CHECK (
                  (status='PENDING' AND active_key IS NOT NULL AND handled_at IS NULL)
                  OR (status='HANDLED' AND active_key IS NULL AND handled_by IS NOT NULL AND handled_at IS NOT NULL))
                """);
        }
    }
}
