package com.uav.lowaltitude.modules.integrationconfig.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.Input;

@Repository
public class ExternalInterfaceRepository {
    private final JdbcTemplate jdbc;
    public ExternalInterfaceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Row find(String kind) {
        var rows = jdbc.query("SELECT * FROM external_interface_config WHERE kind=?", (r,n) -> new Row(
            r.getString("kind"), r.getString("name"), r.getString("source_code"), r.getString("direction"),
            r.getString("endpoint"), r.getString("credential_ref"), r.getString("allowed_cidrs"), r.getString("area_name"),
            r.getObject("interval_minutes", Integer.class), r.getObject("validity_minutes", Integer.class),
            r.getLong("version"), r.getObject("updated_at", Long.class), r.getString("source_mode")), kind);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public int update(String kind, Input p, long now) {
        return jdbc.update("""
            UPDATE external_interface_config SET name=?,source_code=?,direction=?,endpoint=?,credential_ref=?,
                allowed_cidrs=?,area_name=?,interval_minutes=?,validity_minutes=?,source_mode=?,version=version+1,updated_at=?
            WHERE kind=? AND version=?
            """, p.name(), p.sourceCode(), p.direction(), p.endpoint(), p.credentialRef(), p.allowedCidrs(),
            p.areaName(), p.intervalMinutes(), p.validityMinutes(), p.sourceMode(), now, kind, p.version());
    }
    public record Row(String kind, String name, String sourceCode, String direction, String endpoint,
            String credentialRef, String allowedCidrs, String areaName, Integer intervalMinutes,
            Integer validityMinutes, long version, Long updatedAt, String sourceMode) { }
}
