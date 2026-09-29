package com.uav.lowaltitude.modules.punishment.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.annotation.Transactional;

/** 不删除受防篡改触发器保护的历史，测试数据随事务回滚。 */
@Transactional
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/maintenance_flow_verify_[a-z0-9_]+")
class OptionalCaseNotePostgresTest extends PunishmentReviewTest {
    @Override @AfterEach void tearDown() { /* Spring rolls back this test's rows. */ }
}
