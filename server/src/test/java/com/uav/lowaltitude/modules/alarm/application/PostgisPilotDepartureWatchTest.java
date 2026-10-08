package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the production departure queries against isolated PostGIS relations. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".+")
class PostgisPilotDepartureWatchTest {
    @Test
    void liveAndUnknownEventsIgnoreSimulationWhileSimulationCanUseLiveAirspace() {
        String schema = "pilot_departure_source_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("pilot_departure_source_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        String url = required("POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase().contains("currentschema=")) throw new IllegalStateException("Expected isolated PostgreSQL URL");
        DataSource rootSource = new DriverManagerDataSource(url, required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
        JdbcTemplate root = new JdbcTemplate(rootSource);
        root.execute("create schema " + schema);
        try {
            DataSource scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            // Minimal relations isolate the actual production query, without unrelated lifecycle fixtures.
            jdbc.execute("create table alarm (alarm_id text primary key,target_id text,source_mode text)");
            jdbc.execute("create table uav_event (event_id text primary key,alarm_id text,owner_org_id text,district_id text)");
            jdbc.execute("create table track (track_id text primary key,target_id text)");
            jdbc.execute("create table track_point (track_id text,observed_at timestamptz,location geometry(Point,4326),point_kind text default 'MEAS')");
            jdbc.execute("create table airspace (airspace_id text primary key,owner_org_id text,district_id text,source_mode text)");
            jdbc.execute("create table airspace_version (airspace_id text,boundary geometry(MultiPolygon,4326),valid_from timestamptz,valid_to timestamptz)");
            jdbc.execute(EVALUATION_TABLE);
            jdbc.update("insert into alarm values ('alarm','target','live')");
            jdbc.update("insert into uav_event values ('event','alarm','org','district')");
            jdbc.update("insert into track values ('track','target')");
            jdbc.update("insert into track_point(track_id,observed_at,location) values ('track','2026-09-27T00:00:00Z',ST_SetSRID(ST_MakePoint(118.5,37.5),4326)),('track','2026-09-27T00:00:10Z',ST_SetSRID(ST_MakePoint(118.6,37.6),4326))");
            jdbc.update("insert into airspace values ('area','org','district','mock')");
            jdbc.update("insert into airspace_version values ('area',ST_Multi(ST_GeomFromText('POLYGON((118 37,119 37,119 38,118 38,118 37))',4326)),'2026-09-26T00:00:00Z',null)");
            PostgisPilotDepartureWatch watch = new PostgisPilotDepartureWatch(jdbc, scoped);
            long accepted = Instant.parse("2026-09-27T00:00:01Z").toEpochMilli();
            long now = Instant.parse("2026-09-27T00:00:11Z").toEpochMilli();
            for (String areaMode : Arrays.asList("live", "mock", "replay")) {
                jdbc.update("update airspace set source_mode=?", areaMode);
                for (String eventMode : Arrays.asList("live", "unknown", null, "mock", "replay")) {
                    jdbc.update("update alarm set source_mode=?", eventMode);
                    boolean eligible = "live".equals(areaMode) || "mock".equals(eventMode) || "replay".equals(eventMode);
                    assertThat(watch.assess("event", accepted, now))
                            .as("event %s / airspace %s", eventMode, areaMode)
                            .isEqualTo(eligible ? PilotDepartureWatch.Presence.STILL_PRESENT : PilotDepartureWatch.Presence.UNKNOWN);
                }
            }
            // A later observation outside a simulated area must not fabricate a live departure.
            jdbc.update("update airspace set source_mode='mock'");
            jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(120,39),4326) where observed_at='2026-09-27T00:00:10Z'");
            jdbc.update("update alarm set source_mode='live'");
            assertThat(watch.assess("event", accepted, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            jdbc.update("update alarm set source_mode='mock'");
            assertThat(watch.assess("event", accepted, now)).isEqualTo(PilotDepartureWatch.Presence.LEFT);
            // A newer prediction or bridge is not a fresh measured observation, inside or outside.
            for (String kind : new String[] { "PRED", "BRIDGE", "UNKNOWN" }) {
                jdbc.update("insert into track_point values ('track','2026-09-27T00:00:11Z',ST_SetSRID(ST_MakePoint(118.6,37.6),4326),?)", kind);
                assertThat(watch.assess("event", accepted, now)).as("latest %s inside", kind)
                        .isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
                jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(120,39),4326) where observed_at='2026-09-27T00:00:11Z'");
                assertThat(watch.assess("event", accepted, now)).as("latest %s outside", kind)
                        .isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
                jdbc.update("delete from track_point where observed_at='2026-09-27T00:00:11Z'");
            }
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void afterCallKeepsTheAreaTheTargetWasInWhenTheSmsWasSent() {
        String schema = "pilot_departure_call_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("pilot_departure_call_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        String url = required("POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase().contains("currentschema=")) throw new IllegalStateException("Expected isolated PostgreSQL URL");
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(url, required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
        root.execute("create schema " + schema);
        try {
            DataSource scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            jdbc.execute("create table alarm (alarm_id text primary key,target_id text,source_mode text)");
            jdbc.execute("create table uav_event (event_id text primary key,alarm_id text,owner_org_id text,district_id text)");
            jdbc.execute("create table track (track_id text primary key,target_id text)");
            jdbc.execute("create table track_point (track_id text,observed_at timestamptz,location geometry(Point,4326),point_kind text default 'MEAS')");
            jdbc.execute("create table airspace (airspace_id text primary key,owner_org_id text,district_id text,source_mode text)");
            jdbc.execute("create table airspace_version (airspace_id text,boundary geometry(MultiPolygon,4326),valid_from timestamptz,valid_to timestamptz)");
            jdbc.execute(EVALUATION_TABLE);
            jdbc.update("insert into alarm values ('alarm','target','mock')");
            jdbc.update("insert into uav_event values ('event','alarm','org','district')");
            jdbc.update("insert into track values ('track','target')");
            jdbc.update("insert into airspace values ('area','org','district','mock')");
            jdbc.update("insert into airspace_version values ('area',ST_Multi(ST_GeomFromText('POLYGON((118 37,119 37,119 38,118 38,118 37))',4326)),'2026-09-26T00:00:00Z',null)");
            // 短信发出时在空域内；电话期间已经飞出；录音播完后的新位置也在外面。
            jdbc.update("insert into track_point(track_id,observed_at,location) values ('track','2026-09-27T00:00:00Z',ST_SetSRID(ST_MakePoint(118.5,37.5),4326)),"
                    + "('track','2026-09-27T00:00:05Z',ST_SetSRID(ST_MakePoint(120,39),4326)),('track','2026-09-27T00:00:10Z',ST_SetSRID(ST_MakePoint(120,39),4326))");
            PostgisPilotDepartureWatch watch = new PostgisPilotDepartureWatch(jdbc, scoped);
            long sms = Instant.parse("2026-09-27T00:00:01Z").toEpochMilli();
            long played = Instant.parse("2026-09-27T00:00:06Z").toEpochMilli();
            long now = Instant.parse("2026-09-27T00:00:16Z").toEpochMilli();
            // 区域按短信发出时的位置确定，不能按录音播完时的位置（那时已在任何空域之外，区域会算成空的）。
            assertThat(watch.assess("event", sms, played, now)).isEqualTo(PilotDepartureWatch.Presence.LEFT);
            // 录音播完后又回到短信时的空域里，仍算在场。
            jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(118.6,37.6),4326) where observed_at='2026-09-27T00:00:10Z'");
            assertThat(watch.assess("event", sms, played, now)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
            // 只认录音播完之后的位置：播完前的点不能当成电话后的观察。
            jdbc.update("delete from track_point where observed_at='2026-09-27T00:00:10Z'");
            assertThat(watch.assess("event", sms, played, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            // 观察起点早于短信送达，参数不成立。
            assertThat(watch.assess("event", played, sms, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void outsideEveryAirspaceAtSmsTheLatestLegalityEvaluationDecides() {
        String schema = "pilot_departure_eval_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("pilot_departure_eval_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        String url = required("POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase().contains("currentschema=")) throw new IllegalStateException("Expected isolated PostgreSQL URL");
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(url, required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
        root.execute("create schema " + schema);
        try {
            DataSource scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            jdbc.execute("create table alarm (alarm_id text primary key,target_id text,source_mode text)");
            jdbc.execute("create table uav_event (event_id text primary key,alarm_id text,owner_org_id text,district_id text)");
            jdbc.execute("create table track (track_id text primary key,target_id text)");
            jdbc.execute("create table track_point (track_id text,observed_at timestamptz,location geometry(Point,4326),point_kind text default 'MEAS')");
            jdbc.execute("create table airspace (airspace_id text primary key,owner_org_id text,district_id text,source_mode text)");
            jdbc.execute("create table airspace_version (airspace_id text,boundary geometry(MultiPolygon,4326),valid_from timestamptz,valid_to timestamptz)");
            jdbc.execute(EVALUATION_TABLE);
            jdbc.update("insert into alarm values ('alarm','target','mock')");
            jdbc.update("insert into uav_event values ('event','alarm','org','district')");
            jdbc.update("insert into track values ('track','target')");
            jdbc.update("insert into airspace values ('zone','org','district','mock')");
            jdbc.update("insert into airspace_version values ('zone',ST_Multi(ST_GeomFromText('POLYGON((118 37,119 37,119 38,118 38,118 37))',4326)),'2026-09-26T00:00:00Z',null)");
            // 短信发出时已偏离航线、不在任何空域；之后飞进禁飞区。
            jdbc.update("insert into track_point(track_id,observed_at,location) values ('track','2026-09-27T00:00:00Z',ST_SetSRID(ST_MakePoint(120,39),4326)),"
                    + "('track','2026-09-27T00:00:10Z',ST_SetSRID(ST_MakePoint(118.5,37.5),4326))");
            PostgisPilotDepartureWatch watch = new PostgisPilotDepartureWatch(jdbc, scoped);
            long sms = Instant.parse("2026-09-27T00:00:01Z").toEpochMilli();
            long now = Instant.parse("2026-09-27T00:00:11Z").toEpochMilli();
            assertThat(watch.inAreaAtSms("event", sms)).isFalse();
            // 没有研判仍不能判定。
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            // 最新研判仍是违规：没有撤离，要打电话。短信前的那次研判也算，规则引擎约 5 秒才重评一次。
            evaluation(jdbc, "e1", "ILLEGAL", "FRESH", "2026-09-27T00:00:00Z", "2026-09-27T00:00:00.500Z", "mock", "ACTIVE");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
            // 研判看的那一帧比最新位置早 30 秒以上，不能代表现在。
            jdbc.update("update rule_evaluation set observed_at='2026-09-26T23:59:39Z' where evaluation_id='e1'");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            jdbc.update("update rule_evaluation set observed_at='2026-09-27T00:00:00Z',freshness_code='STALE' where evaluation_id='e1'");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            jdbc.update("update rule_evaluation set freshness_code='FRESH' where evaluation_id='e1'");
            // 别的数据来源、影子规则、还没到的研判都不算。
            evaluation(jdbc, "e2", "LEGAL", "FRESH", "2026-09-27T00:00:09Z", "2026-09-27T00:00:09.500Z", "live", "ACTIVE");
            evaluation(jdbc, "e3", "LEGAL", "FRESH", "2026-09-27T00:00:09Z", "2026-09-27T00:00:09.500Z", "mock", "SHADOW");
            evaluation(jdbc, "e4", "LEGAL", "FRESH", "2026-09-27T00:00:09Z", "2026-09-27T00:00:12Z", "mock", "ACTIVE");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
            // 不可判定仍不能判定。
            evaluation(jdbc, "e5", "UNDETERMINED", "FRESH", "2026-09-27T00:00:05Z", "2026-09-27T00:00:05.500Z", "mock", "ACTIVE");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            // 短信之后的位置判为合法（例如回到任务航线）：已撤离，不打电话。
            evaluation(jdbc, "e6", "LEGAL", "FRESH", "2026-09-27T00:00:08Z", "2026-09-27T00:00:08.500Z", "mock", "ACTIVE");
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.LEFT);
            // 电话后的观察只认录音播完之后的研判：播完前就合法的不算撤离。
            long played = Instant.parse("2026-09-27T00:00:09Z").toEpochMilli();
            assertThat(watch.assess("event", sms, played, now)).isEqualTo(PilotDepartureWatch.Presence.UNKNOWN);
            evaluation(jdbc, "e7", "ILLEGAL", "FRESH", "2026-09-27T00:00:10Z", "2026-09-27T00:00:10.500Z", "mock", "ACTIVE");
            assertThat(watch.assess("event", sms, played, now)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
            // 短信发出时在空域里的仍按空域判断，不看研判：飞出空域就是撤离。
            jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(118.5,37.5),4326) where observed_at='2026-09-27T00:00:00Z'");
            jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(120,39),4326) where observed_at='2026-09-27T00:00:10Z'");
            assertThat(watch.inAreaAtSms("event", sms)).isTrue();
            assertThat(watch.assess("event", sms, now)).isEqualTo(PilotDepartureWatch.Presence.LEFT);
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    private static final String EVALUATION_TABLE = "create table rule_evaluation (evaluation_id text primary key,target_id text,owner_org_id text,district_id text,"
            + "source_mode text,mode text,subject_kind text,observed_at timestamptz,evaluated_at timestamptz,freshness_code text,legal_status text)";
    private static void evaluation(JdbcTemplate jdbc, String id, String legal, String freshness, String observed, String evaluated, String sourceMode, String mode) {
        jdbc.update("insert into rule_evaluation values (?,'target','org','district',?,?,'TARGET',cast(? as timestamptz),cast(? as timestamptz),?,?)",
                id, sourceMode, mode, observed, evaluated, freshness, legal);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
