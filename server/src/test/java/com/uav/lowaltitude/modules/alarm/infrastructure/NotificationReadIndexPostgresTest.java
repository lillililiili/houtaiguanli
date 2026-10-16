package com.uav.lowaltitude.modules.alarm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** A real PostgreSQL prepared-plan regression; fixtures live only in a disposable schema.
 * Full Flyway upgrade and notification business contracts are covered by AutoSms/VoicePostgresTest.
 */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/advisory_verify_[a-z0-9_]+")
class NotificationReadIndexPostgresTest {
    @Test void latestTargetLookupDoesNotScanUnrelatedHistoryAfterPreparedPlanReuse() throws Exception {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("Disposable advisory verification database required");
        String schema="notify_read_"+UUID.randomUUID().toString().replace("-","");
        try(Connection connection=DriverManager.getConnection(url,System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"))) {
            JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).startsWith("advisory_verify_");
            jdbc.execute("CREATE SCHEMA "+schema);
            try {
                jdbc.execute("SET search_path TO "+schema);
                jdbc.execute("CREATE TABLE rule_evaluation(evaluation_id varchar(36) PRIMARY KEY,target_id varchar(36),mode varchar(8),subject_kind varchar(8),rule_set_version_id varchar(36),observed_at timestamptz,evaluated_at timestamptz,legal_status varchar(16),payload text)");
                jdbc.execute("CREATE INDEX old_observation ON rule_evaluation(target_id,mode,observed_at DESC,evaluation_id DESC)");
                jdbc.execute("CREATE INDEX old_version ON rule_evaluation(target_id,mode,rule_set_version_id,evaluated_at DESC)");
                jdbc.execute("CREATE INDEX old_target_partial ON rule_evaluation(target_id,mode,evaluated_at DESC,evaluation_id DESC) WHERE subject_kind='TARGET'");
                jdbc.execute("CREATE INDEX old_global_time ON rule_evaluation(evaluated_at DESC,evaluation_id DESC)");
                String hot=UUID.randomUUID().toString(),cold=UUID.randomUUID().toString(),missing=UUID.randomUUID().toString();
                String first=UUID.randomUUID().toString(),last=UUID.randomUUID().toString();
                if(first.compareTo(last)>0){String swap=first;first=last;last=swap;}
                jdbc.update("INSERT INTO rule_evaluation SELECT md5(?||n::text),?,'ACTIVE','TARGET','v',now(),now()+n*interval '1 second','ILLEGAL',repeat(md5(n::text),32) FROM generate_series(1,20000) n",hot,hot);
                jdbc.update("INSERT INTO rule_evaluation VALUES(?,?,'ACTIVE','TARGET','old',now(),now()-interval '2 days','ILLEGAL','old'),(?,?,'ACTIVE','TARGET','new',now()-interval '1 day',now()-interval '2 days','LEGAL','latest tie')",first,cold,last,cold);
                jdbc.update("INSERT INTO rule_evaluation VALUES(?,?,'SHADOW','TARGET','new',now(),now(),'ILLEGAL','shadow must not replace active')",UUID.randomUUID().toString(),cold);
                jdbc.execute("ANALYZE rule_evaluation");
                var migration=new ClassPathResource("db/migration/V202610140001__notification_latest_target_lookup.sql");
                if(migration.exists())jdbc.execute(migration.getContentAsString(StandardCharsets.UTF_8));
                jdbc.execute("SET plan_cache_mode=force_generic_plan");
                jdbc.execute("PREPARE latest_notice(varchar) AS SELECT legal_status FROM rule_evaluation WHERE target_id=$1 AND mode='ACTIVE' ORDER BY evaluated_at DESC,evaluation_id DESC FETCH FIRST 1 ROWS ONLY");
                assertThat(jdbc.queryForObject("EXECUTE latest_notice('"+cold+"')",String.class)).isEqualTo("LEGAL");
                assertThat(jdbc.queryForList("EXECUTE latest_notice('"+missing+"')")).isEmpty();
                for(String target:new String[]{cold,missing,hot}) {
                    String raw=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE latest_notice('"+target+"')",String.class);
                    JsonNode plan=new ObjectMapper().readTree(raw).get(0).get("Plan");
                    long buffers=plan.path("Shared Hit Blocks").asLong()+plan.path("Shared Read Blocks").asLong();
                    assertThat(buffers).as("latest target lookup must not scan unrelated history: %s",raw).isLessThan(64);
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_evaluation",Integer.class)).isEqualTo(20003);
                jdbc.execute("ALTER TABLE rule_evaluation ADD COLUMN owner_org_id varchar(36) DEFAULT 'owner', ADD COLUMN district_id varchar(36) DEFAULT 'district', ADD COLUMN alarm_id varchar(36), ADD COLUMN freshness_code varchar(16), ADD COLUMN unknown_reasons varchar(100)");
                jdbc.execute("CREATE TABLE alarm(alarm_id varchar(36) PRIMARY KEY,target_id varchar(36))");
                jdbc.execute("CREATE TABLE uav_event(event_id varchar(36) PRIMARY KEY,alarm_id varchar(36),owner_org_id varchar(36),district_id varchar(36))");
                for(String target:new String[]{cold,missing,hot}) {
                    jdbc.update("INSERT INTO alarm VALUES(?,?)",target,target);
                    jdbc.update("INSERT INTO uav_event VALUES(?,?,'owner','district')",target,target);
                }
                // Newer conclusions outside the event's org/district must still be ignored.
                for(String column:new String[]{"owner_org_id","district_id"}) {
                    jdbc.update("INSERT INTO rule_evaluation(evaluation_id,target_id,mode,evaluated_at,legal_status,"+column+") VALUES(?,?,'ACTIVE',now(),'ILLEGAL','other')",UUID.randomUUID().toString(),cold);
                }
                jdbc.execute("ANALYZE rule_evaluation");
                jdbc.execute("ANALYZE alarm");
                jdbc.execute("ANALYZE uav_event");
                var repository=new AutoSmsRepository(jdbc);
                assertThat(repository.latestEvaluation(cold).legalStatus()).isEqualTo("LEGAL");
                assertThat(repository.latestEvaluation(missing)).isNull();
                assertThat(repository.latestEvaluation(UUID.randomUUID().toString())).isNull();
                jdbc.execute("PREPARE event_notice(varchar) AS "+AutoSmsRepository.LATEST_EVALUATION_SQL.replace("?","$1"));
                for(String event:new String[]{cold,missing,hot}) {
                    String raw=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE event_notice('"+event+"')",String.class);
                    JsonNode plan=new ObjectMapper().readTree(raw).get(0).get("Plan");
                    long buffers=plan.path("Shared Hit Blocks").asLong()+plan.path("Shared Read Blocks").asLong();
                    assertThat(buffers).as("event lookup must resolve its own target before scanning evaluations: %s",raw).isLessThan(64);
                }
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
}
