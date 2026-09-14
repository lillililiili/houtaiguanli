package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.Application;

@SpringBootTest(properties = "app.dev-seed.enabled=true")
@ActiveProfiles("test")
class LocalStage5HandoffSeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage5HandoffSeeder seeder;
    @Autowired ApplicationArguments arguments;

    @Test
    void seedIsIdempotentAndNeverOverwritesManualSubmissions() {
        long recipients = count("select count(*) from handoff_recipient where recipient_id like 'seed-stage5-%'");
        long handoffs = count("select count(*) from handoff where handoff_id like 'seed-stage5-%'");
        long deliveries = count("select count(*) from handoff_delivery where handoff_id like 'seed-stage5-%'");
        String manual = "manual-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.now());
        String submitter = jdbc.queryForObject("select submitted_by from handoff where handoff_id='seed-stage5-handoff-pending'", String.class);
        String otherRecipient = jdbc.queryForObject("select recipient_id from handoff_recipient where recipient_id like 'seed-stage5-%' and recipient_id<>(select recipient_id from handoff where handoff_id='seed-stage5-handoff-pending') order by recipient_id fetch first 1 row only", String.class);
        String originalName = jdbc.queryForObject("select display_name from handoff_recipient where recipient_id=?", String.class, otherRecipient);
        try {
            jdbc.update("insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK','seed-stage5-risk-pending-delivery','seed-stage5-risk-pending-delivery',null,'RISK_NOTICE',?,1,'seed-stage3-org','seed-stage3-district','mock',?,?)",
                    manual, otherRecipient, submitter, at);
            jdbc.update("insert into handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) values (?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?)", manual + "-d1", manual, at);
            jdbc.update("update handoff_recipient set display_name='人工改名' where recipient_id=?", otherRecipient);
            seeder.run(arguments);
            assertThat(count("select count(*) from handoff_recipient where recipient_id like 'seed-stage5-%'")).isEqualTo(recipients);
            assertThat(count("select count(*) from handoff where handoff_id like 'seed-stage5-%'")).isEqualTo(handoffs);
            assertThat(count("select count(*) from handoff_delivery where handoff_id like 'seed-stage5-%'")).isEqualTo(deliveries);
            assertThat(count("select count(*) from handoff where handoff_id='" + manual + "'")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select display_name from handoff_recipient where recipient_id=?", String.class, otherRecipient)).isEqualTo("人工改名");
            assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id='seed-stage5-handoff-delivered' and attempt_no=1", String.class)).isEqualTo("DELIVERED");
        } finally {
            jdbc.update("delete from handoff_delivery where handoff_id=?", manual);
            jdbc.update("delete from handoff where handoff_id=?", manual);
            jdbc.update("update handoff_recipient set display_name=? where recipient_id=?", originalName, otherRecipient);
        }
    }

    @Test
    void seedsTwoRiskNoticeRecipientsOnePendingSampleAndOneMockDeliveredHistory() {
        assertThat(count("select count(*) from handoff_recipient where recipient_id like 'seed-stage5-%' and handoff_type='RISK_NOTICE' and enabled=true")).isEqualTo(2L);
        // 决策 18-14 之后页面不再选接收方，全新库上必须**恰好一个**默认：
        // 一个都没有，通知上级一律 400；多个默认等于没有默认，服务端还得再猜一次。
        assertThat(count("select count(*) from handoff_recipient where recipient_id like 'seed-stage5-%'"
                + " and handoff_type='RISK_NOTICE' and enabled=true and is_default=true")).isEqualTo(1L);
        assertThat(count("select count(*) from handoff h join handoff_delivery d on d.handoff_id=h.handoff_id where h.handoff_id='seed-stage5-handoff-pending' and d.attempt_no=1 and d.delivery_status='PENDING_DELIVERY' and d.receipt_status='NOT_EXPECTED' and d.blocked_reason='CHANNEL_NOT_CONNECTED' and h.source_mode='mock'")).isEqualTo(1L);
        assertThat(count("select count(*) from handoff h join handoff_delivery d on d.handoff_id=h.handoff_id where h.handoff_id='seed-stage5-handoff-delivered' and d.delivery_status='DELIVERED' and d.delivered_at is not null and h.source_mode='mock'")).isEqualTo(1L);
        assertThat(count("select count(*) from handoff h where h.handoff_id like 'seed-stage5-%' and not exists (select 1 from handoff_material_snapshot s where s.handoff_id=h.handoff_id)")).isZero();
        assertThat(count("select count(*) from handoff h join flight_risk r on r.risk_id=h.risk_id where h.handoff_id like 'seed-stage5-%' and r.state_code<>'NOTIFIED'")).isZero();
        assertThat(count("select count(*) from flight_risk where risk_id like 'seed-stage5-%' and state_code='NOTIFIED'")).isEqualTo(2L);
        // 阶段 14（决策 14-16）起，处罚交接确实会有一条种子夹具，所以不能再断言"全库为零"。
        // 这条断言真正要守的是"**阶段 5 的种子**不造处罚交接"——按前缀收窄，守住原意而不是删掉它。
        assertThat(count("select count(*) from handoff where handoff_type='UAV_PUNISHMENT'"
                + " and handoff_id like 'seed-stage5-%'")).isZero();
    }

    @Test
    void profileAndPropertyGateExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage5HandoffSeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage5HandoffSeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(profile).isNotNull();
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> java.util.Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> java.util.Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> java.util.Set.of("test").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
    }

    @Test
    void actualIsolatedContextsDoNotSeedWhenProductionOrPropertyGateBlocks() {
        assertIsolatedSeedAbsent(true, "production");
        assertIsolatedSeedAbsent(true, "production", "local");
        assertIsolatedSeedAbsent(false, "test");
    }

    private void assertIsolatedSeedAbsent(boolean enabled, String... profiles) {
        String database = "handoff-gate-" + UUID.randomUUID();
        String[] args = {
                "--spring.datasource.url=jdbc:h2:mem:" + database + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=" + enabled, "--app.dev-seed.password=Isolation-9!",
                "--app.live-device.enabled=false", "--spring.main.banner-mode=off"};
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).profiles(profiles).run(args)) {
            assertThat(context.getBeansOfType(LocalStage5HandoffSeeder.class)).isEmpty();
            JdbcTemplate isolated = context.getBean(JdbcTemplate.class);
            assertThat(isolated.queryForObject("select count(*) from handoff_recipient", Long.class)).isZero();
            assertThat(isolated.queryForObject("select count(*) from handoff", Long.class)).isZero();
        }
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
}
