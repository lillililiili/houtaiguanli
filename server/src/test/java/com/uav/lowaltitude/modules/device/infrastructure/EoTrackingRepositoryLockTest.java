package com.uav.lowaltitude.modules.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 自动跟踪轮询不等目标行锁（2026-10-07）：融合正写着的目标先跳过，下一轮再看。
 * 调度有多个线程时，等锁会和融合按不同次序锁目标形成死锁，PostgreSQL 回滚融合那一帧时数据就丢了。
 */
class EoTrackingRepositoryLockTest {

    @Test
    void automaticPollSkipsATargetAnotherTransactionIsWritingInsteadOfWaiting() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:eo_lock_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=300", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE target(target_id varchar(36) PRIMARY KEY, last_seen_at bigint)");
            jdbc.update("INSERT INTO target VALUES('busy',1),('idle',1)");
            var repository = new EoTrackingRepository(jdbc, null);
            try (Connection fusion = dataSource.getConnection()) {
                fusion.setAutoCommit(false);
                // 融合写目标：提交前一直持有这一行的锁。
                try (var update = fusion.prepareStatement("UPDATE target SET last_seen_at=2 WHERE target_id='busy'")) {
                    update.executeUpdate();
                }
                // 原来的写法在这里等锁（多线程时就是死锁的一半）。
                assertThatThrownBy(() -> jdbc.queryForList("SELECT target_id FROM target WHERE target_id='busy' FOR UPDATE"))
                        .isInstanceOf(DataAccessException.class);
                assertThat(repository.tryLock("busy")).isFalse();
                assertThat(repository.tryLock("idle")).isTrue();
                fusion.commit();
            }
            assertThat(repository.tryLock("busy")).isTrue();
            assertThat(repository.tryLock("missing")).isFalse();
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
