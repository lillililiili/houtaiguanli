package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.uav.lowaltitude.Application;

/**
 * production 必须压过 local：阶段 15 的第二演示账号 `reviewer1` / `ROLE-DEMO-REVIEWER`（决策 15-3）
 * 不允许因部署 profile 组合泄入生产。
 *
 * <p>这个账号比一般的演示数据更危险：它**不是一条可以事后删掉的业务记录，而是一个能登录的身份**。
 * 演示环境里它存在的理由是"复核人 ≠ 承办人""审批人 ≠ 申请人"——也就是说它天生带着
 * 批处置、批处罚这一类动作权限。一旦跟着 profile 组合进了生产，生产上就多出一个
 * 口令写在配置里、谁都知道的审批账号。
 *
 * <p>按 Bean 名断言，不引用 E1 的类型：类被重命名时这里也不会因编译依赖而"默认通过"。
 * 但动作目录那一段**刻意引用 `PermissionCode` 枚举**——理由相反：那里要的正是
 * "谁改了枚举就在这里立刻炸"。
 */
class ProductionStage15SeedIsolationTest {

    @Test void productionNeverRegistersStage15Seeder() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    /**
     * 反面对照：`local` 下种子**必须真的注册**。
     * 没有这一条，"production 下不注册"可能只是 Bean 条件写错了、任何 profile 都不注册——
     * 那样隔离测试全绿，而演示环境根本没有第二个账号，复核与两人审批那两条路一点就 409，
     * 问题要到演示当天才发现。
     */
    @Test
    void localStillRegistersTheSeeder() {
        try (ConfigurableApplicationContext context = context("local", "--app.dev-seed.enabled=true")) {
            assertThat(context.containsBean("localStage15DemoReviewerSeeder"))
                    .as("local 下第二账号种子必须注册，否则隔离测试的绿是假的").isTrue();
        }
    }

    private static void assertIsolated(String profiles) {
        // 故意打开演示种子开关：要证明的是"即使开关被打开 production 仍然赢"，
        // 而不是"因为没开所以没有"——后者在部署里换一个 profile 组合就不成立了。
        try (ConfigurableApplicationContext context = context(profiles, "--app.dev-seed.enabled=true")) {
            assertThat(context.containsBean("localStage15DemoReviewerSeeder"))
                    .as("阶段 15 第二账号种子不得在 production 注册").isFalse();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            // ① 账号与角色都不得存在。账号先查，因为它是"能登录的身份"，比角色更要命。
            assertThat(jdbc.queryForObject("select count(*) from app_user where account='reviewer1'", Integer.class))
                    .as("生产不得出现口令写在配置里的演示账号").isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_role where role_code='ROLE-DEMO-REVIEWER'", Integer.class))
                    .as("演示角色不得进生产").isZero();
            // 角色没了但授权行还在，等于给一个"看不见的角色"留着权限，下次谁重建同名角色就直接继承。
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_role_permission where role_code='ROLE-DEMO-REVIEWER'", Integer.class))
                    .as("演示角色的授权行同样不得残留").isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_user_data_scope s join app_user u on u.user_id=s.user_id"
                            + " where u.account='reviewer1'", Integer.class)).isZero();

            // 顺带把"生产不得有任何演示种子"这条一起守住：只按 seeder 命名筛，
            // 避免把某个正当能力误判成违规。
            List<String> demoSeeders = java.util.Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> name.toLowerCase().startsWith("localstage") && name.toLowerCase().endsWith("seeder"))
                    .toList();
            assertThat(demoSeeders).as("生产不得注册任何演示种子").isEmpty();

            // ② 动作权限**目录**必须在（迁移登记的，生产也要有），但**一条都不许预先授权**。
            //
            // 逐一对上枚举而不是抄一份字符串常量：抄一份的话，改了枚举、没改目录，两边各自"自洽"、
            // 用例照样绿，而真实后果是 access.require 永远拿不到那条权限，接口在生产上**静默 403**
            // （阶段 9 出过这类 P0）。
            List<String> expected = java.util.Arrays.stream(
                    com.uav.lowaltitude.modules.identity.domain.PermissionCode.values())
                    .map(com.uav.lowaltitude.modules.identity.domain.PermissionCode::value)
                    .sorted().toList();
            assertThat(jdbc.queryForList(
                    "select permission_code from app_permission where permission_kind='ACTION' order by permission_code",
                    String.class)).as("动作目录必须与枚举逐一对上，多一个少一个都不行")
                    .containsExactlyElementsOf(expected);
            // 集合相等还不够：枚举和目录同时少掉同一个码时 containsExactly 仍然全绿。
            assertThat(expected).as("动作码总数").hasSize(jdbc.queryForObject(
                    "select count(*) from app_permission where permission_kind='ACTION'", Integer.class));
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_permission where permission_kind='ACTION' and route_key is not null",
                    Integer.class)).as("动作码不是菜单项，带 route_key 会让它出现在导航里").isZero();
            // 目录行是**要上屏给一线人员看的**（决策 15-24 把英文开发描述逐码改成了中文）。
            // 只比对 permission_code 的话，新增一个码却忘了给中文，这里照样全绿，
            // 而权限编辑页上会冒出一行 'Stage 14 punishment read'——看的人不知道那是哪一项。
            //
            // 判据是"整串都是 ASCII"而不是"含某个特定词"：后者会随措辞变化误红。
            // 判断放在 Java 里做而不是写进 SQL：H2 没有 SIMILAR TO，而这条隔离用例本来就跑在 H2 上。
            assertThat(jdbc.queryForList(
                    "select permission_code, name from app_permission where permission_kind='ACTION'")
                    .stream().filter(row -> notChinese((String) row.get("name")))
                    .map(row -> row.get("permission_code") + "=" + row.get("name")).toList())
                    .as("这些动作码的 name 还是英文或为空，直接上屏就是给一线人员看英文（决策 15-24）").isEmpty();
            assertThat(jdbc.queryForList(
                    "select distinct module_code, module_name from app_permission where permission_kind='ACTION'")
                    .stream().filter(row -> notChinese((String) row.get("module_name")))
                    .map(row -> row.get("module_code") + "=" + row.get("module_name")).toList())
                    .as("这些动作域的 module_name 还是英文或为空（决策 15-24）").isEmpty();
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_role_permission p join app_permission c on c.permission_code=p.permission_code"
                            + " where c.permission_kind='ACTION' and p.role_code<>'ROLE-ADMIN'", Integer.class))
                    .as("除内置超级管理员外不得预先授予动作权限——授权矩阵是部署时的决定，迁移不该替部署做主")
                    .isZero();
        }
    }

    /** 空串、或整串都是 ASCII —— 两种都不是能直接摆到一线人员面前的说法。 */
    private static boolean notChinese(String text) {
        return text == null || text.isBlank() || text.chars().allMatch(character -> character < 128);
    }

    private static ConfigurableApplicationContext context(String profiles, String... extra) {
        String[] base = {
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage15_seed_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--app.disposal.expiry.enabled=false",
                "--spring.main.banner-mode=off" };
        String[] args = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(args);
    }
}
