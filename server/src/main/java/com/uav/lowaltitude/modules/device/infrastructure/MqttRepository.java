package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;

@Repository
public class MqttRepository {
    private final JdbcTemplate jdbc;
    public MqttRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String BROKER_SELECT = """
            SELECT b.*, l.connection_state,l.last_error FROM mqtt_broker b
            JOIN mqtt_session_lease l ON l.broker_id=b.broker_id
            """;
    private static final String BINDING_SELECT = """
            SELECT m.*,d.enabled,s.owner_org_id,s.district_id FROM mqtt_device_binding m
            JOIN ops_device d ON d.device_id=m.ops_device_id
            JOIN device_business_scope s ON s.ops_device_id=d.device_id
            """;

    public List<Broker> brokers() { return jdbc.query(BROKER_SELECT + " ORDER BY b.name,b.broker_id", this::broker); }
    public Broker broker(String id, boolean lock) {
        if (lock) jdbc.queryForList("SELECT broker_id FROM mqtt_broker WHERE broker_id=? FOR UPDATE", id);
        return jdbc.query(BROKER_SELECT + " WHERE b.broker_id=?", this::broker, id).stream().findFirst().orElse(null);
    }
    private Broker broker(ResultSet r, int row) throws SQLException {
        return new Broker(r.getString("broker_id"),r.getString("name"),r.getString("host"),r.getInt("port"),
                r.getBoolean("tls"),r.getString("client_id"),r.getString("username"),r.getString("credential_ref"),
                r.getString("allowed_cidrs"),r.getString("source_mode"),r.getString("owner_org_id"),r.getString("district_id"),
                r.getBoolean("enabled"),r.getLong("version"),r.getString("connection_state"),r.getString("last_error"));
    }
    public void insertBroker(String id, BrokerInput p, long now) {
        jdbc.update("""
                INSERT INTO mqtt_broker(broker_id,name,host,port,tls,client_id,username,credential_ref,allowed_cidrs,
                    source_mode,owner_org_id,district_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id,p.name(),p.host(),p.port(),p.tls(),"uav-"+id,p.username(),p.credentialRef(),p.allowedCidrs(),
                p.sourceMode(),p.ownerOrgId(),p.districtId(),now,now);
        jdbc.update("INSERT INTO mqtt_session_lease(broker_id,updated_at) VALUES (?,?)",id,now);
    }
    public int updateBroker(String id, BrokerInput p, long now) {
        return jdbc.update("""
                UPDATE mqtt_broker SET name=?,host=?,port=?,tls=?,username=?,credential_ref=?,allowed_cidrs=?,
                    version=version+1,updated_at=? WHERE broker_id=? AND version=? AND enabled=FALSE
                """,p.name(),p.host(),p.port(),p.tls(),p.username(),p.credentialRef(),p.allowedCidrs(),now,id,p.version());
    }
    public int enableBroker(String id, long version, boolean enabled, long now) {
        return jdbc.update("UPDATE mqtt_broker SET enabled=?,version=version+1,updated_at=? WHERE broker_id=? AND version=?",
                enabled,now,id,version);
    }
    public List<Binding> bindings(String brokerId) {
        return jdbc.query(BINDING_SELECT+" WHERE m.broker_id=? ORDER BY m.ops_device_id",this::binding,brokerId);
    }
    public Binding binding(String id, boolean lock) {
        if (lock) jdbc.queryForList("SELECT ops_device_id FROM mqtt_device_binding WHERE ops_device_id=? FOR UPDATE", id);
        return jdbc.query(BINDING_SELECT+" WHERE m.ops_device_id=?",this::binding,id).stream().findFirst().orElse(null);
    }
    private Binding binding(ResultSet r, int row) throws SQLException {
        return new Binding(r.getString("ops_device_id"),r.getString("device_id"),r.getString("ops_source_id"),
                r.getString("source_id"),r.getString("broker_id"),r.getString("provider_code"),r.getString("device_type_abbr"),
                r.getString("external_device_id"),r.getString("source_mode"),r.getBoolean("enabled"),
                r.getObject("last_static_pt_time",Long.class),r.getObject("last_pt_time",Long.class),
                r.getObject("last_msg_cnt",Long.class),r.getString("owner_org_id"),r.getString("district_id"));
    }
    public boolean scopeExists(String org, String district) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_org o CROSS JOIN app_district d WHERE o.org_id=? AND d.district_id=?",
                Integer.class,org,district)==1;
    }
    public List<Map<String,Object>> scopeOptions() {
        return jdbc.queryForList("""
                SELECT o.org_id,o.name AS org_name,d.district_id,d.name AS district_name
                FROM app_org o CROSS JOIN app_district d ORDER BY o.name,d.name
                """);
    }
    public String register(Registration p, long now) {
        String opsId=uuid(), deviceId=uuid(), opsSource=uuid(), source=uuid();
        String type= switch(p.deviceTypeAbbr()) {
            case "radar" -> "RADAR";
            case "5ga" -> "FIVE_G_A";
            case "oe" -> "EO";
            case "aoa" -> "AOA";
            case "tdoa" -> "TDOA";
            case "dcd" -> "DCD";
            case "rid" -> "RID";
            case "dec" -> "DEC";
            case "ifr" -> "IFR";
            case "bsc" -> "BSC";
            default -> throw new IllegalArgumentException("UNSUPPORTED_TYPE");
        };
        String catalogType= switch(p.deviceTypeAbbr()) {
            case "dec", "ifr", "bsc" -> null;
            default -> type;
        };
        String typeName= switch(p.deviceTypeAbbr()) {
            case "radar" -> "雷达";
            case "5ga" -> "5G-A";
            case "oe" -> "光电";
            case "aoa" -> "AOA";
            case "tdoa" -> "TDOA";
            case "dcd" -> "协议破解";
            case "rid" -> "RemoteID";
            case "dec" -> "诱骗";
            case "ifr" -> "干扰";
            case "bsc" -> "驱鸟炮";
            default -> throw new IllegalArgumentException("UNSUPPORTED_TYPE");
        };
        boolean simulated=p.sourceMode().equals("replay");
        Timestamp time=new Timestamp(now);
        jdbc.update("""
                INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,simulated,created_at,updated_at) VALUES (?,?,?,?,'8.6',?,TRUE,?,?,?)
                """,opsSource,"mqtt-"+opsSource,p.name(),LingyunEnvelope.PROTOCOL,p.sourceMode(),simulated,now,now);
        jdbc.update("""
                INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,source_type,created_at,updated_at) VALUES (?,?,?,?,'8.6',?,TRUE,?,?,?)
                """,source,"mqtt-"+source,p.name(),LingyunEnvelope.PROTOCOL,p.sourceMode(),catalogType,time,time);
        jdbc.update("""
                INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,
                    channel,model,vendor,source_mode,simulated,created_at,updated_at,owner_name,region_name)
                SELECT ?,?,?,?,?,?,?,'凌云 MQTT',?,?,?,?,?,?,o.name,d.name FROM app_org o CROSS JOIN app_district d
                WHERE o.org_id=? AND d.district_id=?
                """,opsId,opsSource,p.externalDeviceId(),p.deviceNo(),p.name(),p.deviceTypeAbbr(),typeName,
                p.model(),p.vendor(),p.sourceMode(),simulated,now,now,p.ownerOrgId(),p.districtId());
        jdbc.update("""
                INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,model,vendor,
                    source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,deviceId,source,p.externalDeviceId(),p.deviceNo(),p.name(),type,p.model(),p.vendor(),p.sourceMode(),
                p.ownerOrgId(),p.districtId(),time,time);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,?,?)",
                opsId,p.ownerOrgId(),p.districtId(),time,time);
        jdbc.update("""
                INSERT INTO mqtt_device_binding(ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,
                    device_type_abbr,external_device_id,source_mode) VALUES (?,?,?,?,?,?,?,?,?)
                """,opsId,deviceId,opsSource,source,p.brokerId(),p.providerCode(),p.deviceTypeAbbr(),p.externalDeviceId(),p.sourceMode());
        jdbc.update("INSERT INTO ops_device_state(device_id,received_at,simulated,unknown_reason) VALUES (?,?,?,'尚未收到有效工参')",
                opsId,now,simulated);
        return opsId;
    }
    public int updateDevice(Binding b, Registration p, long now) {
        int changed=jdbc.update("UPDATE ops_device SET name=?,vendor=?,model=?,version=version+1,updated_at=? WHERE device_id=? AND version=?",
                p.name(),p.vendor(),p.model(),now,b.opsDeviceId(),p.version());
        if(changed==1) jdbc.update("UPDATE device SET name=?,vendor=?,model=?,version=version+1,updated_at=? WHERE device_id=?",
                p.name(),p.vendor(),p.model(),new Timestamp(now),b.deviceId());
        return changed;
    }
    public int enableDevice(Binding b,long version,boolean enabled,long now) {
        int changed=jdbc.update("UPDATE ops_device SET enabled=?,version=version+1,updated_at=? WHERE device_id=? AND version=?",
                enabled,now,b.opsDeviceId(),version);
        if(changed==1) {
            jdbc.update("UPDATE device SET enabled=?,version=version+1,updated_at=? WHERE device_id=?",enabled,new Timestamp(now),b.deviceId());
            jdbc.update("UPDATE ops_integration_source SET enabled=?,version=version+1,updated_at=? WHERE source_id=?",enabled,now,b.opsSourceId());
            jdbc.update("UPDATE integration_source SET enabled=?,version=version+1,updated_at=? WHERE source_id=?",enabled,new Timestamp(now),b.sourceId());
            jdbc.update("UPDATE mqtt_device_binding SET subscribed=FALSE WHERE ops_device_id=?",b.opsDeviceId());
            jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE',unknown_reason='等待新的有效工参',last_heartbeat_at=NULL WHERE device_id=?",b.opsDeviceId());
        }
        return changed;
    }
    public boolean claim(String id,String owner,long now) {
        return jdbc.update("""
                UPDATE mqtt_session_lease SET owner_id=?,lease_until=?,updated_at=? WHERE broker_id=?
                AND (lease_until<? OR owner_id=?) AND EXISTS(SELECT 1 FROM mqtt_broker b WHERE b.broker_id=? AND b.enabled=TRUE)
                """,owner,now+30_000,now,id,now,owner,id)==1;
    }
    public boolean fence(String id,String owner,long now) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM mqtt_session_lease WHERE broker_id=? FOR UPDATE",id);
        return !rows.isEmpty() && owner.equals(rows.get(0).get("owner_id")) && ((Number)rows.get(0).get("lease_until")).longValue()>now;
    }
    public void connection(String id,String owner,String state,String error,long now) {
        jdbc.update("UPDATE mqtt_session_lease SET connection_state=?,last_error=?,updated_at=? WHERE broker_id=? AND owner_id=?",
                state,error,now,id,owner);
        if(!state.equals("CONNECTED")) jdbc.update("UPDATE mqtt_device_binding SET subscribed=FALSE WHERE broker_id=?",id);
    }
    public void release(String id,String owner,long now) {
        jdbc.update("UPDATE mqtt_session_lease SET lease_until=0,connection_state='DISCONNECTED',updated_at=? WHERE broker_id=? AND owner_id=?",now,id,owner);
    }
    public void subscribed(String id,boolean subscribed) { jdbc.update("UPDATE mqtt_device_binding SET subscribed=? WHERE ops_device_id=?",subscribed,id); }
    public void resetReceipts(String broker) { jdbc.update("DELETE FROM mqtt_delivery_receipt WHERE broker_id=?",broker); }
    public boolean transportDuplicate(String broker,int packet,String topic,String hash,boolean dup) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT topic,payload_hash FROM mqtt_delivery_receipt WHERE broker_id=? AND packet_id=?",broker,packet);
        boolean duplicate=dup && !rows.isEmpty() && topic.equals(rows.get(0).get("topic")) && hash.equals(rows.get(0).get("payload_hash"));
        jdbc.update("DELETE FROM mqtt_delivery_receipt WHERE broker_id=? AND packet_id=?",broker,packet);
        jdbc.update("INSERT INTO mqtt_delivery_receipt(broker_id,packet_id,topic,payload_hash) VALUES (?,?,?,?)",broker,packet,topic,hash);
        return duplicate;
    }
    public String existingHash(String source,String key) {
        return jdbc.queryForList("SELECT payload_hash FROM inbox_message WHERE source=? AND source_msg_id=?",String.class,source,key).stream().findFirst().orElse(null);
    }
    public boolean inbox(Binding b,LingyunEnvelope m,long received) {
        String key=m.ptTime()+":"+m.msgCnt();
        return jdbc.update("""
                INSERT INTO inbox_message(inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
                SELECT ?,?,?,?,?,CAST(? AS JSON),?,'RECEIVED'
                WHERE NOT EXISTS (SELECT 1 FROM inbox_message WHERE source=? AND source_msg_id=?)
                """,uuid(),b.source(),key,b.sourceId(),m.hash(),m.json(),received,b.source(),key)==1;
    }
    public void sense(Binding b,LingyunEnvelope m,long received) {
        boolean newer=b.lastPtTime()==null || m.ptTime()>b.lastPtTime() || (m.ptTime().equals(b.lastPtTime()) && m.msgCnt()>b.lastMsgCnt());
        if(newer) {
            boolean gap=b.lastMsgCnt()!=null && m.msgCnt()!=((b.lastMsgCnt()+1)%2_147_483_648L);
            jdbc.update("""
                    UPDATE mqtt_device_binding SET last_sense_at=?,last_pt_time=?,last_msg_cnt=?,suspected_gap_count=suspected_gap_count+?
                    WHERE ops_device_id=?
                    """,received,m.ptTime(),m.msgCnt(),gap?1:0,b.opsDeviceId());
        }
    }
    public void heartbeat(Binding b,LingyunEnvelope m,long received) {
        jdbc.update("UPDATE mqtt_device_binding SET last_static_at=?,last_static_pt_time=COALESCE(?,last_static_pt_time) WHERE ops_device_id=?",
                received,m.ptTime(),b.opsDeviceId());
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='ONLINE',work_state_code=?,observed_at=?,received_at=?,
                    last_heartbeat_at=?,unknown_reason=NULL,metrics_json=?,version=version+1 WHERE device_id=?
                """,String.valueOf(m.workState()),m.ptTime(),received,received,m.json(),b.opsDeviceId());
        if (m.longitude() != null && m.latitude() != null) {
            jdbc.update("""
                    UPDATE ops_device SET longitude=?, latitude=?, coordinate_system='WGS-84',
                        altitude_m=COALESCE(?, altitude_m), version=version+1, updated_at=?
                    WHERE device_id=? AND longitude IS NULL AND latitude IS NULL
                    """, m.longitude(), m.latitude(), m.altitude(), received, b.opsDeviceId());
        }
    }
    public void diagnostic(String broker,String device,String topic,String hash,long received,String outcome,String reason) {
        jdbc.update("""
                INSERT INTO mqtt_receive_diagnostic(diagnostic_id,broker_id,ops_device_id,topic,payload_hash,received_at,outcome,reason)
                VALUES (?,?,?,?,?,?,?,?)
                """,uuid(),broker,device,topic.substring(0,Math.min(topic.length(),1024)),hash,received,outcome,reason);
        if(device!=null && (outcome.equals("DUPLICATE") || outcome.equals("CONFLICT"))) {
            String column=outcome.equals("DUPLICATE")?"duplicate_count":"conflict_count";
            jdbc.update("UPDATE mqtt_device_binding SET "+column+"="+column+"+1 WHERE ops_device_id=?",device);
        }
    }
    public Map<String,Object> status(String device) {
        Map<String,Object> result=jdbc.queryForMap("""
                SELECT m.*,b.enabled AS broker_enabled,l.connection_state,l.last_error,l.lease_until
                FROM mqtt_device_binding m JOIN mqtt_broker b ON b.broker_id=m.broker_id
                JOIN mqtt_session_lease l ON l.broker_id=m.broker_id WHERE m.ops_device_id=?
                """,device);
        result.put("recent_diagnostics",jdbc.queryForList("""
                SELECT outcome,reason,received_at,payload_hash FROM mqtt_receive_diagnostic WHERE ops_device_id=?
                ORDER BY received_at DESC,diagnostic_id DESC FETCH FIRST 20 ROWS ONLY
                """,device));
        return result;
    }
    public void expire(long now) {
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='OFFLINE',unknown_reason='30 秒未收到有效工参',version=version+1
                WHERE connectivity='ONLINE' AND last_heartbeat_at<=?
                AND device_id IN (SELECT ops_device_id FROM mqtt_device_binding)
                """,now-30_000);
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
}
