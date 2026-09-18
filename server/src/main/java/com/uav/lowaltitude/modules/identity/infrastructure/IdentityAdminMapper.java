package com.uav.lowaltitude.modules.identity.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.AccessChangeRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ActionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.DistrictRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.OrgRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PendingUserRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PermissionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ScopeGrantRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.UserAdminRow;

@Mapper
public interface IdentityAdminMapper {

    @Select("""
            SELECT r.role_code AS roleCode, r.name, r.description, r.builtin, r.enabled, r.version,
                   COUNT(u.user_id) AS userCount
            FROM app_role r LEFT JOIN app_user u ON u.role_code = r.role_code
            GROUP BY r.role_code, r.name, r.description, r.builtin, r.enabled, r.version
            ORDER BY r.builtin DESC, r.created_at, r.role_code
            """)
    List<RoleRow> listRoles();

    @Select("""
            SELECT r.role_code AS roleCode, r.name, r.description, r.builtin, r.enabled, r.version,
                   COUNT(u.user_id) AS userCount
            FROM app_role r LEFT JOIN app_user u ON u.role_code = r.role_code
            WHERE r.role_code = #{roleCode}
            GROUP BY r.role_code, r.name, r.description, r.builtin, r.enabled, r.version
            """)
    RoleRow findRole(@Param("roleCode") String roleCode);

    @Select("""
            SELECT p.permission_code AS permissionCode, p.module_name AS moduleName,
                   p.route_key AS routeKey, p.sort_order AS sortOrder,
                   COALESCE(rp.permission_level, 'NONE') AS permissionLevel,
                   COALESCE(rp.menu_enabled, FALSE) AS menuEnabled
            FROM app_permission p
            LEFT JOIN app_role_permission rp
              ON rp.permission_code = p.permission_code AND rp.role_code = #{roleCode}
            WHERE p.permission_kind = 'MODULE'
            ORDER BY p.sort_order
            """)
    List<PermissionRow> listPermissionsForRole(@Param("roleCode") String roleCode);

    /** 该角色已授予的动作码原文；直接反制是例外，只有显式 OP 才算授予。 */
    @Select("""
            SELECT p.permission_code
            FROM app_permission p
            JOIN app_role_permission rp
              ON rp.permission_code = p.permission_code AND rp.role_code = #{roleCode}
            WHERE p.permission_kind = 'ACTION'
              AND ((p.permission_code = 'disposal:direct' AND rp.permission_level = 'OP')
                OR (p.permission_code <> 'disposal:direct' AND rp.permission_level <> 'NONE'))
            ORDER BY p.permission_code
            """)
    List<String> listActionCodesForRole(@Param("roleCode") String roleCode);

    /** 超级管理员隐式继承的动作目录；直接反制必须显式授权，因此排除。 */
    @Select("SELECT permission_code FROM app_permission WHERE permission_kind = 'ACTION'"
            + " AND permission_code <> 'disposal:direct' ORDER BY permission_code")
    List<String> listActionCodeCatalog();

    @Select("""
            SELECT permission_code AS permissionCode, module_name AS moduleName,
                   route_key AS routeKey, sort_order AS sortOrder,
                   'NONE' AS permissionLevel, FALSE AS menuEnabled
            FROM app_permission
            WHERE permission_kind = 'MODULE'
            ORDER BY sort_order
            """)
    List<PermissionRow> listPermissionCatalog();

    @Select("""
            SELECT u.user_id AS userId, u.account, u.name, u.phone, u.org_id AS orgId,
                   o.name AS orgName, u.role_code AS roleCode, r.name AS roleName,
                   u.status, u.scope_mode AS scopeMode, u.must_change_password AS mustChangePassword,
                   CASE WHEN EXISTS (SELECT 1 FROM app_session s WHERE s.user_id = u.user_id AND s.expire_at > #{now})
                        THEN TRUE ELSE FALSE END AS online,
                   u.last_login_at AS lastLoginAt, u.last_login_ip AS lastLoginIp,
                   u.created_at AS createdAt, u.version
            FROM app_user u
            JOIN app_role r ON r.role_code = u.role_code
            LEFT JOIN app_org o ON o.org_id = u.org_id
            WHERE u.status <> 'DELETED'
            ORDER BY u.created_at, u.account
            """)
    List<UserAdminRow> listUsers(@Param("now") long now);

    @Select("""
            SELECT u.user_id AS userId, u.account, u.name, u.phone, u.org_id AS orgId,
                   o.name AS orgName, u.role_code AS roleCode, r.name AS roleName,
                   u.status, u.scope_mode AS scopeMode, u.must_change_password AS mustChangePassword,
                   CASE WHEN EXISTS (SELECT 1 FROM app_session s WHERE s.user_id = u.user_id AND s.expire_at > #{now})
                        THEN TRUE ELSE FALSE END AS online,
                   u.last_login_at AS lastLoginAt, u.last_login_ip AS lastLoginIp,
                   u.created_at AS createdAt, u.version
            FROM app_user u
            JOIN app_role r ON r.role_code = u.role_code
            LEFT JOIN app_org o ON o.org_id = u.org_id
            WHERE u.user_id = #{userId}
            """)
    UserAdminRow findAdminUser(@Param("userId") String userId, @Param("now") long now);

    @Select("""
            SELECT s.org_id AS orgId, o.name AS orgName, s.district_id AS districtId, d.name AS districtName
            FROM app_user_data_scope s
            JOIN app_org o ON o.org_id = s.org_id
            JOIN app_district d ON d.district_id = s.district_id
            WHERE s.user_id = #{userId}
            ORDER BY o.name, d.name
            """)
    List<ScopeGrantRow> listUserScopes(@Param("userId") String userId);

    @Select("""
            SELECT COUNT(*) FROM app_user_data_scope
            WHERE user_id = #{userId} AND org_id = #{orgId} AND district_id = #{districtId}
            """)
    int countUserScopeTuple(@Param("userId") String userId, @Param("orgId") String orgId,
            @Param("districtId") String districtId);

    @Select("""
            SELECT org_id AS orgId, parent_id AS parentId, org_code AS orgCode, name, enabled, version
            FROM app_org ORDER BY name, org_code
            """)
    List<OrgRow> listOrganizations();

    @Select("""
            SELECT org_id AS orgId, parent_id AS parentId, org_code AS orgCode, name, enabled, version
            FROM app_org WHERE org_id = #{orgId}
            """)
    OrgRow findOrganization(@Param("orgId") String orgId);

    @Select("""
            SELECT district_id AS districtId, district_code AS districtCode, name, enabled, version
            FROM app_district ORDER BY name, district_code
            """)
    List<DistrictRow> listDistricts();

    @Select("""
            SELECT district_id AS districtId, district_code AS districtCode, name, enabled, version
            FROM app_district WHERE district_id = #{districtId}
            """)
    DistrictRow findDistrict(@Param("districtId") String districtId);

    @Insert("""
            INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version)
            VALUES (#{roleCode}, #{name}, #{description}, FALSE, TRUE, #{at}, #{at}, 0)
            """)
    int insertRole(@Param("roleCode") String roleCode, @Param("name") String name,
            @Param("description") String description, @Param("at") long at);

    @Insert("""
            INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled)
            SELECT #{roleCode}, permission_code, 'NONE', FALSE FROM app_permission
            """)
    int insertEmptyRolePermissions(@Param("roleCode") String roleCode);

    @Update("""
            UPDATE app_role SET description = #{description}, updated_at = #{at}, version = version + 1
            WHERE role_code = #{roleCode} AND version = #{expectedVersion}
            """)
    int updateRoleDescription(@Param("roleCode") String roleCode, @Param("description") String description,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE app_role_permission SET permission_level = #{level}, menu_enabled = #{menuEnabled}
            WHERE role_code = #{roleCode} AND permission_code = #{permissionCode}
            """)
    int updateRolePermission(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode,
            @Param("level") String level, @Param("menuEnabled") boolean menuEnabled);

    @Update("""
            UPDATE app_role SET updated_at = #{at}, version = version + 1
            WHERE role_code = #{roleCode} AND version = #{expectedVersion}
            """)
    int bumpRoleVersion(@Param("roleCode") String roleCode, @Param("expectedVersion") int expectedVersion,
            @Param("at") long at);

    @Delete("DELETE FROM app_role_permission WHERE role_code = #{roleCode}")
    int deleteRolePermissions(@Param("roleCode") String roleCode);

    @Delete("DELETE FROM app_role WHERE role_code = #{roleCode} AND builtin = FALSE AND version = #{expectedVersion}")
    int deleteCustomRole(@Param("roleCode") String roleCode, @Param("expectedVersion") int expectedVersion);

    @Insert("""
            INSERT INTO app_org (org_id, parent_id, org_code, name, enabled, created_at, updated_at, version)
            VALUES (#{orgId}, #{parentId}, #{orgCode}, #{name}, TRUE, #{at}, #{at}, 0)
            """)
    int insertOrganization(@Param("orgId") String orgId, @Param("parentId") String parentId,
            @Param("orgCode") String orgCode, @Param("name") String name, @Param("at") long at);

    @Update("""
            UPDATE app_org SET parent_id = #{parentId}, name = #{name}, updated_at = #{at}, version = version + 1
            WHERE org_id = #{orgId} AND version = #{expectedVersion}
            """)
    int updateOrganization(@Param("orgId") String orgId, @Param("parentId") String parentId,
            @Param("name") String name, @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Insert("""
            INSERT INTO app_district (district_id, district_code, name, enabled, created_at, updated_at, version)
            VALUES (#{districtId}, #{districtCode}, #{name}, TRUE, #{at}, #{at}, 0)
            """)
    int insertDistrict(@Param("districtId") String districtId, @Param("districtCode") String districtCode,
            @Param("name") String name, @Param("at") long at);

    @Update("""
            UPDATE app_district SET name = #{name}, updated_at = #{at}, version = version + 1
            WHERE district_id = #{districtId} AND version = #{expectedVersion}
            """)
    int updateDistrict(@Param("districtId") String districtId, @Param("name") String name,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE app_district SET enabled = #{enabled}, updated_at = #{at}, version = version + 1
            WHERE district_id = #{districtId} AND version = #{expectedVersion}
            """)
    int setDistrictEnabled(@Param("districtId") String districtId, @Param("enabled") boolean enabled,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Select("SELECT COUNT(*) FROM app_user_data_scope WHERE district_id = #{districtId}")
    int countDistrictScopes(@Param("districtId") String districtId);

    @Update("""
            UPDATE app_user SET name = #{name}, phone = #{phone}, org_id = #{orgId},
                updated_at = #{at}, version = version + 1
            WHERE user_id = #{userId} AND version = #{expectedVersion}
            """)
    int updateUserProfile(@Param("userId") String userId, @Param("name") String name,
            @Param("phone") String phone, @Param("orgId") String orgId,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE app_user SET status = #{status}, updated_at = #{at}, version = version + 1,
                permission_version = permission_version + 1
            WHERE user_id = #{userId} AND version = #{expectedVersion}
            """)
    int setUserStatus(@Param("userId") String userId, @Param("status") String status,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE app_user
            SET status = 'DELETED', deleted_account = account, account = #{tombstoneAccount},
                deleted_role_code = role_code, role_code = NULL,
                scope_mode = 'NONE', must_change_password = TRUE, fail_count = 0, locked_until = NULL,
                deleted_at = #{at}, deleted_by = #{deletedBy}, updated_at = #{at},
                version = version + 1, permission_version = permission_version + 1
            WHERE user_id = #{userId} AND version = #{expectedVersion}
              AND status <> 'DELETED' AND role_code <> 'ROLE-ADMIN'
            """)
    int softDeleteUser(@Param("userId") String userId, @Param("tombstoneAccount") String tombstoneAccount,
            @Param("deletedBy") String deletedBy,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE app_user SET password_hash = #{passwordHash}, must_change_password = TRUE,
                fail_count = 0, locked_until = NULL, updated_at = #{at}, version = version + 1,
                permission_version = permission_version + 1
            WHERE user_id = #{userId} AND version = #{expectedVersion}
            """)
    int resetUserPassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Select("SELECT COUNT(*) FROM app_user WHERE role_code = 'ROLE-ADMIN' AND status = 'ACTIVE'")
    int countActiveAdmins();

    @Insert("""
            INSERT INTO access_change_request
              (change_id, change_type, subject_type, subject_id, requester_id, before_snapshot,
               after_snapshot, reason, subject_version, status, requested_at, version)
            VALUES
              (#{changeId}, #{changeType}, #{subjectType}, #{subjectId}, #{requesterId}, #{beforeSnapshot},
               #{afterSnapshot}, #{reason}, #{subjectVersion}, 'PENDING', #{requestedAt}, 0)
            """)
    int insertAccessChange(AccessChangeRow row);

    @Insert("""
            INSERT INTO pending_user_registration
              (change_id, account, name, phone, org_id, role_code, scope_mode, scope_grants, password_hash)
            VALUES
              (#{changeId}, #{account}, #{name}, #{phone}, #{orgId}, #{roleCode}, #{scopeMode}, #{scopeGrants}, #{passwordHash})
            """)
    int insertPendingUser(PendingUserRow row);

    @Select("""
            SELECT change_id AS changeId, account, name, phone, org_id AS orgId, role_code AS roleCode,
                   scope_mode AS scopeMode, scope_grants AS scopeGrants, password_hash AS passwordHash
            FROM pending_user_registration WHERE change_id = #{changeId}
            """)
    PendingUserRow findPendingUser(@Param("changeId") String changeId);

    @Delete("DELETE FROM pending_user_registration WHERE change_id = #{changeId}")
    int deletePendingUser(@Param("changeId") String changeId);

    @Select("""
            SELECT c.change_id AS changeId, c.change_type AS changeType, c.subject_type AS subjectType,
                   c.subject_id AS subjectId, c.requester_id AS requesterId, rq.name AS requesterName,
                   c.before_snapshot AS beforeSnapshot, c.after_snapshot AS afterSnapshot, c.reason,
                   c.subject_version AS subjectVersion, c.status, c.reviewer_id AS reviewerId,
                   rv.name AS reviewerName, c.review_comment AS reviewComment,
                   c.requested_at AS requestedAt, c.reviewed_at AS reviewedAt, c.version
            FROM access_change_request c
            JOIN app_user rq ON rq.user_id = c.requester_id
            LEFT JOIN app_user rv ON rv.user_id = c.reviewer_id
            ORDER BY c.requested_at DESC
            """)
    List<AccessChangeRow> listAccessChanges();

    @Select("""
            SELECT c.change_id AS changeId, c.change_type AS changeType, c.subject_type AS subjectType,
                   c.subject_id AS subjectId, c.requester_id AS requesterId, rq.name AS requesterName,
                   c.before_snapshot AS beforeSnapshot, c.after_snapshot AS afterSnapshot, c.reason,
                   c.subject_version AS subjectVersion, c.status, c.reviewer_id AS reviewerId,
                   rv.name AS reviewerName, c.review_comment AS reviewComment,
                   c.requested_at AS requestedAt, c.reviewed_at AS reviewedAt, c.version
            FROM access_change_request c
            JOIN app_user rq ON rq.user_id = c.requester_id
            LEFT JOIN app_user rv ON rv.user_id = c.reviewer_id
            WHERE c.change_id = #{changeId}
            """)
    AccessChangeRow findAccessChange(@Param("changeId") String changeId);

    @Update("""
            UPDATE access_change_request SET status = #{status}, reviewer_id = #{reviewerId},
                review_comment = #{comment}, reviewed_at = #{at}, version = version + 1
            WHERE change_id = #{changeId} AND status = 'PENDING' AND version = #{expectedVersion}
            """)
    int completeAccessChange(@Param("changeId") String changeId, @Param("status") String status,
            @Param("reviewerId") String reviewerId, @Param("comment") String comment,
            @Param("at") long at, @Param("expectedVersion") int expectedVersion);

    @Insert("""
            INSERT INTO access_change_record
              (record_id, change_id, actor_id, reviewer_id, subject_type, subject_id,
               before_snapshot, after_snapshot, reason, review_basis, created_at)
            VALUES
              (#{recordId}, #{changeId}, #{actorId}, #{reviewerId}, #{subjectType}, #{subjectId},
               #{beforeSnapshot}, #{afterSnapshot}, #{reason}, #{reviewBasis}, #{createdAt})
            """)
    int insertAccessChangeRecord(@Param("recordId") String recordId, @Param("changeId") String changeId,
            @Param("actorId") String actorId, @Param("reviewerId") String reviewerId,
            @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
            @Param("beforeSnapshot") String beforeSnapshot, @Param("afterSnapshot") String afterSnapshot,
            @Param("reason") String reason, @Param("reviewBasis") String reviewBasis,
            @Param("createdAt") long createdAt);

    @Insert("""
            INSERT INTO app_user
              (user_id, account, name, phone, org_id, role_code, status, password_hash, fail_count,
               scope_mode, must_change_password, permission_version, created_at, updated_at, version)
            VALUES
              (#{userId}, #{account}, #{name}, #{phone}, #{orgId}, #{roleCode}, 'ACTIVE', #{passwordHash}, 0,
               #{scopeMode}, TRUE, 0, #{at}, #{at}, 0)
            """)
    int insertUser(@Param("userId") String userId, @Param("account") String account,
            @Param("name") String name, @Param("phone") String phone, @Param("orgId") String orgId,
            @Param("roleCode") String roleCode, @Param("passwordHash") String passwordHash,
            @Param("scopeMode") String scopeMode, @Param("at") long at);

    @Update("""
            UPDATE app_user SET role_code = #{roleCode}, scope_mode = #{scopeMode},
                permission_version = permission_version + 1, updated_at = #{at}, version = version + 1
            WHERE user_id = #{userId} AND version = #{expectedVersion}
            """)
    int updateUserAccess(@Param("userId") String userId, @Param("roleCode") String roleCode,
            @Param("scopeMode") String scopeMode, @Param("at") long at,
            @Param("expectedVersion") int expectedVersion);

    @Delete("DELETE FROM app_user_data_scope WHERE user_id = #{userId}")
    int deleteUserScopes(@Param("userId") String userId);

    @Insert("""
            INSERT INTO app_user_data_scope (user_id, org_id, district_id)
            VALUES (#{userId}, #{orgId}, #{districtId})
            """)
    int insertUserScope(@Param("userId") String userId, @Param("orgId") String orgId,
            @Param("districtId") String districtId);

    @Update("UPDATE app_user SET permission_version = permission_version + 1 WHERE role_code = #{roleCode}")
    int bumpPermissionVersionForRole(@Param("roleCode") String roleCode);

    /* ---- 动作权限（决策 15-1）。与 MODULE 矩阵分开：矩阵要求整组提交，动作是逐项授予的。 ---- */

    @Select("""
            SELECT permission_code AS permissionCode, module_code AS moduleCode, module_name AS moduleName,
                   action_code AS actionCode, name, sort_order AS sortOrder
            FROM app_permission
            WHERE permission_kind = 'ACTION'
            ORDER BY sort_order, permission_code
            """)
    List<ActionRow> listActionCatalog();

    @Select("""
            SELECT a.permission_code AS permissionCode, a.module_code AS moduleCode, a.module_name AS moduleName,
                   a.action_code AS actionCode, a.name, a.sort_order AS sortOrder, p.permission_level AS level
            FROM app_role_permission p JOIN app_permission a ON a.permission_code = p.permission_code
            WHERE p.role_code = #{roleCode} AND a.permission_kind = 'ACTION'
            ORDER BY a.sort_order, a.permission_code
            """)
    List<ActionRow> listActionsForRole(@Param("roleCode") String roleCode);

    @Delete("""
            DELETE FROM app_role_permission
            WHERE role_code = #{roleCode}
              AND permission_code IN (SELECT permission_code FROM app_permission WHERE permission_kind = 'ACTION')
            """)
    int deleteRoleActions(@Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
            VALUES (#{roleCode}, #{permissionCode}, #{level}, FALSE, CURRENT_TIMESTAMP)
            """)
    int insertRoleAction(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode,
            @Param("level") String level);
}
