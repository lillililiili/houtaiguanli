package com.uav.lowaltitude.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit backend identities for isolated tests; never promotes a business role. */
public final class BackendAccountFixture {
    private BackendAccountFixture() { }

    public static String session(JdbcTemplate jdbc, String scope, String org, String district) {
        String user = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values(?,?,?,'ROLE-BACKEND','ACTIVE','unused',0,?,0,0,0,0)", user, user, "隔离后台测试员", scope);
        if ("ASSIGNED".equals(scope))
            jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)", user, org, district);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",
                session, user, System.currentTimeMillis() + 3_600_000L);
        return session;
    }

    /** Fixed management/read grants are allowed; business actions must remain unassigned. */
    public static void assertBaselineActions(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("select permission_code from app_role_permission where role_code='ROLE-BACKEND' order by permission_code", String.class))
                .containsExactly("airspace:read", "alarm:read", "assessment:read", "device:read", "disposal:read",
                        "evidence:read", "flight:read", "handoff:read", "map:activate", "map:delete", "map:read", "map:upload",
                        "punishment:read", "risk:read", "route:read", "rule:manage", "rule:read", "target:read");
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where role_code='ROLE-BACKEND'"
                + " and (permission_level<>'OP' or menu_enabled=true)", Integer.class)).isZero();
    }
}
