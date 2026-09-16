package com.uav.lowaltitude.modules.target.infrastructure;

import java.sql.Timestamp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for the bounded local monitor catalog; never called for live observations. */
@Repository
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class LocalAirspaceDemoTargetRepository {
    private final JdbcTemplate jdbc;
    public LocalAirspaceDemoTargetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void createAndLock(String id, String name, boolean bird, String org, String district, Timestamp now) {
        jdbc.update("""
            INSERT INTO target(target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,
                source_mode,owner_org_id,district_id,created_at,updated_at,version)
            VALUES (?,?,?,?,?,?,'mock',?,?,?,?,0) ON CONFLICT (target_id) DO NOTHING
            """, id, name + "（空域模拟）", bird ? "BIRD" : "UNKNOWN", bird ? "BIRD_FLOCK" : "BALLOON",
            now, now, org, district, now, now);
        jdbc.queryForList("SELECT target_id FROM target WHERE target_id=? FOR UPDATE", id);
    }

    public void recordFrame(String id, double lon, double lat, double height, double speed, Double heading, Timestamp now) {
        String unknown = heading == null
            ? "[\"height_agl_m\",\"heading_deg\",\"classification_confidence\",\"fusion_confidence\"]"
            : "[\"height_agl_m\",\"classification_confidence\",\"fusion_confidence\"]";
        jdbc.update("""
            INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,speed_mps,heading_deg,
                observed_at,received_at,unknown_fields,created_at,updated_at,version)
            VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?,?,?,?,CAST(? AS JSON),?,?,0)
            ON CONFLICT (target_id) DO UPDATE SET location=EXCLUDED.location,altitude_amsl_m=EXCLUDED.altitude_amsl_m,
                speed_mps=EXCLUDED.speed_mps,heading_deg=EXCLUDED.heading_deg,observed_at=EXCLUDED.observed_at,
                received_at=EXCLUDED.received_at,unknown_fields=EXCLUDED.unknown_fields,updated_at=EXCLUDED.updated_at,
                version=target_latest_state.version+1
            """, id, lon, lat, height, speed, heading, now, now, unknown, now, now);
        jdbc.update("UPDATE target SET last_seen_at=?,updated_at=?,version=version+1 WHERE target_id=?", now, now, id);
    }
}
