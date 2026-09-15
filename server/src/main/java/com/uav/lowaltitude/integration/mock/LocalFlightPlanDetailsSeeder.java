package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.platform.time.AppClock;

/** 指定批次的报备信息演示。不会生成飞手执照、实名登记或航线批准文号。 */
@Component @Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true") @Order(100)
public class LocalFlightPlanDetailsSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final String prefix;
    public LocalFlightPlanDetailsSeeder(JdbcTemplate jdbc,AppClock clock,
            @Value("${app.dev-seed.flight-enrichment-prefix:}") String prefix) {
        this.jdbc=jdbc;this.clock=clock;this.prefix=prefix;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(prefix.isBlank())return;
        if(!prefix.matches("seed-refill-[0-9]{6}-[0-9]{6}"))throw new IllegalArgumentException("只允许指定补充演示批次");
        for(int n=1;n<=7;n++) {
            // 起降位置是明确新建的模拟报备，实际计划查询绝不从航线自动推断起降点。
            jdbc.update("""
                UPDATE flight_plan p SET pilot_name=?,operator_name='演示飞行作业单位（模拟）',
                    uav_sn=COALESCE(p.uav_sn,?),takeoff_site_name=?,landing_site_name=?,
                    takeoff_longitude=ST_X(ST_StartPoint(rv.centerline)),takeoff_latitude=ST_Y(ST_StartPoint(rv.centerline)),
                    landing_longitude=ST_X(ST_EndPoint(rv.centerline)),landing_latitude=ST_Y(ST_EndPoint(rv.centerline)),
                    updated_at=?,version=p.version+1
                FROM route_version rv WHERE p.route_version_id=rv.route_version_id AND p.plan_id=?
                    AND p.source_mode='mock' AND p.source_id='seed-stage3-source'
                    AND p.pilot_name IS NULL AND p.operator_name IS NULL AND p.takeoff_site_name IS NULL AND p.landing_site_name IS NULL
                    AND p.takeoff_longitude IS NULL AND p.takeoff_latitude IS NULL AND p.landing_longitude IS NULL AND p.landing_latitude IS NULL
                """, "演示飞手"+n+"（模拟）","DEMO-UAV-"+prefix.substring(12)+"-"+n,
                    "模拟起飞点 "+n,"模拟降落点 "+n,Timestamp.from(clock.now()),prefix+"-"+n);
        }
    }
}
