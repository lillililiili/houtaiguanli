package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 用户要求的飞行轨迹演示：独立日期/场景标识，只新增模拟观测，不补写旧历史。 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true")
@Order(96)
public class LocalFlightPathDemoSeeder implements ApplicationRunner {
    private static final String SOURCE="seed-stage3-source";
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final RuleRunService runs;
    public LocalFlightPathDemoSeeder(JdbcTemplate jdbc,AppClock clock,RuleRunService runs){this.jdbc=jdbc;this.clock=clock;this.runs=runs;}

    public static String prefix(Instant at){return "seed-fp-"+DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC).format(at);}

    @Override @Transactional
    public void run(ApplicationArguments args){
        if(args!=null && args.containsOption("preserve-existing-flight-demos"))return;
        Instant at=clock.now();
        // 五条通知演示计划已覆盖待执行；停用原来额外的一条纯状态样例，不删除任何历史。
        jdbc.update("UPDATE flight_plan SET status_code='CANCELLED',updated_at=?,version=version+1"
                + " WHERE plan_id='seed-stage3-plan-pending' AND source_mode='mock' AND status_code='PENDING'"
                + " AND NOT EXISTS(SELECT 1 FROM flight_plan_verification WHERE plan_id='seed-stage3-plan-pending')",ts(at));
        for(int n=1;n<=4;n++)seed(at,n);
    }

    private void seed(Instant at,int n){
        String id=prefix(at)+"-"+n,org=id+"-org",district=id+"-area",route=id+"-r",rv=id+"-rv",target=id+"-t",track=id+"-tr",link=id+"-ln";
        String dataset="flight-path-v1-"+id;
        if(jdbc.queryForObject("SELECT count(*) FROM rule_run WHERE replay_dataset_code=? AND status='DONE'",Integer.class,dataset)>0)return;
        boolean executing=n<=2,deviation=n==2,gap=n==4;
        String name=switch(n){case 1->"执行中·计划匹配（演示）";case 2->"执行中·计划偏离（演示）";case 3->"已完成·计划匹配（演示）";default->"已完成·轨迹中断（演示）";};
        Instant first=at.minusSeconds(executing?600:3600),last=first.plusSeconds(executing?65:100);
        Instant start=first.minusSeconds(300),end=executing?at.plusSeconds(21600):last.plusSeconds(300);
        // 每个场景独立范围，避免多个同期计划竞争目标；坐标是显式演示采样。
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) SELECT ?,?,?,true,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM app_org WHERE org_id=?)",org,org,"飞行轨迹演示机构"+n,at.toEpochMilli(),at.toEpochMilli(),org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) SELECT ?,?,?,true,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM app_district WHERE district_id=?)",district,district,"飞行轨迹演示区域"+n,at.toEpochMilli(),at.toEpochMilli(),district);
        double lat=37.43+n*.015;
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,?,true,?,'mock',?,?,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM route WHERE route_id=?)",route,"HX-"+id,name,SOURCE,org,district,ts(at),ts(at),route);
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) SELECT ?,?,1,CAST(? AS GEOMETRY),100,10,100,'AMSL',?,? WHERE NOT EXISTS(SELECT 1 FROM route_version WHERE route_version_id=?)",rv,route,String.format(Locale.ROOT,"SRID=4326;LINESTRING(118.61 %.6f,118.64 %.6f)",lat,lat),ts(start),ts(at),rv);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,?,?,'mock',?,?,?,?,?,?,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM flight_plan WHERE plan_id=?)",id,"JH-"+id,executing?"EXECUTING":"COMPLETED",SOURCE,id,ts(start),ts(end),rv,org,district,ts(at),ts(at),id);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,'UAV','QUADCOPTER',?,?,?,'mock',?,?,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM target WHERE target_id=?)",target,"MB-"+id,id,ts(first),ts(last),org,district,ts(first),ts(last),target);
        jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,protocol_version,created_at) SELECT ?,?,?,?,?,'1.0',? WHERE NOT EXISTS(SELECT 1 FROM target_source_link WHERE link_id=?)",link,target,SOURCE,id,id,ts(first),link);
        jdbc.update("INSERT INTO track(track_id,target_id,link_id,external_track_id,started_at,created_at) SELECT ?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM track WHERE track_id=?)",track,target,link,id,ts(first),ts(first),track);
        int count=executing?14:21;
        for(int i=0;i<count;i++){
            if(gap && i>=8 && i<=12)continue;
            String pointId=id+"-p"+i;Instant seen=first.plusSeconds(i*5L);
            jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at) SELECT ?,?,?,?,?,CAST(? AS GEOMETRY),50,40,? WHERE NOT EXISTS(SELECT 1 FROM track_point WHERE point_id=?)",pointId,track,i,ts(seen),ts(seen),point(i,lat,deviation),ts(seen),pointId);
        }
        jdbc.update("INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,created_at,updated_at,version) SELECT ?,CAST(? AS GEOMETRY),50,40,8,90,0.95,0.95,?,?,'[]',?,?,0 WHERE NOT EXISTS(SELECT 1 FROM target_latest_state WHERE target_id=?)",target,point(count-1,lat,deviation),ts(last),ts(last),ts(first),ts(last),target);
        // 由现有引擎计算匹配与偏离，不直接伪造 FULL/PARTIAL 或合法性结论。
        var asOf=last.atOffset(ZoneOffset.UTC);
        var run=runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE,RunMode.ACTIVE,"REPLAY",dataset,null,asOf);
        var result=runs.runBatch(run,List.of(new Subject(SubjectKind.TARGET,target,org,district,"mock")),asOf);
        if(!result.errors().isEmpty())throw new IllegalStateException("飞行演示研判失败: "+result.errors());
    }
    private static String point(int i,double lat,boolean deviation){return String.format(Locale.ROOT,"SRID=4326;POINT(%.6f %.6f)",118.61+i*.0015,lat+(deviation && i>6?(i-6)*.00025:0));}
    private static Timestamp ts(Instant at){return Timestamp.from(at);}
}
