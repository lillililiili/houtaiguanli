package com.uav.lowaltitude.platform.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Selects the unchanged scripts for two independently applied 20261016 histories. Never repairs history. */
public final class MigrationLineage {
    private MigrationLineage() { }
    private record Applied(String script, int checksum, boolean success) { }
    private static final Map<String, Integer> AUTOMATIC = Map.of(
            "V202610160001__normalized_observation_origin_device.sql", 1803088330,
            "V202610160002__countermeasure_start_attempt_evidence.sql", 1130371266);
    private static final Map<String, Integer> MANUAL = Map.of(
            "V202610160001__uav_followup_notification_decision.sql", -1301427798,
            "V202610160002__handoff_internal_recipient_null_guard.sql", -933697544,
            "V202610169001__handoff_decision_realtime.sql", 647435073);

    public static void configure(FluentConfiguration configuration) {
        try (Connection connection = configuration.getDataSource().getConnection()) {
            Map<String, Applied> history = history(connection, configuration);
            Applied first = history.get("202610160001");
            boolean manual = first != null && MANUAL.containsKey(first.script());
            Map<String, Integer> expected = manual ? MANUAL : AUTOMATIC;
            for (String version : new String[]{"202610160001", "202610160002", "202610169001"}) {
                Applied row = history.get(version);
                if (row == null) continue;
                if (first == null || !row.success() || !Integer.valueOf(row.checksum()).equals(expected.get(row.script())))
                    throw new IllegalStateException("Unrecognized or mixed 20261016 migration history at version " + version
                            + "; preserve the database and inspect the original scripts (do not repair checksums)");
            }
            var locations = new ArrayList<>(Arrays.stream(configuration.getLocations())
                    .map(location -> location.getDescriptor())
                    .filter(location -> !location.startsWith("classpath:db/lineage/")).toList());
            locations.add("classpath:db/lineage/" + (manual ? "manual" : "automatic"));
            if (manual && connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                locations.add("classpath:db/lineage/manual-postgresql");
            configuration.locations(locations.toArray(String[]::new));
            // The remote history already applied 9001 before the independent fusion index existed.
            // Permit only this known missing earlier version; all checksums and validation remain active.
            if (manual && history.containsKey("202610169001") && !history.containsKey("202610160003")) {
                configuration.outOfOrder(true);
                var cutoff = org.flywaydb.core.api.MigrationVersion.fromVersion("202610169001");
                for (var pending : configuration.load().info().pending())
                    if (pending.getVersion() != null && pending.getVersion().compareTo(cutoff) < 0
                            && !pending.getVersion().toString().equals("202610160003"))
                        throw new IllegalStateException("Unexpected earlier pending migration " + pending.getVersion());
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot inspect migration lineage; no migrations were applied", error);
        }
    }

    private static Map<String, Applied> history(Connection connection, FluentConfiguration configuration) throws SQLException {
        String schema = configuration.getDefaultSchema();
        if (schema == null && configuration.getSchemas().length > 0) schema = configuration.getSchemas()[0];
        if (schema == null) schema = connection.getSchema();
        String table = configuration.getTable();
        boolean exists = false;
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), schema, table, new String[]{"TABLE"})) {
            while (tables.next()) if (table.equals(tables.getString("TABLE_NAME")) && schema.equals(tables.getString("TABLE_SCHEM"))) exists = true;
        }
        Map<String, Applied> rows = new HashMap<>();
        if (!exists) return rows;
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        String qualified = quote(schema, quote) + "." + quote(table, quote);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT version,script,checksum,success FROM " + qualified
                     + " WHERE version IN ('202610160001','202610160002','202610160003','202610169001')")) {
            while (result.next()) {
                int checksum = result.getInt("checksum");
                if (result.wasNull()) throw new IllegalStateException("Missing checksum in 20261016 migration history");
                String version = result.getString("version");
                if (rows.put(version, new Applied(result.getString("script"), checksum, result.getBoolean("success"))) != null)
                    throw new IllegalStateException("Duplicate migration history at " + version);
            }
        }
        return rows;
    }

    private static String quote(String identifier, String quote) {
        if (identifier == null || quote.isEmpty()) throw new IllegalStateException("Explicit migration schema and quoting are required");
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
