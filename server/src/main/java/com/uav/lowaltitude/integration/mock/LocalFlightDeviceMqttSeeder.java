package com.uav.lowaltitude.integration.mock;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** 仅登记独立 MQTT 模拟设备；位置、故障及离线都由报文/超时生成，不直写状态。 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix="app.dev-seed",name="enabled",havingValue="true")
@Order(53)
public class LocalFlightDeviceMqttSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final MqttConfigurationService configuration;
    public LocalFlightDeviceMqttSeeder(JdbcTemplate jdbc,MqttConfigurationService configuration){this.jdbc=jdbc;this.configuration=configuration;}
    @Override public void run(ApplicationArguments args) {
        var admin=jdbc.queryForObject("SELECT user_id,name,role_code,permission_version,must_change_password,scope_mode FROM app_user WHERE account='admin1'",
            (rs,i)->new AuthUser(rs.getString("user_id"),"admin1",rs.getString("name"),rs.getString("role_code"),
                rs.getInt("permission_version"),rs.getBoolean("must_change_password"),rs.getString("scope_mode")));
        AuthContext.set(admin);
        try {
            var ids=jdbc.queryForList("SELECT broker_id FROM mqtt_broker WHERE name='local-flight-device-check' ORDER BY broker_id",String.class);
            String broker;
            if(ids.isEmpty()) {
                var created=configuration.create(new BrokerInput("local-flight-device-check","127.0.0.1",1883,false,null,null,"127.0.0.1/32",
                    "replay",LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID,LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID,null),key());
                broker=configuration.enable(created.brokerId(),created.version(),true,key()).brokerId();
            } else broker=ids.get(0);
            for(var entry:List.of(
                List.of("radar","FP-CHECK-R1","航线附近雷达·故障模拟"),
                List.of("tdoa","FP-CHECK-T1","航线附近TDOA·离线模拟"),
                List.of("rid","FP-CHECK-I1","航线附近RemoteID·正常模拟"),
                List.of("radar","FP-CHECK-R2","跨区航线雷达·正常模拟"))) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=?",Integer.class,entry.get(1))>0)continue;
                configuration.register(new Registration(LingyunEnvelope.PROTOCOL,broker,"fpcheck",entry.get(1),entry.get(0),"replay",
                    LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID,LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID,
                    entry.get(1),entry.get(2),"本地MQTT模拟",null,null),key());
            }
        } finally { AuthContext.clear(); }
    }
    private static String key(){return UUID.randomUUID().toString();}
}
