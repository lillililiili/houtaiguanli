package com.uav.lowaltitude.migration;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class PostgresStage2CompatibilityTest {

    private static final String COMMON = "classpath:db/migration";
    private static final String POSTGRES = "classpath:db/postgresql";
    private static final String SCHEMA_PREFIX = "stage2_compat_";

    DataSource dataSource;

    @BeforeEach
    void connect() {
        dataSource = new DriverManagerDataSource(
                System.getenv("POSTGRES_TEST_URL"),
                System.getenv("POSTGRES_TEST_USER"),
                System.getenv("POSTGRES_TEST_PASSWORD"));
    }

    @Test
    void migratesAnEmptySchemaAndEnforcesPostgresOnlyContracts() throws Exception {
        withRandomSchema(schema -> {
            JdbcTemplate root = new JdbcTemplate(dataSource);
            assertThat(root.queryForObject("show server_version", String.class)).startsWith("16.");
            assertThat(root.queryForObject(
                    "select extversion from pg_extension where extname='postgis'", String.class))
                    .startsWith("3.5");

            // 期望数取自当前类路径的待执行迁移，避免后续阶段每加一个迁移就让阶段 2 兼容测试过期。
            int pending = stage2Flyway(schema).info().pending().length;
            assertThat(pending).isGreaterThanOrEqualTo(13);
            MigrateResult first = stage2Flyway(schema).migrate();
            assertThat(first.migrationsExecuted).isEqualTo(pending);
            assertThat(stage2Flyway(schema).migrate().migrationsExecuted).isZero();

            withSchema(schema, jdbc -> {
                assertThat(tableNames(jdbc, schema)).contains(
                        "ops_device", "ops_target_latest_state",
                        "integration_source", "device", "target", "alarm");
                assertThat(formatType(jdbc, schema, "device", "location"))
                        .isEqualTo("geometry(Point,4326)");
                assertThat(formatType(jdbc, schema, "target_latest_state", "observed_at"))
                        .isEqualTo("timestamp with time zone");
                assertThat(dataType(jdbc, schema, "inbox_message", "payload")).isEqualTo("jsonb");
                assertThat(jdbc.queryForObject("""
                        select count(*) from app_role_permission
                        where permission_code in ('device:read','target:read','alarm:read')
                        """, Integer.class)).isZero();

                Map<String, String> indexes = indexDefinitions(jdbc, schema);
                assertThat(indexes.get("idx_stage2_device_location_gist"))
                        .containsIgnoringCase("using gist");
                assertThat(indexes.get("idx_stage2_device_source_external_unique"))
                        .containsIgnoringCase("unique").containsIgnoringCase("where");
                assertThat(indexes.get("idx_stage2_device_history_display_time"))
                        .containsIgnoringCase("coalesce(observed_at, received_at)");

                jdbc.update("""
                        insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)
                        values ('org-a','ORG-A','Org A',true,0,0,0),
                               ('org-b','ORG-B','Org B',true,0,0,0)
                        """);
                jdbc.update("update app_org set parent_id='org-b' where org_id='org-a'");
                assertThatThrownBy(() -> jdbc.update(
                        "update app_org set parent_id='org-a' where org_id='org-b'"))
                        .isInstanceOf(DataIntegrityViolationException.class);

                assertThatThrownBy(() -> jdbc.update("""
                        insert into device (
                            device_id,device_no,name,location,source_mode,
                            created_at,updated_at,version
                        ) values (
                            'bad-location','BAD-LOCATION','Bad location',
                            ST_SetSRID(ST_MakePoint(181,0),4326),'replay',
                            current_timestamp,current_timestamp,0
                        )
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);

                jdbc.update("""
                        insert into device (
                            device_id,device_no,name,source_mode,created_at,updated_at,version
                        ) values (
                            'device-state','DEVICE-STATE','Device state','replay',
                            current_timestamp,current_timestamp,0
                        )
                        """);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into device_state (
                            device_id,connectivity,observed_at,received_at,
                            created_at,updated_at,version
                        ) values (
                            'device-state','ABNORMAL',current_timestamp,current_timestamp,
                            current_timestamp,current_timestamp,0
                        )
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);

                jdbc.update("""
                        insert into integration_source (
                            source_id,source_code,name,source_mode,created_at,updated_at
                        ) values ('source-pg','SOURCE-PG','Postgres source','replay',
                            current_timestamp,current_timestamp)
                        """);
                jdbc.update("""
                        insert into device (
                            device_id,source_id,external_device_id,device_no,name,source_mode,
                            created_at,updated_at
                        ) values ('device-pg-1','source-pg','external-1','DEVICE-PG-1',
                            'Device one','replay',current_timestamp,current_timestamp)
                        """);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into device (
                            device_id,source_id,external_device_id,device_no,name,source_mode,
                            created_at,updated_at
                        ) values ('device-pg-2','source-pg','external-1','DEVICE-PG-2',
                            'Device two','replay',current_timestamp,current_timestamp)
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into device (
                            device_id,device_no,name,location,source_mode,created_at,updated_at
                        ) values ('device-empty','DEVICE-EMPTY','Empty point',
                            ST_GeomFromText('POINT EMPTY',4326),'replay',
                            current_timestamp,current_timestamp)
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);

                jdbc.update("""
                        insert into target (
                            target_id,target_no,source_mode,created_at,updated_at
                        ) values ('target-pg','TARGET-PG','replay',current_timestamp,current_timestamp)
                        """);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into target_latest_state (
                            target_id,location,observed_at,received_at,created_at,updated_at
                        ) values ('target-pg',ST_GeomFromText('POINT EMPTY',4326),
                            current_timestamp,current_timestamp,current_timestamp,current_timestamp)
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);
                jdbc.update("""
                        insert into target_source_link (
                            link_id,target_id,source_id,source_session_key,external_target_id,created_at
                        ) values ('link-pg','target-pg','source-pg','session-pg','target-external-pg',
                            current_timestamp)
                        """);
                jdbc.update("""
                        insert into track (
                            track_id,target_id,link_id,external_track_id,created_at
                        ) values ('track-pg','target-pg','link-pg','track-external-pg',current_timestamp)
                        """);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into track_point (
                            point_id,track_id,point_seq,received_at,location,created_at
                        ) values ('point-empty','track-pg',1,current_timestamp,
                            ST_GeomFromText('POINT EMPTY',4326),current_timestamp)
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into alarm (
                            alarm_id,source_id,source_alarm_id,alarm_type,severity,
                            received_at,source_mode,created_at
                        ) values ('alarm-no-source',null,'alarm-1','RADAR','UNKNOWN',
                            current_timestamp,'replay',current_timestamp)
                        """))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        insert into inbox_message (
                            inbox_id,source,source_msg_id,received_at,payload_hash
                        ) values ('bad-hash','replay:test','bad-hash',1,?)
                        """, "A".repeat(64)))
                        .isInstanceOf(DataIntegrityViolationException.class);

                jdbc.update("""
                        insert into app_district (
                            district_id,district_code,name,enabled,created_at,updated_at,version
                        ) values ('district-a','DISTRICT-A','District A',true,0,0,0),
                                 ('district-b','DISTRICT-B','District B',true,0,0,0)
                        """);
                jdbc.update("update app_district set parent_id='district-b' where district_id='district-a'");
                assertThatThrownBy(() -> jdbc.update(
                        "update app_district set parent_id='district-a' where district_id='district-b'"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            });
        });
    }

    @Test
    void upgradesARealV1V2SchemaWithoutChangingTheLegacyMigrations() throws Exception {
        withRandomSchema(schema -> {
            Flyway legacy = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(false)
                    .cleanDisabled(true)
                    .locations(COMMON)
                    .target(MigrationVersion.fromVersion("2"))
                    .load();
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(2);

            // 期望数取自当前类路径的待执行迁移，而不是手抄常量：后续阶段每加一个迁移都不该让阶段 2 兼容测试过期。
            int pending = stage2Flyway(schema).info().pending().length;
            assertThat(pending).isGreaterThanOrEqualTo(11);
            MigrateResult upgrade = stage2Flyway(schema).migrate();
            assertThat(upgrade.migrationsExecuted).isEqualTo(pending);
            assertThat(stage2Flyway(schema).migrate().migrationsExecuted).isZero();
            withSchema(schema, jdbc -> assertThat(tableNames(jdbc, schema)).contains(
                    "ops_device", "device", "target", "alarm"));
        });
    }

    @Test
    void preservesPopulatedOperationsDataWhenUpgradingFromV5ToV6() throws Exception {
        withRandomSchema(schema -> {
            Flyway deployed = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(false)
                    .cleanDisabled(true)
                    .locations(COMMON)
                    .target(MigrationVersion.fromVersion("202609040005"))
                    .load();
            assertThat(deployed.migrate().migrationsExecuted).isEqualTo(11);

            withSchema(schema, this::insertPopulatedV5OperationsGraph);

            int pending = stage2Flyway(schema).info().pending().length;
            assertThat(pending).isGreaterThanOrEqualTo(2);
            MigrateResult upgrade = stage2Flyway(schema).migrate();
            assertThat(upgrade.migrationsExecuted).isEqualTo(pending);
            assertThat(stage2Flyway(schema).migrate().migrationsExecuted).isZero();

            withSchema(schema, jdbc -> {
                assertThat(jdbc.queryForObject(
                        "select source_code from ops_integration_source where source_id='ops-source'",
                        String.class)).isEqualTo("OPS-SOURCE");
                assertThat(jdbc.queryForObject(
                        "select device_no from ops_device where device_id='ops-device'",
                        String.class)).isEqualTo("OPS-DEVICE");
                assertThat(jdbc.queryForObject(
                        "select connectivity from ops_device_state where device_id='ops-device'",
                        String.class)).isEqualTo("ABNORMAL");
                assertThat(jdbc.queryForObject(
                        "select count(*) from ops_device_state_history where device_id='ops-device'",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select ops_source_id from inbox_message where inbox_id='ops-inbox'",
                        String.class)).isEqualTo("ops-source");
                assertThat(jdbc.queryForObject(
                        "select ops_processed_at from inbox_message where inbox_id='ops-inbox'",
                        Long.class)).isEqualTo(222L);
                assertThat(jdbc.queryForObject(
                        "select ops_lease_token from inbox_message where inbox_id='ops-inbox'",
                        String.class)).isEqualTo("ops-lease");
                assertThat(jdbc.queryForObject(
                        "select count(*) from ops_target_source_link l "
                                + "join sensing_target t on t.target_id=l.target_id "
                                + "join ops_device d on d.device_id=l.device_id "
                                + "where l.link_id='ops-link'",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from ops_track_point p "
                                + "join ops_track t on t.track_id=p.track_id "
                                + "join ops_device d on d.device_id=t.device_id "
                                + "where p.track_point_id='ops-point'",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from device_connection_profile p "
                                + "join ops_device d on d.device_id=p.device_id "
                                + "where p.device_id='ops-device'",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from device", Integer.class)).isZero();
                assertThatThrownBy(() -> jdbc.update(
                        "delete from ops_device where device_id='ops-device'"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            });
        });
    }

    private void insertPopulatedV5OperationsGraph(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into integration_source (
                    source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,simulated,created_at,updated_at,version
                ) values ('ops-source','OPS-SOURCE','Operations source','RADAR_TCP_V3_0_0','3.0.0',
                    'live',true,false,100,101,7)
                """);
        jdbc.update("""
                insert into device (
                    device_id,source_id,external_device_id,device_no,name,device_type_name,channel,
                    source_mode,simulated,created_at,updated_at,version
                ) values ('ops-device','ops-source','external-device','OPS-DEVICE','Operations device',
                    'Radar','TCP','live',false,110,111,8)
                """);
        jdbc.update("""
                insert into device_connection_profile (device_id,transport,host,port,updated_at)
                values ('ops-device','TCP','127.0.0.1',9000,112)
                """);
        jdbc.update("""
                insert into device_state (
                    device_id,connectivity,has_alarm,observed_at,received_at,simulated,version
                ) values ('ops-device','ABNORMAL',true,120,121,false,9)
                """);
        jdbc.update("""
                insert into device_state_history (
                    state_id,device_id,connectivity,observed_at,received_at,simulated
                ) values ('ops-state-history','ops-device','ABNORMAL',120,121,false)
                """);
        jdbc.update("""
                insert into inbox_message (
                    inbox_id,source,source_msg_id,received_at,source_id,protocol_message_key,
                    payload_sha256,payload_bytes,processing_status,processed_at,lease_token,
                    lease_until,failure_reason
                ) values ('ops-inbox','live-device:ops-device','message-1',200,'ops-source','message-1',
                    ?,decode('00','hex'),'PROCESSED',222,'ops-lease',333,null)
                """, "a".repeat(64));
        jdbc.update("""
                insert into sensing_target (
                    target_id,target_no,primary_device_id,radar_classification,category_code,
                    active,first_seen_at,last_seen_at,created_at,updated_at
                ) values ('ops-target','OPS-TARGET','ops-device',1,'UAV',true,300,301,300,301)
                """);
        jdbc.update("""
                insert into target_source_link (
                    link_id,target_id,device_id,radar_boot_micros,external_track_id,created_at
                ) values ('ops-link','ops-target','ops-device',400,'42',401)
                """);
        jdbc.update("""
                insert into target_latest_state (
                    target_id,raw_x_m,raw_y_m,raw_z_m,observed_at,received_at,frame_id
                ) values ('ops-target',1,2,3,410,411,'frame-1')
                """);
        jdbc.update("""
                insert into track (
                    track_id,target_id,device_id,radar_boot_micros,external_track_id,
                    started_at,last_point_at,active
                ) values ('ops-track','ops-target','ops-device',400,'42',420,421,true)
                """);
        jdbc.update("""
                insert into track_point (
                    track_point_id,track_id,frame_id,observed_at,received_at,raw_x_m,raw_y_m,raw_z_m
                ) values ('ops-point','ops-track','frame-1',420,421,1,2,3)
                """);
    }

    private Flyway stage2Flyway(String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .cleanDisabled(true)
                .locations(COMMON, POSTGRES)
                .load();
    }

    private List<String> tableNames(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForList("""
                select table_name from information_schema.tables where table_schema=?
                """, String.class, schema);
    }

    private String formatType(JdbcTemplate jdbc, String schema, String table, String column) {
        return jdbc.queryForObject("""
                select format_type(a.atttypid,a.atttypmod)
                from pg_attribute a
                join pg_class c on c.oid=a.attrelid
                join pg_namespace n on n.oid=c.relnamespace
                where n.nspname=? and c.relname=? and a.attname=?
                  and a.attnum>0 and not a.attisdropped
                """, String.class, schema, table, column);
    }

    private String dataType(JdbcTemplate jdbc, String schema, String table, String column) {
        return jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_schema=? and table_name=? and column_name=?
                """, String.class, schema, table, column);
    }

    private Map<String, String> indexDefinitions(JdbcTemplate jdbc, String schema) {
        return jdbc.query("select indexname,indexdef from pg_indexes where schemaname=?", resultSet -> {
            java.util.LinkedHashMap<String, String> indexes = new java.util.LinkedHashMap<>();
            while (resultSet.next()) indexes.put(resultSet.getString(1), resultSet.getString(2));
            return indexes;
        }, schema);
    }

    private void withRandomSchema(ThrowingConsumer<String> action) throws Exception {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        assertSafeSchema(schema);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            action.accept(schema);
        } finally {
            assertSafeSchema(schema);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private void withSchema(String schema, ThrowingConsumer<JdbcTemplate> action) throws Exception {
        assertSafeSchema(schema);
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema + ", public");
            }
            action.accept(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        }
    }

    private void assertSafeSchema(String schema) {
        assertThat(schema).matches("^stage2_compat_[a-f0-9]{32}$");
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
