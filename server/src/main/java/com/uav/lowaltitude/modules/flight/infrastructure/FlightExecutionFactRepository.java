package com.uav.lowaltitude.modules.flight.infrastructure;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;

@Repository
public class FlightExecutionFactRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public FlightExecutionFactRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Target lockTarget(String target,String track){
        var rows=jdbc.query("SELECT t.owner_org_id,t.district_id,t.source_mode FROM target t WHERE t.target_id=? AND EXISTS(SELECT 1 FROM track k WHERE k.track_id=? AND k.target_id=t.target_id) FOR UPDATE",
            (r,n)->new Target(r.getString(1),r.getString(2),r.getString(3)),target,track);
        return rows.isEmpty()?null:rows.get(0);
    }
    public String sourceMode(String id){var rows=jdbc.queryForList("SELECT source_mode FROM integration_source WHERE source_id=? AND enabled=TRUE FOR UPDATE",String.class,id);return rows.isEmpty()?null:rows.get(0);}
    public Existing existing(Input i){var rows=jdbc.query("SELECT fact_id,request_hash,source_mode FROM flight_execution_fact WHERE source_id=? AND message_id=? AND fact_version=?",
        (r,n)->new Existing(r.getString(1),r.getString(2),r.getString(3)),i.sourceId(),i.messageId(),i.version());return rows.isEmpty()?null:rows.get(0);}
    public Input latestMessage(Input i){var rows=jdbc.queryForList("SELECT payload_json FROM flight_execution_fact WHERE source_id=? AND message_id=? ORDER BY fact_version DESC FETCH FIRST 1 ROWS ONLY",String.class,i.sourceId(),i.messageId());return rows.isEmpty()?null:decode(rows.get(0));}
    public void insert(String id,Input i,Target t,long received,String hash,String payload){
        jdbc.update("INSERT INTO flight_execution_fact(fact_id,source_id,source_mode,message_id,fact_version,target_id,track_id,execution_id,owner_org_id,district_id,valid_from,valid_to,received_at,request_hash,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,i.sourceId(),t.mode(),i.messageId(),i.version(),i.targetId(),i.trackId(),i.executionId(),t.org(),t.district(),i.validFrom(),i.validTo(),received,hash,payload);
        for(String ref:new LinkedHashSet<>(i.evidenceRefs()))jdbc.update("INSERT INTO flight_execution_evidence(fact_id,evidence_ref) VALUES(?,?)",id,ref);
    }
    public Filing filing(String plan,long at){
        var rows=jdbc.query("SELECT p.* FROM flight_plan p WHERE p.plan_id=?",(r,n)->{
            String pilot=r.getString("pilot_contact_id"),binding=r.getString("source_binding_id");
            return new Filing(plan,r.getLong("version"),r.getBigDecimal("takeoff_longitude"),r.getBigDecimal("takeoff_latitude"),
                r.getBigDecimal("landing_longitude"),r.getBigDecimal("landing_latitude"),pilot,reportingOrg(binding,r.getString("source_id")),
                verifiedPilot(pilot,at),reportingOrg(binding,r.getString("source_id"))!=null);
        },plan);return rows.isEmpty()?null:rows.get(0);
    }
    public long revision(String target){return jdbc.queryForObject("SELECT COALESCE(MAX(ingestion_seq),0) FROM flight_execution_fact WHERE target_id=?",Long.class,target);}
    public Comparison comparison(String plan,String target,String track,String mode,long at,long receivedBefore){
        long revision=revision(target);
        Filing filing=filing(plan,at);
        var rows=jdbc.query("""
            SELECT f.fact_id,f.source_mode,f.payload_json,f.received_at FROM flight_execution_fact f
            JOIN integration_source s ON s.source_id=f.source_id AND s.enabled=TRUE AND s.source_mode=f.source_mode
            WHERE f.target_id=? AND f.track_id=? AND f.source_mode=? AND f.ingestion_seq<=?
              AND NOT EXISTS(SELECT 1 FROM flight_execution_fact newer WHERE newer.source_id=f.source_id
                AND newer.message_id=f.message_id AND newer.fact_version>f.fact_version AND newer.ingestion_seq<=?)
              AND f.valid_from<=? AND f.valid_to>?
            """,(r,n)->{
                Input input=decode(r.getString(3));String org=reportingOrg(input.reportingBindingId(),input.sourceId());
                return new Actual(r.getString(1),r.getString(2),input,org,verifiedPilot(input.pilotContactId(),at),org!=null,r.getLong(4));
            },target,track,mode,revision,revision,at,at);
        if(rows.isEmpty())return new Comparison(filing,null,"EXECUTION_FACTS_UNAVAILABLE",revision);
        // Multiple current assertions are ambiguous, including separate sources or executions. Never pick by arrival order.
        if(rows.size()!=1)return new Comparison(filing,null,"EXECUTION_FACTS_CONFLICT",revision);
        return new Comparison(filing,rows.get(0),null,revision);
    }
    public boolean verifiedPilot(String id,long at){return id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM business_contact c JOIN app_org o ON o.org_id=c.org_id AND o.enabled=TRUE WHERE c.contact_id=? AND c.enabled=TRUE AND c.verified_at IS NOT NULL AND c.verified_at<=? AND c.verification_basis IS NOT NULL AND TRIM(c.verification_basis)<>'' AND (c.valid_until IS NULL OR c.valid_until>?)",Long.class,id,at,at)>0;}
    public String reportingOrg(String binding,String source){if(binding==null||source==null)return null;var rows=jdbc.queryForList("SELECT b.org_id FROM plan_source_binding b JOIN app_org o ON o.org_id=b.org_id AND o.enabled=TRUE WHERE b.binding_id=? AND b.source_id=? AND b.enabled=TRUE",String.class,binding,source);return rows.isEmpty()?null:rows.get(0);}
    private Input decode(String value){try{return json.readValue(value,Input.class);}catch(Exception e){throw new IllegalStateException("执行事实无法读取",e);}}
    public record Target(String org,String district,String mode) { }
    public record Existing(String id,String hash,String mode) { }
}
