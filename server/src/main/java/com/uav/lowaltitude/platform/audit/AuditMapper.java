package com.uav.lowaltitude.platform.audit;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditMapper {

    @Insert("""
            INSERT INTO audit_log
              (audit_id, user_id, account, role_code, module_code, action, object_type, object_id,
               detail, occurred_at, ip, result, user_agent)
            VALUES
              (#{auditId}, #{userId}, #{account}, #{roleCode}, #{moduleCode}, #{action}, #{objectType}, #{objectId},
               #{detail}, #{occurredAt}, #{ip}, #{result}, #{userAgent})
            """)
    int insert(AuditLog log);

    @Select("""
            <script>
            SELECT audit_id AS auditId, user_id AS userId, account, role_code AS roleCode,
                   module_code AS moduleCode, action, object_type AS objectType, object_id AS objectId,
                   detail, occurred_at AS occurredAt, ip, result, user_agent AS userAgent
            FROM audit_log
            WHERE 1 = 1
            <if test='from != null'>AND occurred_at &gt;= #{from}</if>
            <if test='to != null'>AND occurred_at &lt;= #{to}</if>
            <if test='account != null and account != ""'>AND LOWER(account) LIKE #{account}</if>
            <if test='module != null and module != ""'>AND module_code = #{module}</if>
            <if test='action != null and action != ""'>AND action = #{action}</if>
            <if test='result != null and result != ""'>AND result = #{result}</if>
            <if test='objectType != null and objectType != ""'>AND object_type = #{objectType}</if>
            <if test='objectId != null and objectId != ""'>AND object_id = #{objectId}</if>
            ORDER BY occurred_at DESC, audit_id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLog> query(
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("account") String account,
            @Param("module") String module,
            @Param("action") String action,
            @Param("result") String result,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM audit_log
            WHERE 1 = 1
            <if test='from != null'>AND occurred_at &gt;= #{from}</if>
            <if test='to != null'>AND occurred_at &lt;= #{to}</if>
            <if test='account != null and account != ""'>AND LOWER(account) LIKE #{account}</if>
            <if test='module != null and module != ""'>AND module_code = #{module}</if>
            <if test='action != null and action != ""'>AND action = #{action}</if>
            <if test='result != null and result != ""'>AND result = #{result}</if>
            <if test='objectType != null and objectType != ""'>AND object_type = #{objectType}</if>
            <if test='objectId != null and objectId != ""'>AND object_id = #{objectId}</if>
            </script>
            """)
    long count(
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("account") String account,
            @Param("module") String module,
            @Param("action") String action,
            @Param("result") String result,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId);

    @Select("""
            SELECT audit_id AS auditId, user_id AS userId, account, role_code AS roleCode,
                   module_code AS moduleCode, action, object_type AS objectType, object_id AS objectId,
                   detail, occurred_at AS occurredAt, ip, result, user_agent AS userAgent
            FROM audit_log WHERE audit_id = #{auditId}
            """)
    AuditLog findById(@Param("auditId") String auditId);
}
