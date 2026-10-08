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

/**
 * 用短信发出时目标所在的本区空域作为告警区域，再用之后的新位置判断是否离开。短信发出时不在任何空域的，改看最新违规研判。
 * 没有新位置或不能判定时，不把目标当成已撤离。
 */
@Component
public class PostgisPilotDepartureWatch implements PilotDepartureWatch {
    /** 研判看的那一帧不能比最新位置早太多，否则不能代表现在的情况。规则引擎对持续目标约 5 秒重评一次。 */
    static final long CURRENT_EVALUATION_MILLIS = 30_000L;
    private final JdbcTemplate jdbc;
    private final boolean postgis;
    public PostgisPilotDepartureWatch(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.postgis = postgres(dataSource);
    }
    @Override
    public Presence assess(String eventId, long smsAcceptedAt, long now) {
        return assess(eventId, smsAcceptedAt, smsAcceptedAt, now);
    }
    /** 区域取短信发出时目标所在的空域；只认 since 之后的新位置（电话后传录音播完时刻）。 */
    @Override
    public Presence assess(String eventId, long smsAcceptedAt, long since, long now) {
        if (!postgis || since < smsAcceptedAt) return Presence.UNKNOWN;
        Point before = point(eventId, null, smsAcceptedAt);
        Point after = point(eventId, since, now);
        if (before == null || after == null || before.lon == null || after.lon == null) return Presence.UNKNOWN;
        Set<String> area = covered(eventId, before, smsAcceptedAt);
        if (area.isEmpty()) return byEvaluation(eventId, since, after, now);
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
    @Override
    public boolean inAreaAtSms(String eventId, long smsAcceptedAt) {
        if (!postgis) return true;
        Point before = point(eventId, null, smsAcceptedAt);
        return before == null || before.lon == null || !covered(eventId, before, smsAcceptedAt).isEmpty();
    }
    /**
     * 短信发出时目标不在任何空域（例如先偏离任务航线、再飞进禁飞区），没有区域可比，改看这架无人机最新一次违规研判
     * （2026-10-09 新-31）：仍判违规就是没有撤离；短信（电话后为录音播完）之后的位置判为合法才算撤离；其它情况仍不能判定。
     */
    private Presence byEvaluation(String eventId, long since, Point after, long now) {
        var rows = jdbc.query("""
                SELECT r.legal_status,r.freshness_code,r.observed_at FROM uav_event e
                JOIN alarm a ON a.alarm_id=e.alarm_id
                JOIN rule_evaluation r ON r.target_id=a.target_id AND r.owner_org_id=e.owner_org_id
                  AND r.district_id=e.district_id AND r.source_mode=a.source_mode
                WHERE e.event_id=? AND r.mode='ACTIVE' AND r.subject_kind='TARGET' AND r.evaluated_at<=?
                ORDER BY r.evaluated_at DESC,r.evaluation_id DESC FETCH FIRST 1 ROW ONLY
                """, (r, n) -> new Evaluation(r.getString(1), r.getString(2), r.getTimestamp(3) == null ? null : r.getTimestamp(3).getTime()),
                eventId, new Timestamp(now));
        if (rows.isEmpty()) return Presence.UNKNOWN;
        Evaluation latest = rows.get(0);
        if (!"FRESH".equals(latest.freshness) || latest.observedAt == null) return Presence.UNKNOWN;
        if ("LEGAL".equals(latest.legal) && latest.observedAt > since) return Presence.LEFT;
        if ("ILLEGAL".equals(latest.legal) && latest.observedAt >= after.observedAt - CURRENT_EVALUATION_MILLIS) return Presence.STILL_PRESENT;
        return Presence.UNKNOWN;
    }
    private Point point(String eventId, Long afterExclusive, long untilInclusive) {
        String window = afterExclusive == null ? " AND p.observed_at<=?" : " AND p.observed_at>? AND p.observed_at<=?";
        Object[] args = afterExclusive == null
                ? new Object[]{eventId, new Timestamp(untilInclusive)}
                : new Object[]{eventId, new Timestamp(afterExclusive), new Timestamp(untilInclusive)};
        var rows = jdbc.query("SELECT p.observed_at, ST_X(p.location), ST_Y(p.location), p.point_kind FROM track_point p"
                + " JOIN track t ON t.track_id=p.track_id JOIN alarm a ON a.target_id=t.target_id"
                + " JOIN uav_event e ON e.alarm_id=a.alarm_id WHERE e.event_id=? AND p.location IS NOT NULL AND p.observed_at IS NOT NULL" + window
                + " ORDER BY p.observed_at DESC FETCH FIRST 1 ROW ONLY",
                (r, n) -> "MEAS".equals(r.getString(4))
                        ? new Point(r.getTimestamp(1).getTime(), r.getBigDecimal(2), r.getBigDecimal(3)) : null, args);
        // 看最新点再判断可信性，不能跳过预测点后复用更早的实测点冒充当前观测。
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
                JOIN alarm event_alarm ON event_alarm.alarm_id=e.alarm_id
                JOIN airspace a ON a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id
                JOIN airspace_version av ON av.airspace_id=a.airspace_id
                CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(?,?),4326) AS geom) pt
                WHERE e.event_id=? AND av.boundary IS NOT NULL
                  AND (a.source_mode='live'
                       OR (event_alarm.source_mode IN ('mock','replay') AND a.source_mode IN ('mock','replay')))
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
    private record Evaluation(String legal, String freshness, Long observedAt) { }
}
