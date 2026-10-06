package com.uav.lowaltitude.modules.identity.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.uav.lowaltitude.modules.identity.domain.IdentityRows.OrgRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ScopeRuleRow;

/** 按“本单位 / 本单位及下级单位”规则维护的授权元组（ZT-14）。 */
@Mapper
public interface UserDataScopeMapper {

    /** 锁住账号行再重建，避免两次重建交错写出重复元组。 */
    @Select("""
            SELECT user_id AS userId, org_id AS orgId, scope_org_rule AS scopeOrgRule
            FROM app_user WHERE user_id = #{userId}
            FOR UPDATE
            """)
    ScopeRuleRow lockUserRule(@Param("userId") String userId);

    @Select("""
            SELECT user_id AS userId, org_id AS orgId, scope_org_rule AS scopeOrgRule
            FROM app_user WHERE scope_org_rule IS NOT NULL
            ORDER BY user_id
            FOR UPDATE
            """)
    List<ScopeRuleRow> lockRuleUsers();

    @Select("SELECT org_id AS orgId, parent_id AS parentId FROM app_org")
    List<OrgRow> listOrgLinks();

    @Delete("DELETE FROM app_user_data_scope WHERE user_id = #{userId}")
    int deleteUserScopes(@Param("userId") String userId);

    /** 给定单位 × 全部区域（含停用区域：业务查询本就只认启用的单位和区域，停用再启用无需重建）。 */
    @Insert("""
            <script>
            INSERT INTO app_user_data_scope (user_id, org_id, district_id)
            SELECT CAST(#{userId} AS VARCHAR(36)), o.org_id, d.district_id
            FROM app_org o CROSS JOIN app_district d
            WHERE o.org_id IN
            <foreach collection='orgIds' item='orgId' open='(' separator=',' close=')'>#{orgId}</foreach>
            </script>
            """)
    int insertOrgScopes(@Param("userId") String userId, @Param("orgIds") List<String> orgIds);

    @Update("UPDATE app_user SET scope_org_rule = NULL WHERE user_id = #{userId}")
    int clearRule(@Param("userId") String userId);
}
