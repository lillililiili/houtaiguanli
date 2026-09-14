package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 阶段 13 种子：三条授权形态齐全、已完成那条让处罚前提成立、重跑幂等。 */
@SpringBootTest
@ActiveProfiles("test")
class LocalStage13DisposalSeederTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage13DisposalSeeder seeder;

    @Test
    void seedsThreeAuthorizationShapes() {
        assertThat(status(LocalStage13DisposalSeeder.AUTH_APPROVED)).isEqualTo("APPROVED");
        assertThat(status(LocalStage13DisposalSeeder.AUTH_COMPLETED)).isEqualTo("COMPLETED");
        assertThat(status(LocalStage13DisposalSeeder.AUTH_REJECTED)).isEqualTo("REJECTED");
        // 已批准那条必须有有效期，否则页面上"还能不能执行"无从判断。
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where authorization_id=?"
                + " and valid_from is not null and valid_until is not null", Integer.class,
                LocalStage13DisposalSeeder.AUTH_APPROVED)).isEqualTo(1);
        // 已驳回那条不该有有效期：没批下来的授权不存在"生效时段"。
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where authorization_id=?"
                + " and valid_from is null and valid_until is null", Integer.class,
                LocalStage13DisposalSeeder.AUTH_REJECTED)).isEqualTo(1);
    }

    @Test
    void completedAuthorizationMakesPunishmentPrerequisiteHold() {
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class,
                LocalStage13DisposalSeeder.EVENT)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_kind='UAV_EVENT'"
                + " and subject_id=? and status='COMPLETED'", Integer.class,
                LocalStage13DisposalSeeder.EVENT)).isEqualTo(1);
    }

    @Test
    void policyIsSeededAsDemoOnly() {
        assertThat(jdbc.queryForObject("select schema_status from disposal_policy where policy_code='demo-v1'",
                String.class)).isEqualTo("DEMO");
        assertThat(jdbc.queryForObject("select count(*) from disposal_policy where schema_status='CONFIRMED'",
                Integer.class)).isZero();
    }

    @Test
    void seedsWithoutAnyDeviceRowInsteadOfCrashingTheContext() {
        // ck_stage13_authorization_device 要求非 MANUAL 通道必须带 device_id。
        // 没有可用设备时若还留 LINGYUN_B + null，种子会在启动阶段撞 CHECK——而种子一炸整个上下文都起不来，
        // 表现是所有用例全红。这里模拟"设备种子没装"的库：清掉本条演示授权，禁用全部设备，再跑一遍。
        jdbc.update("delete from disposal_authorization_event where authorization_id=?",
                LocalStage13DisposalSeeder.AUTH_APPROVED);
        jdbc.update("delete from disposal_authorization where authorization_id=?",
                LocalStage13DisposalSeeder.AUTH_APPROVED);
        jdbc.update("update ops_device set enabled=false");
        try {
            seeder.run(new DefaultApplicationArguments());
            assertThat(status(LocalStage13DisposalSeeder.AUTH_APPROVED)).isEqualTo("APPROVED");
            // 降级后必须是人工通道且不带设备，否则就是把 CHECK 违规换了个地方留着。
            assertThat(jdbc.queryForObject("select channel from disposal_authorization where authorization_id=?",
                    String.class, LocalStage13DisposalSeeder.AUTH_APPROVED)).isEqualTo("MANUAL");
            assertThat(jdbc.queryForObject("select device_id from disposal_authorization where authorization_id=?",
                    String.class, LocalStage13DisposalSeeder.AUTH_APPROVED)).isNull();
        } finally {
            jdbc.update("update ops_device set enabled=true");
        }
    }

    @Test
    void rerunIsIdempotent() {
        long before = count();
        ApplicationArguments args = new DefaultApplicationArguments();
        seeder.run(args);
        seeder.run(args);
        // 重跑不得再造一份：种子每次启动都跑，翻倍的话本地库几天就没法看了。
        assertThat(count()).isEqualTo(before);
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from disposal_authorization where authorization_id like 'seed-stage13-%'",
                Long.class);
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id);
    }
}
