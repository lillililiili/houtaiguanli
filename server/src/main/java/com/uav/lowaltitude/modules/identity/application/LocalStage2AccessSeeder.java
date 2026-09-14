package com.uav.lowaltitude.modules.identity.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

@Component
// production 与 local/test 同时出现时仍由 !production 拒绝注册，防止部署 profile 组合把合成授权带入生产。
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(40)
public class LocalStage2AccessSeeder implements ApplicationRunner {

    private static final String SYNTHETIC_ROLE = "ROLE-ADMIN";

    private final JdbcTemplate jdbcTemplate;

    public LocalStage2AccessSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean changed = false;
        for (PermissionCode permission : PermissionCode.values()) {
            changed |= jdbcTemplate.update("""
                    insert into app_role_permission (
                        role_code, permission_code, permission_level, menu_enabled, created_at
                    )
                    select ?, ?, 'READ', false, current_timestamp
                    where exists (
                        select 1 from app_user
                        where account='admin1' and role_code=? and status='ACTIVE'
                    ) and not exists (
                        select 1 from app_role_permission
                        where role_code=? and permission_code=?
                    )
                    """,
                    SYNTHETIC_ROLE,
                    permission.value(),
                    SYNTHETIC_ROLE,
                    SYNTHETIC_ROLE,
                    permission.value()) > 0;
        }
        if (changed) {
            // 一轮补权只递增一次权限版本：已有会话会失效，操作者重新登录后才能取得完整的新动作授权。
            jdbcTemplate.update("""
                    update app_user
                    set permission_version=permission_version+1
                    where account='admin1' and role_code=?
                    """, SYNTHETIC_ROLE);
        }
    }
}
