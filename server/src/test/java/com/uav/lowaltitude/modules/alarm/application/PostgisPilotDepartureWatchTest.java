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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
