package com.uav.lowaltitude.integration.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** Completes only the explicitly imported local airspace examples; never manufactures live observations. */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true")
@Order(1000)
public class LocalAirspaceRiskDemoSeeder implements ApplicationRunner {
    public static final String SOURCE="demo-airspace-risk-20260915";
    private static final List<String> TARGET_SOURCES=List.of(SOURCE,"seed-stage3-source");
    public record Camera(String edge, String device, String org, String district) { }
    public static final List<Camera> CAMERAS=List.of(
        new Camera("LOCAL-ASR-EO-1","LOCAL-ASR-EO-001","seed-stage7-org","seed-stage7-district"),
        new Camera("LOCAL-ASR-EO-2","LOCAL-ASR-EO-002","44e5a82a-bdba-3e7c-86d8-e95c8b9a6dbb","9de4e1ae-c628-3dd5-be53-05b8f7583c96"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MqttConfigurationService configuration;
    public LocalAirspaceRiskDemoSeeder(JdbcTemplate jdbc,TransactionTemplate tx,MqttConfigurationService configuration){
        this.jdbc=jdbc;this.tx=tx;this.configuration=configuration;
    }
    @Override public void run(ApplicationArguments args){
        if(jdbc.queryForObject("select count(*) from flight_risk where source_id in (?,?) and source_mode='mock'",Integer.class,SOURCE,"seed-stage3-source")==0)return;
        tx.executeWithoutResult(status -> TARGET_SOURCES.forEach(this::completeTargets));
        if(jdbc.queryForObject("select count(*) from flight_risk where source_id=? and source_mode='mock'",Integer.class,SOURCE)==0)return;
        var admin=jdbc.queryForObject("select user_id,name,role_code,permission_version,must_change_password,scope_mode from app_user where account='admin1'",
            (rs,i)->new AuthUser(rs.getString("user_id"),"admin1",rs.getString("name"),rs.getString("role_code"),rs.getInt("permission_version"),rs.getBoolean("must_change_password"),rs.getString("scope_mode")));
        AuthContext.set(admin);
        try { for(var camera:CAMERAS)register(camera); } finally { AuthContext.clear(); }
    }
    private void completeTargets(String source){
        var rows=jdbc.queryForList("select r.risk_id,COALESCE(r.risk_no,r.source_risk_id) as risk_no,r.owner_org_id,r.district_id,COALESCE(r.occurred_at,r.received_at) as occurred_at,r.observed_altitude_m,r.observed_altitude_datum,f.subtype_code,ST_AsEWKT(f.target_location) as point from flight_risk r join space_risk_fact f on f.risk_id=r.risk_id where r.source_id=? and r.source_mode='mock' and r.target_id is null and f.target_location is not null",source);
        for(var r:rows){
            String id=UUID.nameUUIDFromBytes((source+":"+r.get("risk_id")).getBytes(StandardCharsets.UTF_8)).toString();
            boolean bird="BIRD_FLOCK".equals(r.get("subtype_code"));
            Object at=r.get("occurred_at");
            Object amsl="AMSL".equals(r.get("observed_altitude_datum"))?r.get("observed_altitude_m"):null;
            Object agl="AGL".equals(r.get("observed_altitude_datum"))?r.get("observed_altitude_m"):null;
            jdbc.update("insert into target(target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) select ?,?,?,?, ?,?,'mock',?,?,?,?,0 where not exists(select 1 from target where target_id=?)",
                id,r.get("risk_no")+"-目标",bird?"BIRD":"UNKNOWN",r.get("subtype_code"),at,at,r.get("owner_org_id"),r.get("district_id"),at,at,id);
            jdbc.update("insert into target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version) select ?,CAST(? AS GEOMETRY),?,?,?,?,CAST(? AS JSON),?,?,0 where not exists(select 1 from target_latest_state where target_id=?)",
                id,r.get("point"),amsl,agl,at,at,amsl==null?"[\"altitude_amsl_m\"]":"[]",at,at,id);
            // One-time completion of these mock records. Preserve verification state, history and immutable space facts.
            jdbc.update("update flight_risk set target_id=?,version=version+1 where risk_id=? and source_id=? and source_mode='mock' and target_id is null",id,r.get("risk_id"),source);
        }
    }
    private void register(Camera c){
        if(jdbc.queryForObject("select count(*) from eo_device_binding where external_device_id=?",Integer.class,c.device())>0)return;
        String name="local-airspace-risk-"+c.edge();
        var brokers=jdbc.queryForList("select broker_id from mqtt_broker where name=?",String.class,name);
        String broker;
        if(brokers.isEmpty()){
            var created=configuration.create(new BrokerInput(name,"127.0.0.1",1883,false,null,null,"127.0.0.1/32","replay",c.org(),c.district(),null),UUID.randomUUID().toString());
            configuration.enable(created.brokerId(),created.version(),true,UUID.randomUUID().toString());broker=created.brokerId();
        } else broker=brokers.get(0);
        configuration.register(new Registration(com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope.PROTOCOL,broker,null,c.device(),null,"replay",c.org(),c.district(),c.device(),"空域风险模拟光电 "+c.edge().substring(c.edge().length()-1),"本地模拟",null,null,c.edge()),UUID.randomUUID().toString());
    }
}
