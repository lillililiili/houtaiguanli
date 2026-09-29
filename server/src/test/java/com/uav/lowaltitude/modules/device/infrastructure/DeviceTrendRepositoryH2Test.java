package com.uav.lowaltitude.modules.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeviceTrendRepositoryH2Test {
    @Test void numericAndBothReportSourcesAggregateInTheSupportedTestDatabase() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:trend_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        try {
            jdbc.execute("CREATE TABLE ops_device_state_history(state_id varchar(36),device_id varchar(36),connectivity varchar(16),observed_at bigint,received_at bigint,metric_code varchar(64),metric_value decimal(18,6),metric_unit varchar(32),simulated boolean)");
            jdbc.execute("CREATE TABLE mqtt_receive_diagnostic(ops_device_id varchar(36),received_at bigint,outcome varchar(32),reason varchar(64))");
            var repository = new DeviceTrendRepository(jdbc);
            DeviceTrendRepository.record(jdbc, "a", "active_track_count", 0, 1000, true);
            DeviceTrendRepository.record(jdbc, "a", "active_track_count", 6, 2000, true);
            DeviceTrendRepository.record(jdbc, "a", "active_track_count", 9, 125000, true);
            DeviceTrendRepository.record(jdbc, "b", "active_track_count", 999, 3000, true);
            var values = repository.series("a", 0, 180000, 60000, true, false, false);
            assertThat(values).hasSize(2);
            assertThat(((Number) values.get(0).get("average")).doubleValue()).isEqualTo(3);
            assertThat(((Number) values.get(0).get("latest")).doubleValue()).isEqualTo(6);
            assertThat(((Number) values.get(1).get("bucket")).intValue()).isEqualTo(2);
            assertThat(repository.series("a", 0, 180000, 60000, true, false, true)).hasSize(2);
            jdbc.update("INSERT INTO mqtt_receive_diagnostic VALUES('a',1000,'ACCEPTED','STATIC_UPDATED'),('a',4000,'ACCEPTED','STATIC_UPDATED'),('a',2000,'REJECTED','STATIC_UPDATED')");
            var reports = repository.series("a", 0, 180000, 60000, true, true, true);
            assertThat(reports).hasSize(1);
            assertThat(((Number) reports.get(0).get("samples")).intValue()).isEqualTo(2);
            assertThat(((Number) reports.get(0).get("interval_seconds")).doubleValue()).isEqualTo(3);
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
