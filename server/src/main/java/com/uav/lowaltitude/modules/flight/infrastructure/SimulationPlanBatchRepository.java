package com.uav.lowaltitude.modules.flight.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SimulationPlanBatchRepository {
    private final JdbcTemplate jdbc;
    public SimulationPlanBatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Unregistered inputs keep their existing rules. Only explicitly registered replay tasks are retired. */
    public static String eligibleSql(String alias) {
        return "(" + alias + ".source_mode<>'replay' OR NOT EXISTS (SELECT 1 FROM simulation_plan_batch_member bm WHERE bm.plan_id=" + alias + ".plan_id)"
            + " OR EXISTS (SELECT 1 FROM simulation_plan_batch_member bm JOIN simulation_plan_batch_current bc ON bc.batch_id=bm.batch_id"
            + " WHERE bm.plan_id=" + alias + ".plan_id AND bm.current_member=TRUE AND bc.owner_org_id=" + alias + ".owner_org_id AND bc.district_id=" + alias + ".district_id))";
    }

    public boolean lockScope(String org, String district) {
        // A permanent parent row serializes first registration as well as subsequent switches.
        return !jdbc.queryForList("SELECT org_id FROM app_org WHERE org_id=? AND enabled=TRUE AND EXISTS"
            + " (SELECT 1 FROM app_district WHERE district_id=? AND enabled=TRUE) FOR UPDATE", String.class, org, district).isEmpty();
    }
    public Batch current(String org, String district) {
        return jdbc.query("SELECT b.batch_id,b.started_at,b.request_body FROM simulation_plan_batch b JOIN simulation_plan_batch_current c"
            + " ON c.batch_id=b.batch_id WHERE c.owner_org_id=? AND c.district_id=?",
            (r,n)->new Batch(r.getString(1),r.getLong(2),r.getString(3)),org,district).stream().findFirst().orElse(null);
    }
    public Batch find(String id) {
        return jdbc.query("SELECT batch_id,started_at,request_body FROM simulation_plan_batch WHERE batch_id=?",
            (r,n)->new Batch(r.getString(1),r.getLong(2),r.getString(3)),id).stream().findFirst().orElse(null);
    }
    public boolean localReplayPlan(String id, String org, String district) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan p WHERE p.plan_id=? AND p.source_mode='replay'"
            + " AND p.owner_org_id=? AND p.district_id=? AND EXISTS (SELECT 1 FROM local_interface_message m"
            + " WHERE m.subject_id=p.plan_id AND m.kind='FLIGHT_PLAN' AND m.direction='IN' AND m.state='ACCEPTED')",
            Long.class,id,org,district)>0;
    }
    public void insert(String id,String org,String district,long started,long now,String actor,String body) {
        jdbc.update("INSERT INTO simulation_plan_batch(batch_id,owner_org_id,district_id,started_at,registered_at,created_by,request_body) VALUES(?,?,?,?,?,?,?)",
            id,org,district,started,now,actor,body);
    }
    public void member(String batch, String plan, boolean current) {
        jdbc.update("INSERT INTO simulation_plan_batch_member(batch_id,plan_id,current_member) VALUES(?,?,?)",batch,plan,current);
    }
    public void activate(String org,String district,String batch) {
        if(jdbc.update("UPDATE simulation_plan_batch_current SET batch_id=? WHERE owner_org_id=? AND district_id=?",batch,org,district)==0)
            jdbc.update("INSERT INTO simulation_plan_batch_current(owner_org_id,district_id,batch_id) VALUES(?,?,?)",org,district,batch);
    }
    public record Batch(String id,long startedAt,String body) { }
}
