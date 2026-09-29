package com.uav.lowaltitude.modules.airspace.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/** Receipts and source locking. Geometry remains owned by AirspaceWriteRepository. */
@Repository
public class UpstreamAirspaceRepository {
    private final JdbcTemplate jdbc;
    public UpstreamAirspaceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Source(String id, String mode, boolean enabled, String protocol) { }
    public record Delivery(String sourceId, String messageId, String airspaceId, String versionId,
            long revision, String action, long effectiveAt, String payload, long receivedAt) { }
    public record Scope(String ownerOrgId,String ownerOrgName,String districtId,String districtName) { }

    public Source lockSource(String id) {
        var rows=jdbc.query("SELECT source_id,source_mode,enabled,protocol_code FROM integration_source WHERE source_id=? FOR UPDATE",
            (r,n)->new Source(r.getString(1),r.getString(2),r.getBoolean(3),r.getString(4)),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean sourceMatches(String id,String source,String mode) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_id=? AND source_id=? AND source_mode=?",
            Long.class,id,source,mode)>0;
    }
    private static final org.springframework.jdbc.core.RowMapper<Delivery> ROW=(r,n)->new Delivery(
        r.getString("source_id"),r.getString("message_id"),r.getString("airspace_id"),r.getString("airspace_version_id"),
        r.getLong("revision"),r.getString("action"),r.getLong("effective_at"),r.getString("payload"),r.getLong("received_at"));
    public Delivery message(String source,String message) {
        var rows=jdbc.query("SELECT * FROM airspace_delivery WHERE source_id=? AND message_id=?",ROW,source,message);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Delivery latest(String source,String airspace) {
        var rows=jdbc.query("SELECT * FROM airspace_delivery WHERE source_id=? AND airspace_id=? ORDER BY revision DESC FETCH FIRST 1 ROWS ONLY",ROW,source,airspace);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void insert(Delivery d,String actor) {
        jdbc.update("INSERT INTO airspace_delivery(source_id,message_id,airspace_id,airspace_version_id,revision,action,effective_at,payload,received_at,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            d.sourceId(),d.messageId(),d.airspaceId(),d.versionId(),d.revision(),d.action(),d.effectiveAt(),d.payload(),d.receivedAt(),actor);
    }
    public boolean scopeAllowed(AccessDecision access,String org,String district) {
        String sql="SELECT COUNT(*) FROM app_org o CROSS JOIN app_district d WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE";
        if(access.scopeMode()==ScopeMode.ASSIGNED)
            return jdbc.queryForObject(sql+" AND EXISTS(SELECT 1 FROM app_user_data_scope s WHERE s.user_id=? AND s.org_id=o.org_id AND s.district_id=d.district_id)",Long.class,org,district,access.userId())>0;
        return access.scopeMode()==ScopeMode.ALL && jdbc.queryForObject(sql,Long.class,org,district)>0;
    }
    public List<Scope> scopes(AccessDecision access) {
        String sql="SELECT o.org_id,o.name,d.district_id,d.name FROM app_org o CROSS JOIN app_district d WHERE o.enabled=TRUE AND d.enabled=TRUE";
        Object[] args={};
        if(access.scopeMode()==ScopeMode.ASSIGNED) {
            sql+=" AND EXISTS(SELECT 1 FROM app_user_data_scope s WHERE s.user_id=? AND s.org_id=o.org_id AND s.district_id=d.district_id)";
            args=new Object[]{access.userId()};
        }
        return jdbc.query(sql+" ORDER BY o.org_id,d.district_id FETCH FIRST 1000 ROWS ONLY",
            (r,n)->new Scope(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),args);
    }
    public List<Delivery> recent(String source,AccessDecision access) {
        String sql="SELECT m.* FROM airspace_delivery m JOIN airspace a ON a.airspace_id=m.airspace_id"
            +" JOIN app_org o ON o.org_id=a.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=a.district_id AND d.enabled=TRUE"
            +" WHERE m.source_id=? AND NOT EXISTS(SELECT 1 FROM airspace_delivery newer WHERE newer.source_id=m.source_id AND newer.airspace_id=m.airspace_id AND newer.revision>m.revision)";
        Object[] args={source};
        if(access.scopeMode()==ScopeMode.ASSIGNED) {
            sql+=" AND EXISTS(SELECT 1 FROM app_user_data_scope s WHERE s.user_id=? AND s.org_id=a.owner_org_id AND s.district_id=a.district_id)";
            args=new Object[]{source,access.userId()};
        }
        return jdbc.query(sql+" ORDER BY m.received_at DESC,m.message_id FETCH FIRST 100 ROWS ONLY",ROW,args);
    }
}
