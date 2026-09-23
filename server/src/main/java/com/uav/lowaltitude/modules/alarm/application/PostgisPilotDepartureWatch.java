package com.uav.lowaltitude.modules.alarm.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 用短信发出时目标所在的本区空域作为告警区域，再用之后的新位置判断是否离开。没有新位置或区域不能判定时，不把目标当成已撤离。 */
@Component
public class PostgisPilotDepartureWatch implements PilotDepartureWatch {
    private final JdbcTemplate jdbc;
    private final boolean postgis;
    public PostgisPilotDepartureWatch(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.postgis = postgres(dataSource);
    }
    @Override
    public Presence assess(String eventId, long smsAcceptedAt, long now) {
        if (!postgis) return Presence.UNKNOWN;
        Point before = point(eventId, null, smsAcceptedAt);
        Point after = point(eventId, smsAcceptedAt, now);
        if (before == null || after == null || before.lon == null || after.lon == null) return Presence.UNKNOWN;
        Set<String> area = covered(eventId, before, smsAcceptedAt);
        if (area.isEmpty()) return Presence.UNKNOWN;
        List<Hit> later = hits(eventId, after, after.observedAt);
        boolean still = false;
        boolean boundary = false;
        for (Hit hit : later) {
            if (!area.contains(hit.airspaceId)) continue;
            if ("COVERS".equals(hit.relation)) still = true;
            else boundary = true;
        }
        if (still) return Presence.STILL_PRESENT;
        if (boundary) return Presence.UNKNOWN;
        return Presence.LEFT;
    }
    private Point point(String eventId, Long afterExclusive, long untilInclusive) {
        String window = afterExclusive == null ? " AND p.observed_at<=?" : " AND p.observed_at>? AND p.observed_at<=?";
        Object[] args = afterExclusive == null
                ? new Object[]{eventId, new Timestamp(untilInclusive)}
                : new Object[]{eventId, new Timestamp(afterExclusive), new Timestamp(untilInclusive)};
        var rows = jdbc.query("SELECT p.observed_at, ST_X(p.location), ST_Y(p.location) FROM track_point p"
                + " JOIN track t ON t.track_id=p.track_id JOIN alarm a ON a.target_id=t.target_id"
                + " JOIN uav_event e ON e.alarm_id=a.alarm_id WHERE e.event_id=? AND p.location IS NOT NULL AND p.observed_at IS NOT NULL" + window
                + " ORDER BY p.observed_at DESC FETCH FIRST 1 ROW ONLY",
                (r, n) -> new Point(r.getTimestamp(1).getTime(), r.getBigDecimal(2), r.getBigDecimal(3)), args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Set<String> covered(String eventId, Point point, long at) {
        Set<String> ids = new HashSet<>();
        for (Hit hit : hits(eventId, point, at)) if ("COVERS".equals(hit.relation)) ids.add(hit.airspaceId);
        return ids;
    }
    private List<Hit> hits(String eventId, Point point, long at) {
        OffsetDateTime asOf = Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC);
        return jdbc.query("""
                SELECT a.airspace_id,
                  CASE WHEN ST_Touches(av.boundary,pt.geom) THEN 'TOUCHES'
                       WHEN ST_Covers(av.boundary,pt.geom) THEN 'COVERS' ELSE 'OTHER' END AS relation
                FROM uav_event e
                JOIN airspace a ON a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id
                JOIN airspace_version av ON av.airspace_id=a.airspace_id
                CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(?,?),4326) AS geom) pt
                WHERE e.event_id=? AND av.boundary IS NOT NULL
                  AND av.valid_from<=? AND (av.valid_to IS NULL OR ?<av.valid_to)
                  AND av.boundary && pt.geom AND ST_Intersects(av.boundary,pt.geom)
                """, (r, n) -> new Hit(r.getString(1), r.getString(2)),
                point.lon, point.lat, eventId, Timestamp.from(asOf.toInstant()), Timestamp.from(asOf.toInstant()));
    }
    private static boolean postgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception unavailable) { return false; }
    }
    private record Point(long observedAt, BigDecimal lon, BigDecimal lat) { }
    private record Hit(String airspaceId, String relation) { }
}
