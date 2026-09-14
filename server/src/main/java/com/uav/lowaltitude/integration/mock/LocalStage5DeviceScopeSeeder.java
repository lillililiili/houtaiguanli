package com.uav.lowaltitude.integration.mock;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 为 local/test 的运维模拟设备提供固定的组织/区域归属映射。生产不会注册（双门禁），
 * 也不会按设备名称猜测归属；真实设备的归属资料到位后由受控迁移/配置导入。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(50)
public class LocalStage5DeviceScopeSeeder implements ApplicationRunner {

    /** 与 LocalStage2TargetSeeder 相同的稳定元组，让设备异常与目标/告警落在同一个演示范围。 */
    public static final String PLATFORM_ORG_ID = stable("demo-org:platform");
    public static final String DONGYING_DISTRICT_ID = stable("demo-district:dongying");
    public static final String OTHER_ORG_ID = "seed-stage5-other-org";
    public static final String OTHER_DISTRICT_ID = "seed-stage5-other-district";
    /** DEV-MOCK-005 映射到跨范围反例元组；DEV-MOCK-012 故意不映射，用于验证“无映射即不可见”。 */
    public static final List<String> PLATFORM_DEVICES = List.of("DEV-MOCK-001", "DEV-MOCK-002", "DEV-MOCK-003", "DEV-MOCK-004",
            "DEV-MOCK-006", "DEV-MOCK-007", "DEV-MOCK-008", "DEV-MOCK-009", "DEV-MOCK-010", "DEV-MOCK-011");
    public static final String CROSS_SCOPE_DEVICE = "DEV-MOCK-005";
    public static final String UNMAPPED_DEVICE = "DEV-MOCK-012";

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public LocalStage5DeviceScopeSeeder(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant at = clock.now();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_org where org_id=?)",
                OTHER_ORG_ID, "SEED-STAGE5-OTHER", "设备跨范围反例机构", at.toEpochMilli(), at.toEpochMilli(), OTHER_ORG_ID);
        jdbc.update("update app_org set name='设备跨范围反例机构' where org_id=? and name<>'设备跨范围反例机构'", OTHER_ORG_ID);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_district where district_id=?)",
                OTHER_DISTRICT_ID, "SEED-STAGE5-OTHER", "设备跨范围反例区域", at.toEpochMilli(), at.toEpochMilli(), OTHER_DISTRICT_ID);
        jdbc.update("update app_district set name='设备跨范围反例区域' where district_id=? and name<>'设备跨范围反例区域'", OTHER_DISTRICT_ID);
        for (String deviceNo : PLATFORM_DEVICES) map(deviceNo, PLATFORM_ORG_ID, DONGYING_DISTRICT_ID, at);
        map(CROSS_SCOPE_DEVICE, OTHER_ORG_ID, OTHER_DISTRICT_ID, at);
    }

    private void map(String deviceNo, String orgId, String districtId, Instant at) {
        // 只在设备、组织、区域都存在且尚无映射时写入：重跑不覆盖人工调整过的归属。
        jdbc.update("""
                insert into device_business_scope (ops_device_id, owner_org_id, district_id, created_at, updated_at)
                select d.device_id, ?, ?, ?, ?
                from ops_device d
                where d.device_no = ?
                  and exists (select 1 from app_org where org_id = ?)
                  and exists (select 1 from app_district where district_id = ?)
                  and not exists (select 1 from device_business_scope s where s.ops_device_id = d.device_id)
                """, orgId, districtId, Timestamp.from(at), Timestamp.from(at), deviceNo, orgId, districtId);
    }

    static String stable(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
