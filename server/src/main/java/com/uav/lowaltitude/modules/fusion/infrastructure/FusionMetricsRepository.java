package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/**
 * 融合效果日指标只读仓库：直接读迁移 054 的视图 fusion_effect_daily。
 * 视图已按 (source_mode, owner_org_id, district_id, day) 聚合并给出分子/分母，比率为 NULL 表示分母为 0；
 * 这里只做范围谓词与日历日窗口过滤，不再二次计算，避免 API 与视图口径漂移。
 */
@Repository
public class FusionMetricsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FusionMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** 闭区间 [fromDay, toDay]；domainKey 为空时返回范围内全部分区。视图列 day 在 H2 是关键字，SQL 里必须加引号。 */
    public List<DailyRow> daily(String domainKey, LocalDate fromDay, LocalDate toDay, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("from_day", fromDay);
        params.put("to_day", toDay);
        StringBuilder sql = new StringBuilder("SELECT v.fusion_domain_key,v.source_mode,v.owner_org_id,v.district_id,v.\"day\" AS metric_day,v.tracked_targets,"
                + "v.id_switch_count,v.short_lost_frames,v.total_frames,v.interrupt_rate,v.duplicate_targets,v.truth_targets,v.duplicate_target_rate,"
                + "v.correct_associations,v.total_associations,v.association_accuracy FROM fusion_effect_daily v"
                + " WHERE v.\"day\" >= :from_day AND v.\"day\" <= :to_day");
        // ALL 仍要求归属目录存在且启用；分区缺归属（org/district 为 NULL）的行对任何范围都不可见。
        sql.append(" AND v.owner_org_id IS NOT NULL AND v.district_id IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM app_org vo JOIN app_district vd ON vd.district_id=v.district_id"
                + " WHERE vo.org_id=v.owner_org_id AND vo.enabled=TRUE AND vd.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org o ON o.org_id=gs.org_id AND o.enabled=TRUE"
                    + " JOIN app_district d ON d.district_id=gs.district_id AND d.enabled=TRUE WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=v.owner_org_id AND gs.district_id=v.district_id)");
            params.put("scope_user", access.userId());
        }
        if (domainKey != null) {
            sql.append(" AND v.fusion_domain_key=:domain");
            params.put("domain", domainKey);
        }
        sql.append(" ORDER BY v.\"day\" ASC,v.fusion_domain_key ASC");
        return jdbc.query(sql.toString(), params, FusionMetricsRepository::row);
    }

    private static DailyRow row(ResultSet rs, int ignored) throws SQLException {
        return new DailyRow(rs.getString("fusion_domain_key"), rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getObject("metric_day", LocalDate.class), rs.getLong("tracked_targets"), rs.getLong("id_switch_count"), rs.getLong("short_lost_frames"),
                rs.getLong("total_frames"), rs.getBigDecimal("interrupt_rate"), rs.getLong("duplicate_targets"), rs.getLong("truth_targets"),
                rs.getBigDecimal("duplicate_target_rate"), rs.getLong("correct_associations"), rs.getLong("total_associations"),
                rs.getBigDecimal("association_accuracy"));
    }

    public record DailyRow(String fusionDomainKey, String sourceMode, String ownerOrgId, String districtId, LocalDate day, long trackedTargets,
            long idSwitchCount, long shortLostFrames, long totalFrames, BigDecimal interruptRate, long duplicateTargets, long truthTargets,
            BigDecimal duplicateTargetRate, long correctAssociations, long totalAssociations, BigDecimal associationAccuracy) { }
}
