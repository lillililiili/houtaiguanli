package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Add the other independent 20261026 branch without changing applied scripts or history rows. */
public class V202610270005__reconcile_voice_and_flight_histories extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return List.of(1, 1792140, 200317101).hashCode();
    }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        int flightTables = 0;
        for (String name : List.of("flight_avoidance_policy", "flight_avoidance_change", "adsb_receiver",
                "adsb_aircraft", "counter_flight_check"))
            if (table(connection, name)) flightTables++;
        if (flightTables != 0 && flightTables != 5)
            throw new IllegalStateException("Incomplete flight avoidance schema; preserve the database (no history repair)");
        if (flightTables == 0)
            execute(connection, "flight-avoidance/V202610260001__flight_avoidance.sql");
        if (!column(connection, "uav_auto_voice_task", "response_order_basis_json"))
            execute(connection, "voice-order/V202610260001__freeze_voice_response_order_basis.sql");
    }

    private static void execute(Connection connection, String script) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("db/lineage/" + script), StandardCharsets.UTF_8));
    }

    private static boolean table(Connection connection, String name) throws SQLException {
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), name,
                new String[]{"TABLE", "BASE TABLE"})) {
            while (rows.next()) if (name.equals(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private static boolean column(Connection connection, String table, String column) throws SQLException {
        try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), table, column)) {
            while (rows.next()) if (table.equals(rows.getString("TABLE_NAME")) && column.equals(rows.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }
}
