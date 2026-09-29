package com.uav.lowaltitude.modules.assessment.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.annotation.Transactional;

/** 使用隔离 PostgreSQL 库，事务回滚替代删除只追加历史的 H2 清理逻辑。 */
@Transactional
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/maintenance_flow_verify_[a-z0-9_]+")
class OptionalReviewNotePostgresTest extends LegalityReviewApiTest {
    @Override @AfterEach void cleanup() { /* Spring rolls back this test's rows. */ }
}
