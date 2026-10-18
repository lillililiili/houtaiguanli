package com.uav.lowaltitude.platform.audit;

/** List, count, detail and export share the same menu classification and identity projection. */
public final class AuditSql {
    private static final String FILTER = """
            WHERE 1 = 1
            <if test='from != null'>AND a.occurred_at &gt;= #{from}</if>
            <if test='to != null'>AND a.occurred_at &lt;= #{to}</if>
            <if test='account != null and account != ""'>AND LOWER(a.account) LIKE #{account}</if>
            <if test='action != null and action != ""'>AND a.action = #{action}</if>
            <if test='result != null and result != "" and result != "FAILURE"'>AND a.result = #{result}</if>
            <if test='result == "FAILURE"'>AND a.result IN ('FAILURE', 'FAILED')</if>
            <if test='!includeAutomatic and (action == null or action == "")'>AND (a.action IS NULL OR a.action NOT IN (""" + automatic() + """
            ))</if>
            <if test='objectType != null and objectType != ""'>AND a.object_type = #{objectType}</if>
            <if test='objectId != null and objectId != ""'>AND a.object_id = #{objectId}</if>
            """ + "<if test='module != null and module != \"\"'>AND (" + AuditMenus.sql() + ") = #{module}</if> ";

    /** Constant action codes only (AuditLabels.AUTOMATIC_ACTIONS), never request input. */
    private static String automatic() {
        return String.join(",", AuditLabels.AUTOMATIC_ACTIONS.stream().map(code -> "'" + code + "'").toList());
    }

    private static String select(String inner) {
        return """
                SELECT a.*, COALESCE(NULLIF(TRIM(a.role_code), ''), u.role_code) AS display_role_code,
                       r.name AS role_name,
                       CASE WHEN NULLIF(TRIM(a.role_code), '') IS NOT NULL THEN 'RECORDED'
                            WHEN u.role_code IS NOT NULL THEN 'CURRENT' ELSE 'UNKNOWN' END AS role_source,
                """ + AuditMenus.sql() + " AS menu_code FROM (" + inner + """
                ) a
                LEFT JOIN app_user u ON u.user_id = a.user_id
                LEFT JOIN app_role r ON r.role_code = COALESCE(NULLIF(TRIM(a.role_code), ''), u.role_code)
                """;
    }

    public static String query() {
        return "<script>" + select("SELECT a.* FROM audit_log a " + FILTER
                + " ORDER BY a.occurred_at DESC, a.audit_id DESC LIMIT #{limit} OFFSET #{offset}")
                + " ORDER BY a.occurred_at DESC, a.audit_id DESC</script>";
    }
    public static String count() { return "<script>SELECT COUNT(*) FROM audit_log a " + FILTER + "</script>"; }
    public static String detail() { return select("SELECT a.* FROM audit_log a WHERE a.audit_id = #{auditId}"); }
}
