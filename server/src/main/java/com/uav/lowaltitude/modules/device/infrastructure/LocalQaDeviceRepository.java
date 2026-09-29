package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only invoked inside the guarded QA provisioning transaction. No equipment observations are manufactured. */
@Repository
public class LocalQaDeviceRepository {
    private final JdbcTemplate jdbc;
    public LocalQaDeviceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void markNewSourceSimulated(String id) {
        if(jdbc.update("UPDATE ops_integration_source SET simulated=TRUE WHERE source_id=? AND enabled=FALSE AND version=0",id)!=1)
            throw new IllegalStateException("new QA source changed before registration");
    }
    public void bindScope(String id,String org,String district,long now) {
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",
            id,org,district,new Timestamp(now),new Timestamp(now));
    }
}
