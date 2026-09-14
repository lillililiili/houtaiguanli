package com.uav.lowaltitude.modules.identity.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdempotencyMapper {

    @Insert("""
            INSERT INTO idempotency_request (idem_key, user_id, request_hash, response_body, created_at)
            VALUES (#{storedKey}, #{userId}, #{requestHash}, NULL, #{createdAt})
            """)
    int insert(@Param("storedKey") String storedKey, @Param("userId") String userId,
            @Param("requestHash") String requestHash, @Param("createdAt") long createdAt);

    @Select("SELECT request_hash FROM idempotency_request WHERE idem_key = #{storedKey}")
    String findHash(@Param("storedKey") String storedKey);
}
