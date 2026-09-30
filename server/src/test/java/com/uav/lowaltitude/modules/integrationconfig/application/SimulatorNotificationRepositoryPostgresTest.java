package com.uav.lowaltitude.modules.integrationconfig.application;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class SimulatorNotificationRepositoryPostgresTest {
    @Test void matchingAndSupersededAttemptsOnPostgres(){
        String url=System.getenv("POSTGRES_TEST_URL"),user=System.getenv("POSTGRES_TEST_USER"),password=System.getenv("POSTGRES_TEST_PASSWORD");
        var root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        String database=root.queryForObject("SELECT current_database()",String.class);
        if(database==null||!database.matches("stage456_verify_[a-z0-9_]+"))throw new IllegalStateException("Requires isolated notification QA database");
        String schema="simulator_projection_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE SCHEMA "+schema);
        try{
            SimulatorNotificationRepositoryTest.verifyReceiptMatching(new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,user,password)));
        }finally{root.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
