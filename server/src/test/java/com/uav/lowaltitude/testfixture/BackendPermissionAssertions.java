package com.uav.lowaltitude.testfixture;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed backend grants never imply business write or countermeasure authority. */
public final class BackendPermissionAssertions {
    private BackendPermissionAssertions() { }

    public static void assertFixedGrants(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT permission_code FROM app_role_permission WHERE role_code='ROLE-BACKEND'", String.class))
                .containsExactlyInAnyOrder("device:read", "target:read", "alarm:read", "flight:read", "route:read",
                        "airspace:read", "assessment:read", "risk:read", "handoff:read", "disposal:read", "punishment:read",
                        "evidence:read", "rule:read", "rule:manage", "map:read", "map:upload", "map:activate", "map:delete");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_role_permission WHERE role_code='ROLE-BACKEND' AND (permission_level <> 'OP' OR menu_enabled=TRUE)", Integer.class)).isZero();
    }
}
