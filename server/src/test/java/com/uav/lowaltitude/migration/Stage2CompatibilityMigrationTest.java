package com.uav.lowaltitude.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Stage2CompatibilityMigrationTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateIntegratedMain() {
        String databaseUrl = "jdbc:h2:mem:stage2_compat_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(databaseUrl, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void keepsTheDeviceOperationsModelSeparateFromTheStage2ReadModel() throws Exception {
        assertThat(tableNames()).contains(
                "ops_integration_source",
                "ops_device",
                "ops_device_state",
                "ops_device_state_history",
                "ops_target_source_link",
                "ops_target_latest_state",
                "ops_track",
                "ops_track_point");

        assertThat(tableNames()).contains(
                "integration_source",
                "device",
                "device_state",
                "device_state_history",
                "target",
                "target_source_link",
                "target_latest_state",
                "track",
                "track_point",
                "alarm");
    }

    @Test
    void restoresTheStage2InboxAndConnectivityContract() {
        assertThat(columns("inbox_message")).contains(
                "source_id", "payload_hash", "payload", "status",
                "processed_at", "last_error", "lease_token", "lease_until");

        assertThatThrownBy(() -> jdbc.update("""
                insert into inbox_message (
                    inbox_id, source, source_msg_id, received_at, status, lease_token
                ) values ('bad-lease', 'replay:test', '1', 1, 'RECEIVED', 'lease')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertOwnership();
        jdbc.update("""
                insert into device (
                    device_id, device_no, name, source_mode, owner_org_id, district_id,
                    created_at, updated_at, version
                ) values ('device-1', 'DEVICE-1', 'Radar', 'replay', 'org-1', 'district-1',
                    current_timestamp, current_timestamp, 0)
                """);

        assertThatThrownBy(() -> jdbc.update("""
                insert into device_state (
                    device_id, connectivity, observed_at, received_at,
                    created_at, updated_at, version
                ) values ('device-1', 'ABNORMAL', current_timestamp, current_timestamp,
                    current_timestamp, current_timestamp, 0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void addsActionPermissionCatalogRowsWithoutGrantingThem() {
        assertThat(jdbc.queryForList("""
                select permission_code from app_permission
                where permission_code in ('device:read', 'target:read', 'alarm:read')
                order by permission_code
                """, String.class)).containsExactly("alarm:read", "device:read", "target:read");
        assertThat(jdbc.queryForObject("""
                select count(*) from app_role_permission
                where permission_code in ('device:read', 'target:read', 'alarm:read')
                """, Integer.class)).isZero();
    }

    @Test
    void enforcesPortableInboxDeviceAndUnknownValueConstraints() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into inbox_message (
                    inbox_id, source, source_msg_id, received_at, payload_hash
                ) values ('bad-hash', 'replay:test', 'bad-hash', 1, ?)
                """, "G".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into inbox_message (
                    inbox_id, source, source_msg_id, received_at, payload_hash
                ) values ('upper-hash', 'replay:test', 'upper-hash', 1, ?)
                """, "A".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertOwnership();
        assertThatThrownBy(() -> jdbc.update("""
                insert into device (
                    device_id, device_no, name, altitude_m, source_mode,
                    owner_org_id, district_id, created_at, updated_at
                ) values ('altitude-only', 'ALTITUDE-ONLY', 'Altitude only', 12.5, 'replay',
                    'org-1', 'district-1', current_timestamp, current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into device (
                    device_id, device_no, name, source_mode,
                    owner_org_id, district_id, created_at, updated_at
                ) values ('bad-mode', 'BAD-MODE', 'Bad mode', 'invalid',
                    'org-1', 'district-1', current_timestamp, current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                insert into device (
                    device_id, device_no, name, source_mode,
                    owner_org_id, district_id, created_at, updated_at
                ) values ('device-unknown', 'DEVICE-UNKNOWN', 'Unknown device', 'replay',
                    'org-1', 'district-1', current_timestamp, current_timestamp)
                """);
        Object location = jdbc.queryForObject(
                "select location from device where device_id='device-unknown'",
                (resultSet, rowNumber) -> (Object) resultSet.getObject(1));
        assertThat(location).isNull();
        assertThatThrownBy(() -> jdbc.update("""
                insert into device_state (
                    device_id, connectivity, observed_at, received_at,
                    created_at, updated_at
                ) values ('device-unknown', 'UNKNOWN', current_timestamp, current_timestamp,
                    current_timestamp, current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                insert into device_state (
                    device_id, connectivity, observed_at, received_at, unknown_reason,
                    created_at, updated_at
                ) values ('device-unknown', 'UNKNOWN', current_timestamp, current_timestamp,
                    'NOT_REPORTED', current_timestamp, current_timestamp)
                """);
        Object hasAlarm = jdbc.queryForObject(
                "select has_alarm from device_state where device_id='device-unknown'",
                (resultSet, rowNumber) -> (Object) resultSet.getObject(1));
        assertThat(hasAlarm).isNull();
        assertThat(jdbc.queryForObject("""
                select column_default from information_schema.columns
                where table_name='device_state' and column_name='has_alarm'
                """, String.class)).isNull();
    }

    @Test
    void enforcesTargetTrackAndAlarmIntegrityWithoutGeneratingAlarms() {
        insertOwnership();
        jdbc.update("""
                insert into integration_source (
                    source_id, source_code, name, source_mode, created_at, updated_at
                ) values ('source-1', 'SOURCE-1', 'Source', 'replay',
                    current_timestamp, current_timestamp)
                """);
        jdbc.update("""
                insert into target (
                    target_id, target_no, source_mode, owner_org_id, district_id,
                    created_at, updated_at
                ) values ('target-1', 'TARGET-1', 'replay', 'org-1', 'district-1',
                    current_timestamp, current_timestamp)
                """);
        Object firstSeen = jdbc.queryForObject(
                "select first_seen_at from target where target_id='target-1'",
                (resultSet, rowNumber) -> (Object) resultSet.getObject(1));
        Object lastSeen = jdbc.queryForObject(
                "select last_seen_at from target where target_id='target-1'",
                (resultSet, rowNumber) -> (Object) resultSet.getObject(1));
        assertThat(firstSeen).isNull();
        assertThat(lastSeen).isNull();
        assertThatThrownBy(() -> jdbc.update("""
                insert into target (
                    target_id, target_no, first_seen_at, last_seen_at, source_mode,
                    created_at, updated_at
                ) values ('target-reversed', 'TARGET-REVERSED',
                    timestamp with time zone '2026-09-04 01:00:00+00',
                    timestamp with time zone '2026-09-04 00:00:00+00',
                    'replay', current_timestamp, current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                insert into target_source_link (
                    link_id, target_id, source_id, source_session_key,
                    external_target_id, created_at
                ) values ('link-1', 'target-1', 'source-1', 'session-1',
                    'external-1', current_timestamp)
                """);
        jdbc.update("""
                insert into track (
                    track_id, target_id, link_id, external_track_id, created_at
                ) values ('track-1', 'target-1', 'link-1', 'track-external-1', current_timestamp)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                insert into track_point (
                    point_id, track_id, point_seq, received_at, location, created_at
                ) values ('point-no-location', 'track-1', 1, current_timestamp, null,
                    current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into alarm (
                    alarm_id, source_id, source_alarm_id, alarm_type, severity,
                    received_at, source_mode, created_at
                ) values ('alarm-no-source', null, 'alarm-1', 'RADAR', 'UNKNOWN',
                    current_timestamp, 'replay', current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(columns("alarm")).doesNotContain("uav_event_id");
        assertThat(jdbc.queryForObject("select count(*) from alarm", Integer.class)).isZero();
    }

    @Test
    void alignsIntegratedIdentityColumnWidthsAndQueryIndexes() {
        assertThat(jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_name='app_permission' and column_name='permission_code'
                """, Long.class)).isEqualTo(96L);
        assertThat(jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name='app_user' and column_name='permission_version'
                """, String.class)).isEqualToIgnoringCase("BIGINT");
        assertThat(jdbc.queryForList("""
                select lower(index_name) from information_schema.indexes
                where lower(index_name) like 'idx_stage2_%'
                """, String.class)).contains(
                        "idx_stage2_district_parent",
                        "idx_stage2_user_role_scope",
                        "idx_stage2_role_permission_reverse",
                        "idx_stage2_user_scope_reverse");
    }

    private void insertOwnership() {
        jdbc.update("""
                insert into app_org (org_id, org_code, name, enabled, created_at, updated_at, version)
                values ('org-1', 'ORG-1', 'Org', true, 0, 0, 0)
                """);
        jdbc.update("""
                insert into app_district (district_id, district_code, name, enabled, created_at, updated_at, version)
                values ('district-1', 'DISTRICT-1', 'District', true, 0, 0, 0)
                """);
    }

    private Set<String> tableNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private Set<String> columns(String table) {
        return new HashSet<>(jdbc.queryForList("""
                select lower(column_name) from information_schema.columns
                where lower(table_name) = ?
                """, String.class, table));
    }
}
