package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Operations reports read business facts; the historical report_* sample tables are retained untouched. */
@Repository
public class ReportingRepository {
    private final NamedParameterJdbcTemplate named;
    public ReportingRepository(NamedParameterJdbcTemplate named) { this.named = named; }

    public List<TargetFact> targets(LocalDate from, LocalDate to, Scope scope) {
        return named.query("""
            SELECT t.target_id,t.first_seen_at,t.object_type_code,t.source_mode,
                   COALESCE(d.name,'区域未标注') AS region,ls.altitude_amsl_m
            FROM target t LEFT JOIN app_district d ON d.district_id=t.district_id
            LEFT JOIN target_latest_state ls ON ls.target_id=t.target_id
            """ + where(scope, "t", "first_seen_at"), params(from,to,scope), (rs,n) ->
            new TargetFact(rs.getString("target_id"),rs.getObject("first_seen_at",OffsetDateTime.class),
                rs.getString("object_type_code"),rs.getString("source_mode"),rs.getString("region"),
                rs.getBigDecimal("altitude_amsl_m")));
    }

    public List<CaseFact> cases(LocalDate from, LocalDate to, Scope scope) {
        return named.query("""
            SELECT c.case_id,c.filed_at,c.source_mode,COALESCE(d.name,'区域未标注') AS region,
                   COALESCE(c.party_name,'当事人未明确') AS party_name,p.penalty_type,p.fine_amount
            FROM punishment_case c LEFT JOIN app_district d ON d.district_id=c.district_id
            LEFT JOIN penalty_decision_document doc ON doc.case_id=c.case_id AND doc.status='ISSUED'
              AND NOT EXISTS (SELECT 1 FROM penalty_decision_document newer
                WHERE newer.case_id=doc.case_id AND newer.status='ISSUED'
                  AND (newer.issued_at,newer.document_id) > (doc.issued_at,doc.document_id))
            LEFT JOIN penalty_discretion p ON p.discretion_id=doc.discretion_id AND p.status='CONFIRMED'
            """ + where(scope,"c","filed_at"), params(from,to,scope), (rs,n) ->
            new CaseFact(rs.getString("case_id"),rs.getObject("filed_at",OffsetDateTime.class),
                rs.getString("source_mode"),rs.getString("region"),rs.getString("party_name"),
                rs.getString("penalty_type"),rs.getBigDecimal("fine_amount")));
    }

    private static String where(Scope scope,String alias,String time) {
        String sql=" WHERE "+alias+"."+time+">=:from_at AND "+alias+"."+time+"<:until_at"
            +" AND "+alias+".owner_org_id IS NOT NULL AND "+alias+".district_id IS NOT NULL";
        if(!scope.allScope()) sql+="""
             AND EXISTS (SELECT 1 FROM app_user_data_scope s
               JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE
               JOIN app_district dd ON dd.district_id=s.district_id AND dd.enabled=TRUE
               WHERE s.user_id=:user_id AND s.org_id=%s.owner_org_id AND s.district_id=%s.district_id)
            """.formatted(alias,alias);
        return sql;
    }
    private static Map<String,Object> params(LocalDate from,LocalDate to,Scope scope) {
        Map<String,Object> result=new HashMap<>();
        var zone=ZoneId.of("Asia/Shanghai");
        result.put("from_at",from.atStartOfDay(zone).toOffsetDateTime());
        result.put("until_at",to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        result.put("user_id",scope.userId()); return result;
    }
    public record Scope(boolean allScope,String userId) { }
    public record TargetFact(String id,OffsetDateTime firstSeenAt,String type,String sourceMode,String region,BigDecimal altitude) { }
    public record CaseFact(String id,OffsetDateTime filedAt,String sourceMode,String region,String party,String penaltyType,BigDecimal fineCents) { }
}
