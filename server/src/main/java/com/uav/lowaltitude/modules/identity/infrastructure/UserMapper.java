package com.uav.lowaltitude.modules.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.uav.lowaltitude.modules.identity.domain.AppUser;

@Mapper
public interface UserMapper {

    String USER_COLUMNS = """
            user_id AS userId, account, name, role_code AS roleCode, status,
            password_hash AS passwordHash, fail_count AS failCount, locked_until AS lockedUntil,
            phone, org_id AS orgId, scope_mode AS scopeMode,
            must_change_password AS mustChangePassword, permission_version AS permissionVersion,
            last_login_at AS lastLoginAt, last_login_ip AS lastLoginIp,
            created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Select("SELECT " + USER_COLUMNS + " FROM app_user WHERE account = #{account}")
    AppUser findByAccount(@Param("account") String account);

    @Select("SELECT " + USER_COLUMNS + " FROM app_user WHERE user_id = #{userId}")
    AppUser findById(@Param("userId") String userId);

    @Update("UPDATE app_user SET fail_count = #{failCount}, locked_until = #{lockedUntil} WHERE user_id = #{userId}")
    int updateLock(@Param("userId") String userId, @Param("failCount") int failCount, @Param("lockedUntil") Long lockedUntil);

    @Update("""
            UPDATE app_user
            SET fail_count = 0, locked_until = NULL, last_login_at = #{at}, last_login_ip = #{ip}, updated_at = #{at}
            WHERE user_id = #{userId}
            """)
    int recordSuccessfulLogin(@Param("userId") String userId, @Param("at") long at, @Param("ip") String ip);

    @Update("""
            UPDATE app_user
            SET password_hash = #{passwordHash}, must_change_password = FALSE,
                fail_count = 0, locked_until = NULL, updated_at = #{at}, version = version + 1
            WHERE user_id = #{userId}
            """)
    int changePassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash, @Param("at") long at);

    @Update("""
            UPDATE app_user
            SET password_hash = #{passwordHash}, must_change_password = TRUE,
                status = 'ACTIVE', fail_count = 0, locked_until = NULL,
                permission_version = permission_version + 1, updated_at = #{at}, version = version + 1
            WHERE user_id = #{userId} AND role_code = 'ROLE-ADMIN'
            """)
    int recoverSuperAdmin(@Param("userId") String userId, @Param("passwordHash") String passwordHash,
            @Param("at") long at);

    @Select("SELECT COUNT(*) FROM app_user")
    int count();
}
