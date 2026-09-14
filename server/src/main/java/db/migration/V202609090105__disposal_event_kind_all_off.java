package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 四通道停止下发全关后记 DEVICE_ALL_OFF_ISSUED。H2 与 PG 约束名可能不同，按定义文本删除再重建。
 */
public class V202609090105__disposal_event_kind_all_off extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        DO $$
                        DECLARE r record;
                        BEGIN
                          FOR r IN
                            SELECT con.conname
                            FROM pg_constraint con
                            JOIN pg_class rel ON rel.oid = con.conrelid
                            WHERE rel.relname = 'disposal_authorization_event'
                              AND con.contype = 'c'
                              AND pg_get_constraintdef(con.oid) LIKE '%event_kind%'
                              AND pg_get_constraintdef(con.oid) LIKE '%DEVICE_CONTROL_UNAVAILABLE%'
                              AND pg_get_constraintdef(con.oid) NOT LIKE '%DEVICE_ALL_OFF_ISSUED%'
                          LOOP
                            EXECUTE format('ALTER TABLE disposal_authorization_event DROP CONSTRAINT %I', r.conname);
                          END LOOP;
                        END $$;
                        """);
            }
        } else {
            List<String> names = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT CONSTRAINT_NAME, CHECK_CLAUSE
                         FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
                         """)) {
                while (rows.next()) {
                    String clause = String.valueOf(rows.getString("CHECK_CLAUSE")).toUpperCase(Locale.ROOT);
                    if (clause.contains("EVENT_KIND") && clause.contains("DEVICE_CONTROL_UNAVAILABLE")
                            && !clause.contains("DEVICE_ALL_OFF_ISSUED")) {
                        names.add(rows.getString("CONSTRAINT_NAME"));
                    }
                }
            }
            try (Statement drop = connection.createStatement()) {
                for (String name : names) {
                    drop.execute("ALTER TABLE disposal_authorization_event DROP CONSTRAINT \""
                            + name.replace("\"", "") + "\"");
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE disposal_authorization_event
                    ADD CONSTRAINT ck_stage13_event_kind
                    CHECK (event_kind IN ('REQUEST', 'APPROVE', 'REJECT', 'EXECUTE', 'RECEIPT',
                        'STOP', 'COMPLETE', 'FAIL', 'EXPIRE', 'CANCEL', 'MANUAL_RESULT',
                        'DEVICE_STOP_UNAVAILABLE', 'DEVICE_CONTROL_UNAVAILABLE', 'DEVICE_NOT_BOUND',
                        'PROTOCOL_NOT_OPENED', 'DEVICE_OFFLINE', 'DEVICE_ALL_OFF_ISSUED'))
                    """);
        }
    }
}
