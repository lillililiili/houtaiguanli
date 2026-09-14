package com.uav.lowaltitude.modules.identity.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.uav.lowaltitude.modules.identity.domain.AppSession;

@Mapper
public interface SessionMapper {

    @Insert("""
            INSERT INTO app_session (session_id, user_id, expire_at, ip, shift_note, permission_version)
            VALUES (#{sessionId}, #{userId}, #{expireAt}, #{ip}, #{shiftNote}, #{permissionVersion})
            """)
    int insert(AppSession session);

    @Select("""
            SELECT session_id AS sessionId, user_id AS userId, expire_at AS expireAt, ip,
                   shift_note AS shiftNote, permission_version AS permissionVersion
            FROM app_session WHERE session_id = #{sessionId}
            """)
    AppSession findById(@Param("sessionId") String sessionId);

    @Update("UPDATE app_session SET expire_at = 0 WHERE session_id = #{sessionId}")
    int expire(@Param("sessionId") String sessionId);

    @Update("UPDATE app_session SET expire_at = 0 WHERE user_id = #{userId} AND expire_at > 0")
    int expireAllForUser(@Param("userId") String userId);

    @Update("""
            UPDATE app_session SET expire_at = 0
            WHERE user_id IN (SELECT user_id FROM app_user WHERE role_code = #{roleCode}) AND expire_at > 0
            """)
    int expireAllForRole(@Param("roleCode") String roleCode);
}
