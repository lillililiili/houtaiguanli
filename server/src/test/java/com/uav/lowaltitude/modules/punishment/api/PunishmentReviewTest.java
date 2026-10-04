package com.uav.lowaltitude.modules.punishment.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 处罚复核与决定书写入已关闭。保留清理方法，供 PostgreSQL 子类覆盖事务回滚。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PunishmentReviewTest {
    @Autowired JdbcTemplate jdbc;
    private PunishmentFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PunishmentFixture(jdbc);
    }

    @AfterEach
    void tearDown() { fixture.cleanup(); }
}
