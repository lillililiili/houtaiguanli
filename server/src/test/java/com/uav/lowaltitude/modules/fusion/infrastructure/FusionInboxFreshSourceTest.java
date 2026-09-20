package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.ingest.InboxSourceRouter;

class FusionInboxFreshSourceTest {
    @Test void disabledKeepsGlobalFifo() { check(h2(), false); }
    @Test void activeSourceGetsPriorityWithoutSkippingItsOlderFrames() { check(h2(), true); }

    @Test
    @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches=".+")
    void postgresPriorityKeepsSourceOrderAndHistoricalBacklog() {
        var root = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
                System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        var admin = new JdbcTemplate(root);
        if (!admin.queryForObject("select current_database()", String.class).matches("^advisory_verify_[a-z0-9_]+$"))
            throw new IllegalStateException("Requires isolated advisory_verify_ database");
        String schema = "notice_fifo_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            check(new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL") + "?currentSchema=" + schema,
                    System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD")), true);
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    private DriverManagerDataSource h2() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }
    private void check(DriverManagerDataSource source, boolean priority) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE inbox_message(inbox_id VARCHAR(36) PRIMARY KEY,source VARCHAR(128),source_msg_id VARCHAR(36),source_id VARCHAR(36),received_at BIGINT,payload JSON,status VARCHAR(16),lease_until BIGINT,lease_token VARCHAR(36),fusion_attempts INT DEFAULT 0,ingest_seq BIGINT)");
        long now = 1_000_000L;
        insert(jdbc, "historical", "lingyun:old", now-600_000, 1);
        insert(jdbc, "active-head", "lingyun:active", now-500_000, 2);
        insert(jdbc, "active-tail", "lingyun:active", now-1_000, 3);
        insert(jdbc, "ops", "live-device:ops", now-700_000, 4);
        var router = mock(InboxSourceRouter.class);
        when(router.prefixes()).thenReturn(List.of("lingyun:"));
        var properties = new FusionProperties();
        properties.setPrioritizeFreshSources(priority);
        var inbox = new FusionInboxRepository(jdbc, router, properties);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        for (String expected : priority ? List.of("active-head", "active-tail", "historical")
                                        : List.of("historical", "active-head", "active-tail")) {
            var rows = tx.execute(status -> inbox.claim(now, 1, 30_000, 5));
            assertThat(rows).extracting(FusionInboxRepository.InboxRow::inboxId).containsExactly(expected);
            jdbc.update("UPDATE inbox_message SET status='DONE' WHERE inbox_id=?", expected);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE inbox_id='ops'", String.class)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message", Integer.class)).isEqualTo(4);
    }
    private void insert(JdbcTemplate jdbc, String id, String code, long at, int seq) {
        jdbc.update("INSERT INTO inbox_message(inbox_id,source,source_msg_id,source_id,received_at,payload,status,ingest_seq) VALUES(?,?,?,?,?,CAST('{}' AS JSON),'RECEIVED',?)",
                id, code, id, code, at, seq);
    }
}
