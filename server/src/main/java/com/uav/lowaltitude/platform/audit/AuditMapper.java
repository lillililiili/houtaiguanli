package com.uav.lowaltitude.platform.audit;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

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

    @SelectProvider(type = AuditSql.class, method = "query")
    List<AuditLog> query(
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("account") String account,
            @Param("module") String module,
            @Param("action") String action,
            @Param("result") String result,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("includeAutomatic") boolean includeAutomatic,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @SelectProvider(type = AuditSql.class, method = "count")
    long count(
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("account") String account,
            @Param("module") String module,
            @Param("action") String action,
            @Param("result") String result,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("includeAutomatic") boolean includeAutomatic);

    @SelectProvider(type = AuditSql.class, method = "detail")
    AuditLog findById(@Param("auditId") String auditId);
}
