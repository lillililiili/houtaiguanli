package com.uav.lowaltitude.modules.flight.infrastructure;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class LocalFlightPlanInputRepository {
 private final JdbcTemplate jdbc;
 public LocalFlightPlanInputRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void insert(String id,String no,String serial,String routeVersion,String org,String district,long start,long end,long now){jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'PENDING','mock',?,?,?,?,?,?,?,?,0)",id,no,serial,at(start),at(end),routeVersion,org,district,at(now),at(now));}
 private static java.time.OffsetDateTime at(long ms){return Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC);}
}
