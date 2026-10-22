package com.uav.lowaltitude.migration;

import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class UnitRoleCopyOriginMigrationPostgresTest extends UnitRoleCopyOriginMigrationTest {
    @Override protected Database database() {
        String url=System.getenv("POSTGRES_TEST_URL");
        if (url==null || !url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated stage456_verify database required");
        String schema="role_origin_"+UUID.randomUUID().toString().replace("-","");
        var rootSource=new DriverManagerDataSource(url,System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));
        var root=new JdbcTemplate(rootSource);
        root.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema+",public",rootSource.getUsername(),rootSource.getPassword());
        return new Database(source,schema,true,()->root.execute("DROP SCHEMA "+schema+" CASCADE"));
    }
}
