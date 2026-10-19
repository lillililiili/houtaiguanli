package com.uav.lowaltitude.modules.reporting.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Small budgets exercise the production SQL boundary without generating half a million test rows. */
class ObservationMetricsBudgetTest {
    protected JdbcTemplate jdbc;
    private ObservationMetricsRepository repository;
    private TransactionTemplate transaction;
    private final String config = id();
    private final LocalDate day = LocalDate.now(ObservationMetrics.ZONE).minusDays(90);
    private final OffsetDateTime at = day.atTime(12, 0).atOffset(ZoneOffset.ofHours(8));

    protected DataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:budget_" + id()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }
    protected void closeDatabase() { jdbc.execute("SHUTDOWN"); }
    @BeforeEach void prepare() {
        DataSource dataSource = database();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE target(target_id VARCHAR PRIMARY KEY,source_mode VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,object_type_code VARCHAR)");
        jdbc.execute("CREATE TABLE track(track_id VARCHAR PRIMARY KEY,target_id VARCHAR,layer VARCHAR,config_version VARCHAR)");
        jdbc.execute("CREATE TABLE target_track_status(target_id VARCHAR,status VARCHAR)");
        jdbc.execute("CREATE TABLE app_org(org_id VARCHAR,enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_district(district_id VARCHAR,enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_user_data_scope(user_id VARCHAR,org_id VARCHAR,district_id VARCHAR)");
        jdbc.execute("CREATE TABLE track_point(point_id VARCHAR,track_id VARCHAR,observed_at TIMESTAMP WITH TIME ZONE,"
                + "point_kind VARCHAR,position_accuracy_m DOUBLE PRECISION,observation_id VARCHAR,position_source_id VARCHAR,"
                + "source_switched BOOLEAN,contributing JSON,location GEOMETRY)");
        jdbc.execute("CREATE TABLE source_observation(observation_id VARCHAR,source_id VARCHAR,source_mode VARCHAR,owner_org_id VARCHAR,"
                + "district_id VARCHAR,class_code VARCHAR,observed_at TIMESTAMP WITH TIME ZONE,quality JSON)");
        FusionConfigRepository configs = mock(FusionConfigRepository.class);
        when(configs.listAll()).thenReturn(List.of(new FusionConfigRepository.ConfigRow(config, "ACTIVE", "CONFIRMED",
                "{\"filter\":{\"max_dt_ms\":30000},\"identity\":{\"short_lost_after_ms\":30000},"
                + "\"association\":{\"gate_sigma\":5},\"quality\":{\"anomaly_zscore\":5}}", "", null, at, at, 0)));
        repository = new ObservationMetricsRepository(jdbc, new ObjectMapper(), configs, 4);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @AfterEach void cleanup() { closeDatabase(); }
    private static String id() { return UUID.randomUUID().toString(); }
    private record Fixture(String target, String track, String org, String district, String source, String mode) { }
    private Fixture fixture(String mode, int points, double lon, int offsetDays) {
        Fixture f = new Fixture(id(), id(), id(), id(), id(), mode);
        jdbc.update("INSERT INTO target VALUES(?,?,?,?,'UAV')", f.target, mode, f.org, f.district);
        jdbc.update("INSERT INTO track VALUES(?,?,'FUSED',?)", f.track, f.target, config);
        jdbc.update("INSERT INTO app_org VALUES(?,TRUE)", f.org);
        jdbc.update("INSERT INTO app_district VALUES(?,TRUE)", f.district);
        // Reverse insertion order also verifies the bounded probe does not select a truncated prefix.
        for (int i = points - 1; i >= 0; i--) {
            String observation = id(); OffsetDateTime time = at.plusDays(offsetDays).plusSeconds(i);
            jdbc.update("INSERT INTO source_observation VALUES(?,?,?,?,?,'UAV',?,CAST('{}' AS JSON))",
                    observation, f.source, mode, f.org, f.district, time);
            jdbc.update("INSERT INTO track_point VALUES(?,?,?,'MEAS',10,?,?,FALSE,CAST(? AS JSON),CAST(? AS GEOMETRY))",
                    id(), f.track, time, observation, f.source,
                    "[{\"observation_id\":\"" + observation + "\",\"source_id\":\"" + f.source + "\",\"weight\":1}]",
                    "SRID=4326;POINT(" + (lon + i * .0001) + " 35)");
        }
        return f;
    }
    private ObservationMetrics.Result read(ReportingRepository.Scope scope, List<String> modes) {
        return transaction.execute(status -> repository.read(day, day, at.plusDays(1).toInstant().toEpochMilli(), scope, modes));
    }
    @ParameterizedTest @CsvSource({"live,103,3", "replay,114,4", "mock,-71,4"})
    void belowAndExactlyAtBudgetKeepCompleteMetrics(String mode, double lon, int points) {
        Fixture f = fixture(mode, points, lon, 0);
        var result = read(new ReportingRepository.Scope(true, "reader", f.org), List.of(mode));
        assertThat(result.durationSeconds()).isEqualTo(points - 1d);
        assertThat(result.validSegments()).isEqualTo(points - 1);
        assertThat(result.sourceModes()).containsExactly(mode);
    }
    @ParameterizedTest @CsvSource({"live,104", "replay,-73"})
    void exceedingBudgetDoesNotPublishPartialOrZeroTotals(String mode, double lon) {
        fixture(mode, 5, lon, 0);
        var result = read(new ReportingRepository.Scope(true, "reader"), List.of(mode));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.reason()).contains("统计上限", "缩短统计周期");
        assertThat(result.durationSeconds()).isNull();
        assertThat(result.distanceMeters()).isNull();
        assertThat(result.days()).isEmpty();
    }
    @Test void budgetUsesSameSourceTimeMergeAndTupleScopeAsMetrics() {
        Fixture own = fixture("replay", 4, 110, 0);
        fixture("live", 6, 112, 0);
        fixture("replay", 6, 114, -2);
        Fixture merged = fixture("replay", 6, 116, 0);
        jdbc.update("INSERT INTO target_track_status VALUES(?,'MERGE')", merged.target);
        var visible = read(new ReportingRepository.Scope(true, "reader"), List.of("replay"));
        assertThat(visible.durationSeconds()).isEqualTo(3);
        fixture("replay", 6, 118, 0);
        String user = id();
        jdbc.update("INSERT INTO app_user_data_scope VALUES(?,?,?)", user, own.org, own.district);
        assertThat(read(new ReportingRepository.Scope(false, user), List.of("replay")).durationSeconds()).isEqualTo(3);
        assertThat(read(new ReportingRepository.Scope(true, user, own.org), List.of("replay")).durationSeconds()).isEqualTo(3);
        assertThat(read(new ReportingRepository.Scope(false, id()), List.of("replay")).status()).isEqualTo("NO_DATA");
    }
}
