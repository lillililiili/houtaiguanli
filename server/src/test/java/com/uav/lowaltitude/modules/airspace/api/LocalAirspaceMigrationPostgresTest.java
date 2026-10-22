package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="AIRSPACE_TEST_DB_URL",matches="jdbc:postgresql:.*airspace_verify_.*")
class LocalAirspaceMigrationPostgresTest {
    @Test void upgradesExistingAirspaceWithoutRewritingItsVersionOrOrigin() {
        String url=System.getenv("AIRSPACE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/airspace_verify_[a-zA-Z0-9_]+")) throw new IllegalStateException("Dedicated airspace_verify database required");
        String schema="airspace_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String user=System.getenv("AIRSPACE_TEST_DB_USER"),password=System.getenv("AIRSPACE_TEST_DB_PASSWORD");
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+schema);
        var datasource=new DriverManagerDataSource(url+"?currentSchema="+schema+",public",user,password);
        try {
            Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration","classpath:db/postgresql").target(MigrationVersion.fromVersion("202610200001")).load().migrate();
            JdbcTemplate jdbc=new JdbcTemplate(datasource);
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO airspace(airspace_id,airspace_no,name,source_mode,created_at,updated_at,version) VALUES(?,?,?,'live',current_timestamp,current_timestamp,7)",id,id,"preexisting");
            String version=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,valid_from,created_at) VALUES(?,?,1,'RESTRICTED',current_timestamp,current_timestamp)",version,id);
            Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration","classpath:db/postgresql").load().migrate();
            assertThat(jdbc.queryForObject("SELECT version FROM airspace WHERE airspace_id=?",Long.class,id)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM airspace WHERE airspace_id=?",java.sql.Timestamp.class,id)).isNull();
            assertThat(jdbc.queryForObject("SELECT airspace_version_id FROM airspace_version WHERE airspace_id=?",String.class,id)).isEqualTo(version);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version_origin WHERE airspace_version_id=?",Long.class,version)).isZero();
        } finally { root.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
}
