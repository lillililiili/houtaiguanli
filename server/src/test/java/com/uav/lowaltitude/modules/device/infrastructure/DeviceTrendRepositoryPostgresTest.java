package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches=".+")
class DeviceTrendRepositoryPostgresTest {
    @Test void aggregatesAcceptedReportsAndNumericSamplesWithoutFillingMissingBuckets() {
        var source=new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));
        var root=new JdbcTemplate(source);
        assertThat(root.queryForObject("SELECT current_database()",String.class)).startsWith("stage456_verify_");
        String schema="trend_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE SCHEMA "+schema);
        try {
            var isolated=new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL")+"?currentSchema="+schema,System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));
            var jdbc=new JdbcTemplate(isolated);
            jdbc.execute("CREATE TABLE ops_device_state_history (state_id varchar(36) primary key,device_id varchar(36),connectivity varchar(16),observed_at bigint,received_at bigint,metric_code varchar(64),metric_value decimal(18,6),metric_unit varchar(32),simulated boolean)");
            jdbc.execute("CREATE TABLE mqtt_receive_diagnostic (ops_device_id varchar(36),received_at bigint,outcome varchar(32),reason varchar(64))");
            var repository=new DeviceTrendRepository(jdbc);
            DeviceTrendRepository.record(jdbc,"a","active_track_count",0,1000,false);
            DeviceTrendRepository.record(jdbc,"a","active_track_count",6,2000,false);
            DeviceTrendRepository.record(jdbc,"a","active_track_count",9,125000,false);
            DeviceTrendRepository.record(jdbc,"a","active_track_count",999,3000,true);
            DeviceTrendRepository.record(jdbc,"b","active_track_count",888,3000,false);
            var values=repository.series("a",0,180000,60000,false,false,false);
            assertThat(values).hasSize(2);
            assertThat(((Number)values.get(0).get("average")).doubleValue()).isEqualTo(3);
            assertThat(((Number)values.get(0).get("latest")).doubleValue()).isEqualTo(6);
            assertThat(((Number)values.get(1).get("bucket")).intValue()).isEqualTo(2);
            assertThat(((Number)repository.series("a",0,180000,60000,false,false,true).get(0).get("samples")).intValue()).isEqualTo(2);
            jdbc.update("INSERT INTO mqtt_receive_diagnostic VALUES ('a',1000,'ACCEPTED','STATIC_UPDATED'),('a',4000,'ACCEPTED','STATIC_UPDATED'),('a',2000,'DUPLICATE','STATIC_UPDATED'),('a',3000,'REJECTED','STATIC_UPDATED'),('b',5000,'ACCEPTED','STATIC_UPDATED')");
            var reports=repository.series("a",0,180000,60000,false,true,true);
            assertThat(reports).hasSize(1);
            assertThat(((Number)reports.get(0).get("samples")).intValue()).isEqualTo(2);
            assertThat(((Number)reports.get(0).get("interval_seconds")).doubleValue()).isEqualTo(3);
            assertThat(repository.series("a",5000,9000,1000,false,true,true)).isEmpty();
        } finally { root.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
}
