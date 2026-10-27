package com.uav.lowaltitude.platform.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Selects unchanged scripts for independently applied migration histories. Never repairs history. */
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
    private static final Map<String, Integer> LOCAL_FEATURES = Map.of(
            "V202610200001__remove_vendor_name_from_display_data.sql", 351620372,
            "db.migration.V202610200002__unit_delegation", 529132896,
            "V202610200003__trajectory_prediction.sql", 1496639541,
            "V202610200004__prediction_evidence_and_notifications.sql", 12268958,
            "V202610200005__flight_plan_filing_coordinate_system.sql", -1287599436,
            "V202610200006__prediction_voice_recording_purpose.sql", -367654150,
            "V202610200007__unit_management_permissions.sql", -563788088,
            "db.migration.V202610210001__unit_role_copy_origin", -891790966);
    private static final Map<String, Integer> REMOTE_FEATURES = Map.of(
            "V202610200001__disposal_countermeasure_mode.sql", -982526904,
            "V202610200002__local_airspace_lifecycle.sql", -852369259);
    private static final List<String> FEATURE_VERSIONS = List.of("202610200001", "202610200002", "202610200003",
            "202610200004", "202610200005", "202610200006", "202610200007", "202610210001");
    private static final Map<String, Integer> VOICE_AND_FLIGHT = Map.of(
            "V202610260001__freeze_voice_response_order_basis.sql", 1792140,
            "V202610260001__flight_avoidance.sql", 200317101);

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
            boolean remoteFeatures = remoteFeatures(history);
            locations.add("classpath:db/lineage/" + (remoteFeatures ? "features-remote" : "features-local"));
            if (!remoteFeatures && connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                locations.add("classpath:db/lineage/features-local-postgresql");
            Applied voiceOrFlight = history.get("202610260001");
            if (voiceOrFlight != null && (!voiceOrFlight.success()
                    || !Integer.valueOf(voiceOrFlight.checksum()).equals(VOICE_AND_FLIGHT.get(voiceOrFlight.script()))))
                throw new IllegalStateException("Unrecognized 20261026 migration history; preserve the database and original scripts (do not repair checksums)");
            boolean flight = voiceOrFlight != null && voiceOrFlight.script().equals("V202610260001__flight_avoidance.sql");
            locations.add("classpath:db/lineage/" + (flight ? "flight-avoidance" : "voice-order"));
            configuration.locations(locations.toArray(String[]::new));
            selectFeatureJavaMigrations(configuration, remoteFeatures);
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

    private static boolean remoteFeatures(Map<String, Applied> history) {
        Applied first = history.get("202610200001");
        boolean remote = first != null && REMOTE_FEATURES.containsKey(first.script());
        var expected = remote ? REMOTE_FEATURES : LOCAL_FEATURES;
        for (String version : FEATURE_VERSIONS) {
            Applied row = history.get(version);
            if (row == null) continue;
            if (first == null || !row.success() || !Integer.valueOf(row.checksum()).equals(expected.get(row.script())))
                throw new IllegalStateException("Unrecognized or mixed 20261020 migration history at version " + version
                        + "; preserve the database and original scripts (do not repair checksums)");
        }
        return remote;
    }

    /** Keep applied Java migration class names/checksums intact while selecting the matching old branch. */
    private static void selectFeatureJavaMigrations(FluentConfiguration configuration, boolean remote) {
        if (Arrays.stream(configuration.getLocations()).noneMatch(location ->
                location.getDescriptor().equals("classpath:db/migration"))) return;
        var classes = new LinkedHashSet<Class<? extends JavaMigration>>();
        var loader = configuration.getClassLoader();
        try {
            var resolver = new PathMatchingResourcePatternResolver(loader);
            for (var resource : resolver.getResources("classpath*:db/migration/*.class")) {
                String filename = resource.getFilename();
                if (filename == null || filename.contains("$")) continue;
                String name = "db.migration." + filename.substring(0, filename.length() - ".class".length());
                if (remote && (name.equals("db.migration.V202610200002__unit_delegation")
                        || name.equals("db.migration.V202610210001__unit_role_copy_origin"))) continue;
                Class<?> candidate = Class.forName(name, false, loader);
                if (JavaMigration.class.isAssignableFrom(candidate)) classes.add(candidate.asSubclass(JavaMigration.class));
            }
        } catch (ReflectiveOperationException | java.io.IOException error) {
            throw new IllegalStateException("Cannot select original Java migrations; no migrations were applied", error);
        }
        configuration.javaMigrationClassProvider(() -> classes);
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
                     + " WHERE version IN ('202610160001','202610160002','202610160003','202610169001',"
                     + "'202610200001','202610200002','202610200003','202610200004','202610200005','202610200006',"
                     + "'202610200007','202610210001','202610260001')")) {
            while (result.next()) {
                int checksum = result.getInt("checksum");
                if (result.wasNull()) throw new IllegalStateException("Missing checksum in migration lineage history");
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
