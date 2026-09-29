package com.uav.lowaltitude.modules.flight.infrastructure;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class LocalFlightPlanInputRepository {
 private final JdbcTemplate jdbc;
 public LocalFlightPlanInputRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void insert(String id,String no,String serial,String routeVersion,String org,String district,long start,long end,long now,String sourceMode,String status){jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0)",id,no,status,sourceMode,serial,at(start),at(end),routeVersion,org,district,at(now),at(now));}
 public java.util.List<com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Source> sources(){return jdbc.query("SELECT source_id,name,source_mode FROM integration_source WHERE enabled=TRUE AND source_mode IN ('mock','replay') ORDER BY name,source_id",(r,n)->new com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Source(r.getString(1),r.getString(2),r.getString(3)));}
 public boolean sourceAvailable(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM integration_source WHERE source_id=? AND enabled=TRUE AND source_mode IN ('mock','replay')",Long.class,id)>0;}
 public boolean sourceExists(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM integration_source WHERE source_id=?",Long.class,id)>0;}
 public int updateFiling(String id,long version,com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.Filing b,long now){return jdbc.update("UPDATE flight_plan SET source_id=?,operator_name=?,pilot_name=?,takeoff_site_name=?,landing_site_name=?,takeoff_longitude=?,takeoff_latitude=?,landing_longitude=?,landing_latitude=?,updated_at=?,version=version+1 WHERE plan_id=? AND version=? AND source_mode IN ('mock','replay')",
  blank(b.sourceId()),blank(b.operatorName()),blank(b.pilotName()),blank(b.takeoffSiteName()),blank(b.landingSiteName()),b.takeoffLongitude(),b.takeoffLatitude(),b.landingLongitude(),b.landingLatitude(),at(now),id,version);}
 private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
 private static java.time.OffsetDateTime at(long ms){return Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC);}
}
