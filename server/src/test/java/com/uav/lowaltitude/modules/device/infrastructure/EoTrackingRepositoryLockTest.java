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
    void automaticCandidatesExcludeRetiredAndDisabledUnitsButStopTasksRemainAvailable() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:eo_scope_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE app_org(org_id varchar(36) PRIMARY KEY,enabled boolean,deleted_at bigint)");
            jdbc.execute("CREATE TABLE app_district(district_id varchar(36) PRIMARY KEY,enabled boolean)");
            jdbc.execute("CREATE TABLE target(target_id varchar(36) PRIMARY KEY,owner_org_id varchar(36),district_id varchar(36))");
            jdbc.execute("CREATE TABLE target_latest_state(target_id varchar(36),observed_at timestamp with time zone)");
            jdbc.execute("CREATE TABLE eo_tracking_task(task_id varchar(36),target_id varchar(36),status varchar(24),created_at bigint)");
            jdbc.execute("CREATE TABLE eo_target_control(target_id varchar(36),auto_paused boolean)");
            jdbc.execute("CREATE TABLE alarm(alarm_id varchar(36),target_id varchar(36))");
            jdbc.execute("CREATE TABLE uav_event(alarm_id varchar(36),state_code varchar(32))");
            jdbc.execute("CREATE TABLE flight_risk(target_id varchar(36),state_code varchar(32),risk_type varchar(32),severity varchar(16))");
            jdbc.execute("CREATE TABLE rule_evaluation(evaluation_id varchar(36),target_id varchar(36),mode varchar(16),legal_status varchar(24))");
            jdbc.execute("CREATE TABLE legality_review(evaluation_id varchar(36),manual_status varchar(24))");
            String district=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO app_district VALUES(?,TRUE)",district);
            long now=System.currentTimeMillis();
            var active=new java.util.ArrayList<String>();
            var rejected=new java.util.ArrayList<String>();
            for(int index=0;index<4;index++) {
                String org=UUID.randomUUID().toString(),target=UUID.randomUUID().toString();
                boolean enabled=index<2;
                jdbc.update("INSERT INTO app_org VALUES(?,?,?)",org,enabled,index==2?now:null);
                jdbc.update("INSERT INTO target VALUES(?,?,?)",target,org,district);
                jdbc.update("INSERT INTO target_latest_state VALUES(?,?)",target,new java.sql.Timestamp(now-index));
                // Acknowledged historical risk may still satisfy visual-demand selection.
                jdbc.update("INSERT INTO flight_risk VALUES(?,'ACKNOWLEDGED','SPACE_OBJECT','HIGH')",target);
                (enabled?active:rejected).add(target);
                var repository=new EoTrackingRepository(jdbc,null);
                assertThat(repository.currentScope(org,district)).isEqualTo(enabled);
            }
            var repository=new EoTrackingRepository(jdbc,null);
            assertThat(repository.candidates(now-1000,now,20)).containsExactlyElementsOf(active);
            assertThat(repository.candidates(now-1000,now,20)).doesNotContainAnyElementsOf(rejected);
            String task=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO eo_tracking_task VALUES(?,?,'ENDING',?)",task,rejected.get(0),now);
            assertThat(repository.endingTasks()).containsExactly(task);
            assertThat(repository.currentScope(UUID.randomUUID().toString(),district)).isFalse();
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

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
