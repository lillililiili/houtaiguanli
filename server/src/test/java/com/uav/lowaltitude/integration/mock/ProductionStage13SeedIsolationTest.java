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
 * production 必须压过 local：阶段 13 处置授权域的演示数据与到期任务不允许因部署 profile 组合泄入生产。
 *
 * 这里守的是两件方向相反的事，不要混为一谈：
 *   ① **授权业务数据**（授权、只增事件流、编号计数）在生产必须一行都没有；
 *   ② **策略目录** `disposal_policy demo-v1` 在生产**必须存在且标 DEMO**（决策 13-1）——
 *      它是外键与策略读取的前提，缺了申请接口无参数可用；标 CONFIRMED 则等于宣称客户 Q5 已答复。
 *
 * 到期任务另有两道闸，本类验证的是这两道：默认关时 {@code disposalExpiryJob} 不注册；
 * 打开时恰好注册一个（Bean 条件写错只在有人真打开开关时才炸，不钉住就会留到现场）。
 *
 * 按 Bean 名断言，不引用 E1 的类型：类被重命名时这里也不会因编译依赖而"默认通过"。
 */
class ProductionStage13SeedIsolationTest {

    @Test void productionNeverRegistersStage13SeedersOrExpiryJob() { assertIsolated("production", true); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local", false); }

    /**
     * 开关的另一侧：`app.disposal.expiry.enabled=true` 时到期任务必须注册，且只有一个。
     * 没有这一条，"默认不注册"可能只是因为 Bean 条件写错了、任何时候都不注册——那样时限就永远不会被执行。
     */
    @Test
    void expirySwitchRegistersExactlyOneJobWhenTurnedOn() {
        // 关掉演示种子：这条只问"开关打开时 Bean 注册了几个"，不需要演示数据。
        // 也顺带绕开 LocalStage13DisposalSeeder 在"库里没有已启用设备"时的启动崩溃（已报 E1，见 13.3 报告）——
        // 那是种子自己的缺陷，不该让这条无关的用例替它红。
        try (ConfigurableApplicationContext context = context("local",
                "--app.disposal.expiry.enabled=true", "--app.dev-seed.enabled=false")) {
            assertThat(context.getBeanNamesForType(
                    com.uav.lowaltitude.modules.disposal.application.DisposalExpiryJob.class))
                    .as("开关打开时到期任务必须注册且只有一个").hasSize(1);
        }
    }

    /**
     * @param expiryOffByDefault 该 profile 组合下到期开关是否应处于缺省（关）状态。
     *     纯 `production` 是；`production,local` **不是**——`application-local.yml` 显式把
     *     `app.disposal.expiry.enabled` 设成了 true，那是一个部署配置的决定，不是演示数据泄漏。
     */
    private static void assertIsolated(String profiles, boolean expiryOffByDefault) {
        // 故意打开**演示种子**：要证明的是"即使种子开关被打开 production 仍然赢"，
        // 而不是"因为没开所以没有"——后者在部署里换一个 profile 组合就不成立了。
        //
        // 但**不打开到期开关**，这是有意的区别（与阶段 9 决策 9-16 同一道理）：
        // 到期任务不是演示设施，而是正式能力——生产恰恰需要它把过期授权置为 EXPIRED，
        // 否则昨天批的反制授权今天还点得动。所以这里要钉的是"**缺省不注册**"（部署没主动开就不跑），
        // 而不是"production 下永远不许注册"。把开关强行打开再要求它不注册，等于宣称生产不许执行时限，
        // 那会把一个安全机制反过来关掉。开关打开时的正确行为由 expirySwitchRegistersExactlyOneJobWhenTurnedOn 覆盖。
        try (ConfigurableApplicationContext context = context(profiles, "--app.dev-seed.enabled=true")) {
            if (expiryOffByDefault) {
                assertThat(context.containsBean("disposalExpiryJob"))
                        .as("app.disposal.expiry.enabled 缺省关，未显式打开时不得注册").isFalse();
            }
            // production,local 下开关来自 application-local.yml，任务会注册——这不是泄漏，
            // 但值得知道：**混进一个 local profile 就会翻转一个能力开关**。真正必须为空的是下面的业务数据。
            // 只扫**种子**：演示数据在生产永远不该有，无论开关怎么设。
            // 到期任务不在这个清单里——它是正式能力，注册与否由上面的开关判断决定（见方法注释）。
            List<String> disposalSeeders = java.util.Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> name.toLowerCase().contains("disposal") && name.toLowerCase().contains("seeder"))
                    .toList();
            assertThat(disposalSeeders).as("生产不得注册任何处置演示种子").isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            // ① 业务数据逐表为空。逐表列出而不是抽查——漏掉哪张表，那张表就是演示数据进生产的通道。
            for (String table : List.of("disposal_authorization", "disposal_authorization_event", "disposal_no_counter")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                        .as(table + " 是业务数据表，生产必须为空").isZero();
            }

            // ② 策略目录必须在，且必须自述为 DEMO。这一段断言方向与上面相反，是有意的。
            assertThat(jdbc.queryForObject("select count(*) from disposal_policy where policy_code='demo-v1'", Integer.class))
                    .as("策略来自迁移，生产也要有——缺了申请接口没有阈值可用").isEqualTo(1);
            assertThat(jdbc.queryForObject("select schema_status from disposal_policy where policy_code='demo-v1'", String.class))
                    .as("客户 Q5 未答复前不得标 CONFIRMED（决策 13-1）").isEqualTo("DEMO");
            assertThat(jdbc.queryForObject("select status from disposal_policy where policy_code='demo-v1'", String.class))
                    .isEqualTo("ACTIVE");
            // 参数齐全才谈得上"代码里没有裸阈值"（决策 13-2）：少一项就意味着某处要靠代码默认值兜底。
            String params = jdbc.queryForObject("select cast(params as varchar) from disposal_policy where policy_code='demo-v1'", String.class);
            assertThat(params).as("策略参数必须齐全，否则代码只能用裸阈值兜底")
                    .contains("approval_required").contains("two_person_rule")
                    .contains("time_limit_min").contains("requires_confirmed_event").contains("max_active_per_subject");

            // 权限目录行（迁移 0101）：只登记不授权。授权矩阵是部署时的决定，迁移不该替部署做主。
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_permission where permission_code like 'disposal:%'", Integer.class))
                    .as("处置权限码来自迁移，生产也要有").isPositive();
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_role_permission where permission_code like 'disposal:%' and role_code <> 'ROLE-ADMIN'",
                    Integer.class)).as("除内置超级管理员外不得预先授予处置权限").isZero();
        }
    }

    private static ConfigurableApplicationContext context(String profiles, String... extra) {
        String[] base = {
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage13_seed_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--spring.main.banner-mode=off" };
        String[] args = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(args);
    }
}
