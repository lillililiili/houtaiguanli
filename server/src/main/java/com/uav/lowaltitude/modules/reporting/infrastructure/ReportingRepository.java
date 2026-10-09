package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.uav.lowaltitude.modules.punishment.infrastructure.EffectiveDecisionSql;
import com.uav.lowaltitude.platform.query.StatisticsScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Operations reports read business facts; the historical report_* sample tables are retained untouched.
 * 计入哪些来源见 {@link StatisticsScope}：真实设备与设备模拟器的数据都算，系统自带的演示样例不算（2026-10-07）。
 */
@Repository
public class ReportingRepository {
    private final NamedParameterJdbcTemplate named;
    private final StatisticsScope statistics;
    public ReportingRepository(NamedParameterJdbcTemplate named, StatisticsScope statistics) { this.named = named; this.statistics = statistics; }

    /**
     * 计入统计的研判：和合法性研判页一样，有结论就算，依据充分与否、规则参数是否已确认都不再筛（2026-10-08 D-4
     * “统计和页面用同一口径”）。原先只算依据充分的，研判页非法 10 条时大屏和运行统计只有 2 条。来源仍按统计口径。
     */
    public java.util.Set<String> formalEvaluationIds(List<String> targetIds) {
        if(targetIds.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(named.queryForList("SELECT e.evaluation_id FROM rule_evaluation e"
            + " WHERE e.target_id IN (:ids) AND e.source_mode IN " + statistics.sqlIn(),Map.of("ids",targetIds),String.class));
    }
    public java.util.Set<String> formalRiskIds(List<String> targetIds) {
        if(targetIds.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(named.queryForList("SELECT risk_id FROM flight_risk WHERE target_id IN (:ids) AND source_mode IN " + statistics.sqlIn(),Map.of("ids",targetIds),String.class));
    }
    public record OrganizationOption(String orgId, String name) { }
    public List<OrganizationOption> organizations(Scope scope) {
        String filter = scope.allScope() ? "" : " AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_district dd ON dd.district_id=ds.district_id AND dd.enabled=TRUE WHERE ds.user_id=:user_id AND ds.org_id=o.org_id)";
        return named.query("SELECT o.org_id,o.name FROM app_org o WHERE o.enabled=TRUE" + filter + " ORDER BY o.name,o.org_id",
                Map.of("user_id", scope.userId()), (rs,n) -> new OrganizationOption(rs.getString("org_id"),rs.getString("name")));
    }

    /**
     * 统计期内新增的目标。被合并的目标是某个存活目标的别名（决策 16-6），同一架不能数两遍：
     * 目标列表、态势页都不列它，这里同样不计（ZT-17 复测 2：运行统计多出 21 个，正是被合并的目标）。
     * 它自己名下的研判、风险也就不另计，存活目标按它自己的最新研判、风险计，与目标详情显示的一致。
     */
    public List<TargetFact> targets(LocalDate from, LocalDate to, Scope scope) {
        return named.query("""
            SELECT t.target_id,t.first_seen_at,t.object_type_code,t.source_mode,
                   COALESCE(d.name,'区域未标注') AS region,ls.altitude_amsl_m
            FROM target t LEFT JOIN app_district d ON d.district_id=t.district_id
            LEFT JOIN target_latest_state ls ON ls.target_id=t.target_id
            """ + where(scope, "t", "first_seen_at")
            + " AND NOT EXISTS (SELECT 1 FROM target_track_status merged_status"
            + " WHERE merged_status.target_id=t.target_id AND merged_status.status='MERGE')", params(from,to,scope), (rs,n) ->
            new TargetFact(rs.getString("target_id"),rs.getObject("first_seen_at",OffsetDateTime.class),
                rs.getString("object_type_code"),rs.getString("source_mode"),rs.getString("region"),
                rs.getBigDecimal("altitude_amsl_m")));
    }

    public List<CaseFact> cases(LocalDate from, LocalDate to, Scope scope) {
        return named.query("""
            SELECT c.case_id,c.filed_at,c.source_mode,COALESCE(d.name,'区域未标注') AS region,
                   COALESCE(c.party_name,'当事人未明确') AS party_name,p.penalty_type,p.fine_amount
            FROM punishment_case c LEFT JOIN app_district d ON d.district_id=c.district_id
            """ + EffectiveDecisionSql.JOINS + where(scope,"c","filed_at"), params(from,to,scope), (rs,n) ->
            new CaseFact(rs.getString("case_id"),rs.getObject("filed_at",OffsetDateTime.class),
                rs.getString("source_mode"),rs.getString("region"),rs.getString("party_name"),
                rs.getString("penalty_type"),rs.getBigDecimal("fine_amount")));
    }

    private String where(Scope scope,String alias,String time) {
        String sql=" WHERE "+alias+".source_mode IN "+statistics.sqlIn()+" AND "+alias+"."+time+">=:from_at AND "+alias+"."+time+"<:until_at"
            +" AND "+alias+".owner_org_id IS NOT NULL AND "+alias+".district_id IS NOT NULL";
        if(!scope.allScope()) sql+="""
             AND EXISTS (SELECT 1 FROM app_user_data_scope s
               JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE
               JOIN app_district dd ON dd.district_id=s.district_id AND dd.enabled=TRUE
               WHERE s.user_id=:user_id AND s.org_id=%s.owner_org_id AND s.district_id=%s.district_id)
            """.formatted(alias,alias);
        if (scope.ownerOrgId() != null) sql += " AND " + alias + ".owner_org_id=:owner_org_id";
        return sql;
    }
    private static Map<String,Object> params(LocalDate from,LocalDate to,Scope scope) {
        Map<String,Object> result=new HashMap<>();
        var zone=ZoneId.of("Asia/Shanghai");
        result.put("from_at",from.atStartOfDay(zone).toOffsetDateTime());
        result.put("until_at",to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        result.put("user_id",scope.userId()); result.put("owner_org_id",scope.ownerOrgId()); return result;
    }
    public record Scope(boolean allScope,String userId,String ownerOrgId) {
        public Scope(boolean allScope,String userId) { this(allScope,userId,null); }
    }
    public record TargetFact(String id,OffsetDateTime firstSeenAt,String type,String sourceMode,String region,BigDecimal altitude) { }
    public record CaseFact(String id,OffsetDateTime filedAt,String sourceMode,String region,String party,String penaltyType,BigDecimal fineCents) { }
}
