package com.uav.lowaltitude.modules.risk.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** C04-only current observations and append-only clearance evidence. All distances are metres. */
@Repository
public class RiskPresenceRepository {
    private final JdbcTemplate jdbc;
    private final boolean postgis;
    public RiskPresenceRepository(JdbcTemplate jdbc, DataSource source) {
        this.jdbc = jdbc;
        try (var connection = source.getConnection()) {
            postgis = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Cannot determine spatial backend", ex); }
    }
    public boolean available() { return postgis; }
    public record Clearance(long observedAt, long recordedAt) { }
    public Clearance clearance(String riskId) {
        var rows = jdbc.query("select observed_at,recorded_at from risk_clearance_evidence where risk_id=?",
                (rs, i) -> new Clearance(rs.getTimestamp(1).getTime(), rs.getTimestamp(2).getTime()), riskId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<String> pendingC04() {
        return jdbc.queryForList("""
                select r.risk_id from flight_risk r join space_risk_fact f on f.risk_id=r.risk_id
                join rule_version v on v.rule_version_id=f.rule_version_id and v.rule_code='C04'
                where r.risk_type='SPACE_OBJECT' and r.state_code<>'EXCLUDED'
                  and not exists(select 1 from risk_clearance_evidence e where e.risk_id=r.risk_id)
                order by r.risk_id
                """, String.class);
    }
    public boolean c04Fact(String riskId) {
        return jdbc.queryForObject("select count(*) from space_risk_fact f join rule_version v on v.rule_version_id=f.rule_version_id where f.risk_id=? and v.rule_code='C04'", Integer.class, riskId) == 1;
    }
    /** Prove a bad original input from historical evidence, never from the target's current status. */
    public boolean predictionOnlyOrigin(String riskId) {
        if (!postgis) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from flight_risk r
                  join space_risk_fact f on f.risk_id=r.risk_id
                  join rule_version v on v.rule_version_id=f.rule_version_id and v.rule_code='C04'
                  where r.risk_id=? and r.risk_type='SPACE_OBJECT'
                    and exists(select 1 from track tr join track_point p on p.track_id=tr.track_id
                      where tr.target_id=r.target_id and tr.layer='FUSED'
                        and p.observed_at=r.occurred_at and p.received_at<=r.received_at
                        and p.point_kind in ('PRED','BRIDGE') and ST_Equals(p.location,f.target_location))
                    and not exists(select 1 from track tr join track_point p on p.track_id=tr.track_id
                      where tr.target_id=r.target_id and tr.layer='FUSED'
                        and p.observed_at=r.occurred_at and p.received_at<=r.received_at
                        and p.point_kind='MEAS'))
                """, Boolean.class, riskId));
    }
    public record Observation(String pointId, String kind, OffsetDateTime observedAt, OffsetDateTime receivedAt,
            OffsetDateTime latestAt, BigDecimal distance, BigDecimal accuracy, BigDecimal halfWidth,
            boolean sameLocation, String unknownFields, String targetMode, String sourceMode,
            String observationMode, String planMode, String routeMode, boolean determined, boolean sourceScopeValid, int peers,
            OffsetDateTime sourceObservedAt, OffsetDateTime sourceReceivedAt) { }
    public Observation observation(String targetId, String routeId, String planId) {
        if (!postgis) return null;
        var rows = jdbc.query("""
                select p.point_id,p.point_kind,p.observed_at,p.received_at,ls.observed_at latest_at,
                  ST_Distance(rv.centerline::geography,p.location::geography) distance_m,
                  p.position_accuracy_m,rv.corridor_width_m/2 half_width_m,
                  ST_Equals(ls.location,p.location) same_location,CAST(ls.unknown_fields AS VARCHAR) unknown_fields,
                  t.source_mode target_mode,s.source_mode,so.source_mode observation_mode,
                  plan.source_mode plan_mode,rt.source_mode route_mode,COALESCE(d.determined,TRUE) determined,
                  so.observed_at source_observed_at,so.received_at source_received_at,
                  (so.observation_id IS NOT NULL AND so.source_id=p.position_source_id AND so.location IS NOT NULL
                    AND so.owner_org_id=t.owner_org_id AND so.district_id=t.district_id
                    AND so.observed_at<=p.observed_at AND so.received_at<=p.received_at
                    AND EXISTS(select 1 from target_source_link source_link
                      join target source_target on source_target.target_id=source_link.target_id
                      left join target_current_alias alias on alias.historical_target_id=source_link.target_id
                      where source_link.source_id=so.source_id AND source_link.source_session_key=so.source_session_key
                        AND source_link.external_target_id=so.external_target_id
                        AND COALESCE(alias.current_target_id,source_link.target_id)=t.target_id
                        AND source_target.owner_org_id=t.owner_org_id AND source_target.district_id=t.district_id
                        AND source_target.source_mode=t.source_mode)) source_scope_valid,
                  (select count(*) from track_point other join track ot on ot.track_id=other.track_id
                    where ot.target_id=t.target_id and ot.layer=tr.layer and other.observed_at=p.observed_at
                      and (other.point_kind<>p.point_kind or not ST_Equals(other.location,p.location)
                        or other.position_accuracy_m IS DISTINCT FROM p.position_accuracy_m)) peers
                from target t join target_latest_state ls on ls.target_id=t.target_id
                join track tr on tr.target_id=t.target_id and tr.layer='FUSED' join track_point p on p.track_id=tr.track_id
                left join source_observation so on so.observation_id=p.observation_id
                left join integration_source s on s.source_id=p.position_source_id and s.enabled=TRUE
                left join target_degradation d on d.target_id=t.target_id
                join flight_plan plan on plan.plan_id=? join route_version rv on rv.route_version_id=?
                join route rt on rt.route_id=rv.route_id
                where t.target_id=?
                order by p.observed_at desc nulls last,p.received_at desc,p.point_id
                fetch first 1 row only
                """, (rs, i) -> new Observation(rs.getString("point_id"),rs.getString("point_kind"),
                    rs.getObject("observed_at",OffsetDateTime.class),rs.getObject("received_at",OffsetDateTime.class),
                    rs.getObject("latest_at",OffsetDateTime.class),rs.getBigDecimal("distance_m"),rs.getBigDecimal("position_accuracy_m"),
                    rs.getBigDecimal("half_width_m"),rs.getBoolean("same_location"),rs.getString("unknown_fields"),
                    rs.getString("target_mode"),rs.getString("source_mode"),rs.getString("observation_mode"),
                    rs.getString("plan_mode"),rs.getString("route_mode"),rs.getBoolean("determined"),rs.getBoolean("source_scope_valid"),rs.getInt("peers"),
                    rs.getObject("source_observed_at",OffsetDateTime.class),rs.getObject("source_received_at",OffsetDateTime.class)), planId,routeId,targetId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public int append(String riskId, String targetId, String routeId, String ruleVersion, String freshVersion,
            String mode, Observation point, BigDecimal boundary, int freshSeconds, OffsetDateTime now) {
        // Caller holds the risk row lock; one immutable result belongs to one original risk.
        return jdbc.update("""
                insert into risk_clearance_evidence(risk_id,target_id,route_version_id,point_id,rule_set_version_id,
                  freshness_rule_set_version_id,source_mode,observed_at,received_at,recorded_at,distance_m,accuracy_m,boundary_m,freshness_seconds)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,riskId,targetId,routeId,point.pointId(),ruleVersion,freshVersion,mode,point.observedAt(),point.receivedAt(),now,
                    point.distance(),point.accuracy(),boundary,freshSeconds);
    }
}
