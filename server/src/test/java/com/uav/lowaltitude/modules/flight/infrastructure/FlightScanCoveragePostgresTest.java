package com.uav.lowaltitude.modules.flight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 使用独立 PostGIS 库和连接级临时表；不读取或修改业务航线。 */
@EnabledIfEnvironmentVariable(named="FLIGHT_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/stage_flight_verify_[a-z0-9_]+")
class FlightScanCoveragePostgresTest {
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    FlightReadRepository repository;
    String route;
    double lon=118.5,lat=37.4;
    @BeforeEach void setup() {
        source=new SingleConnectionDataSource(System.getenv("FLIGHT_TEST_PG_URL"),
            System.getenv("FLIGHT_TEST_PG_USER"),System.getenv("FLIGHT_TEST_PG_PASSWORD"),true);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TEMP TABLE route_version(route_version_id text PRIMARY KEY,centerline geometry(LineString,4326))");
        repository=new FlightReadRepository(jdbc,source);
        route=UUID.randomUUID().toString();
    }
    @AfterEach void close(){if(source!=null)source.destroy();}
    void path(String localWkt) {
        jdbc.update("INSERT INTO route_version VALUES (?,ST_Transform(ST_GeomFromText(?),?,4326))",
            route,localWkt,"+proj=aeqd +lat_0="+lat+" +lon_0="+lon+" +datum=WGS84 +units=m +no_defs");
    }
    Boolean intersects(double range,double azimuth,double fov) {
        return repository.routeIntersectsScanSector(route,BigDecimal.valueOf(lon),BigDecimal.valueOf(lat),
            BigDecimal.valueOf(range),BigDecimal.valueOf(azimuth),BigDecimal.valueOf(fov));
    }
    @ParameterizedTest
    @CsvSource({"0,60,true","180,60,false","350,40,true","90,60,false","180,300,false","90,300,true"})
    void usesDirectionAndFieldOfView(double azimuth,double fov,boolean expected) {
        path("LINESTRING(-50 500,50 500)");
        assertThat(intersects(1000,azimuth,fov)).isEqualTo(expected);
    }
    @Test void crossingSegmentCountsEvenWhenBothEndpointsAreOutsideSector() {
        path("LINESTRING(-2000 500,2000 500)");
        assertThat(intersects(1000,0,60)).isTrue();
    }
    @Test void laterRouteSegmentCountsWhenClosestSegmentIsBehindDevice() {
        path("LINESTRING(-10 -10,10 -10,500 500,-500 500)");
        assertThat(intersects(1000,0,60)).isTrue();
    }
    @Test void rangeIsCircularInsteadOfOuterPolygonApproximation() {
        path("LINESTRING(-50 1100,50 1100)");
        assertThat(intersects(1000,0,60)).isFalse();
        assertThat(intersects(1200,0,60)).isTrue();
    }
    @Test void differentCoordinatesAndLongRangeUseSameRule() {
        lon=120.2;lat=30.1;
        path("LINESTRING(-100 8000,100 8000)");
        assertThat(intersects(10000,0,90)).isTrue();
        assertThat(intersects(5000,0,90)).isFalse();
    }
    @Test void angularBoundaryAndNarrowSectorAreIncluded() {
        path("LINESTRING(0 400,0 600)");
        assertThat(intersects(1000,30,60)).isTrue();
        assertThat(intersects(1000,0,0.1)).isTrue();
    }
    @Test void absentOrEmptyRouteIsUnknown() {
        assertThat(intersects(1000,0,60)).isNull();
        path("LINESTRING EMPTY");
        assertThat(intersects(1000,0,60)).isNull();
    }
}
