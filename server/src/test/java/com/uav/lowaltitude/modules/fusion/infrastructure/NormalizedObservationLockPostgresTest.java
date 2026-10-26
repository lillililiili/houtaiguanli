package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Consume real registrations concurrently with the same organization guard used by K03 writes. */
@SpringBootTest(properties = "app.prediction.enabled=false")
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class NormalizedObservationLockPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.enabled", () -> false);
    }
    @AfterAll static void close() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObservationRepository observations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @ParameterizedTest @ValueSource(ints={0,1})
    void observationsAllowPredictionAndOtherSourcesButFenceScopeChanges(int sourceIndex) {
        var f = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            observations.requireNormalizedDevice(f.sources[sourceIndex],f.devices[sourceIndex],f.org,f.district);
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(false);
                var independent = new JdbcTemplate(new SingleConnectionDataSource(other,true));
                independent.execute("SET LOCAL lock_timeout='1000ms'");
                // This is the exact trigger guard that was failing with P7102 during observation ingestion.
                independent.queryForList("SELECT require_current_organization(?)", f.org);
                var second = new ObservationRepository(independent,json);
                assertThat(second.requireNormalizedDevice(f.sources[1-sourceIndex],f.devices[1-sourceIndex],f.org,f.district).deviceId())
                        .isEqualTo(f.devices[1-sourceIndex]);
                other.rollback();
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
            // Catalog deactivation/retirement and source/device mutation must still wait for ingestion.
            assertLocked("SELECT org_id FROM app_org WHERE org_id=? FOR UPDATE NOWAIT", f.org);
            assertLocked("SELECT district_id FROM app_district WHERE district_id=? FOR UPDATE NOWAIT", f.district);
            assertLocked("SELECT source_id FROM integration_source WHERE source_id=? FOR UPDATE NOWAIT", f.sources[sourceIndex]);
            assertLocked("SELECT device_id FROM device WHERE device_id=? FOR UPDATE NOWAIT", f.devices[sourceIndex]);
            assertLocked("SELECT source_id FROM simulator_observation_source WHERE source_id=? FOR UPDATE NOWAIT", f.sources[sourceIndex]);
        });
    }

    @Test void disabledChangedOrUnregisteredSourcesStayRejected() {
        var f = fixture();
        assertRejected(f, id(), f.org);
        assertRejected(f, f.sources[0], id());
        jdbc.update("UPDATE integration_source SET enabled=FALSE WHERE source_id=?",f.sources[0]);
        assertRejected(f,f.sources[0],f.org);
        jdbc.update("UPDATE integration_source SET enabled=TRUE,source_mode='live' WHERE source_id=?",f.sources[0]);
        assertRejected(f,f.sources[0],f.org);
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?",f.org);
        assertRejected(f,f.sources[1],f.org);
    }

    private void assertRejected(Fixture f,String source,String org) {
        String device = source.equals(f.sources[1]) ? f.devices[1] : f.devices[0];
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                observations.requireNormalizedDevice(source,device,org,f.district)))
                .isInstanceOf(IllegalStateException.class).hasMessage("NORMALIZED_SOURCE_SCOPE_CHANGED");
    }
    private void assertLocked(String sql,String id) {
        try (Connection other = dataSource.getConnection(); var statement = other.prepareStatement(sql)) {
            statement.setString(1,id);
            assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException)error).getSQLState()).isEqualTo("55P03");
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
    private Fixture fixture() {
        String org=id(), district=id(); String[] sources={id(),id()}, devices={id(),id()};
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)",org,org,org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)",district,district,district);
        for(int i=0;i<2;i++) {
            jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,source_type,created_at,updated_at,version)"
                    +" VALUES (?,?,?,'SIM_NORMALIZED','1',TRUE,'replay','SIM_NORMALIZED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",sources[i],sources[i],sources[i]);
            jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                    +" VALUES (?,?,?,?,?,TRUE,'replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",devices[i],sources[i],devices[i],devices[i],devices[i],org,district);
            jdbc.update("INSERT INTO simulator_observation_source(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at)"
                    +" VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",sources[i],devices[i],id(),org,district,"0".repeat(64));
        }
        return new Fixture(org,district,sources,devices);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private record Fixture(String org,String district,String[] sources,String[] devices) { }
}
