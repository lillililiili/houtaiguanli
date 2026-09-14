package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 设备运维演示"体量"夹具：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 * <p>
 * 背景：{@code LocalDeviceSeeder} 自阶段 3 起只挂在 test profile（迁移 V202609050012 也清掉了旧的 DEV-MOCK-* 台账），
 * 因此本地新库的设备管理 / 实时监测 / 接入调测页面为空，{@code LocalStage5DeviceScopeSeeder} 映射的 DEV-MOCK-* 设备
 * 也不存在，工作台设备异常块一直是 UNCONFIGURED。本类补一套东营范围内的模拟设备，覆盖连接/健康/异常/指令/调测的每种状态。
 * <p>
 * 规则：只补缺行（SELECT … WHERE NOT EXISTS），稳定 ID 前缀 {@code seed-vol-device-}，不 UPDATE 任何已有记录；
 * 全部标记 source_mode='mock'、simulated=TRUE，来源为独立的模拟适配器记录，不与 test 夹具或阶段 2/7 的来源混用。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(52)
@DependsOn({"localUserSeeder", "localStage5DeviceScopeSeeder"})
public class LocalDemoVolumeDeviceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoVolumeDeviceSeeder.class);

    public static final String SOURCE_ID = "seed-vol-device-source";
    private static final String SOURCE_CODE = "SEED-VOL-MOCK";
    private static final String ORG = LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID;
    private static final String DISTRICT = LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID;
    private static final String OTHER_ORG = LocalStage5DeviceScopeSeeder.OTHER_ORG_ID;
    private static final String OTHER_DISTRICT = LocalStage5DeviceScopeSeeder.OTHER_DISTRICT_ID;

    private static final long MINUTE = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    /** 十二台设备：类型码/通道沿用台账与接入目录已使用的值；坐标落在东营（118.2–118.7 / 37.2–37.7）。 */
    private static final List<Seed> SEEDS = List.of(
            new Seed(1, "东营区雷达31号", "radar", "雷达", "融合感知箱", "东营区", 118.520, 37.460, "ONLINE", "GOOD", false),
            new Seed(2, "东营区光电12号", "oe", "光电", "融合感知箱", "东营区", 118.500, 37.440, "ONLINE", "GOOD", false),
            new Seed(3, "东营区反制05号", "countermeasure", "反制", "反制直连", "东营区", 118.550, 37.470, "ONLINE", "DEGRADED", false),
            new Seed(4, "河口区雷达07号", "radar", "雷达", "雷达直连", "河口区", 118.530, 37.660, "ABNORMAL", "BAD", false),
            new Seed(5, "河口区TDOA03号", "tdoa", "TDOA", "TDOA", "河口区", 118.600, 37.620, "OFFLINE", "UNKNOWN", false),
            new Seed(6, "垦利区AOA02号", "aoa", "AOA", "TDOA", "垦利区", 118.580, 37.550, "ONLINE", "GOOD", false),
            new Seed(7, "垦利区RemoteID04号", "rid", "RemoteID", "TDOA", "垦利区", 118.620, 37.520, "UNKNOWN", "UNKNOWN", false),
            new Seed(8, "广饶县融合终端01号", "other", "融合终端", "融合感知箱", "广饶县", 118.420, 37.250, "ONLINE", "GOOD", false),
            new Seed(9, "广饶县雷达08号", "radar", "雷达", "融合感知箱", "广饶县", 118.380, 37.300, "ABNORMAL", "DEGRADED", false),
            new Seed(10, "利津县光电03号", "oe", "光电", "融合感知箱", "利津县", 118.270, 37.500, "OFFLINE", "UNKNOWN", false),
            new Seed(11, "东营港5G-A基站02号", "5ga", "5G-A基站", "5G-A", "东营港经济区", 118.660, 37.690, "ONLINE", "GOOD", true),
            new Seed(12, "利津县反制02号", "countermeasure", "反制", "反制直连", "利津县", 118.240, 37.550, "ONLINE", "BAD", false));

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public LocalDemoVolumeDeviceSeeder(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String deviceId(int seq) { return String.format(Locale.ROOT, "seed-vol-device-%02d", seq); }
    public static String deviceNo(int seq) { return String.format(Locale.ROOT, "设备-0908-%03d", seq); }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String admin = jdbc.query("SELECT user_id FROM app_user WHERE account='admin1'", rs -> rs.next() ? rs.getString(1) : null);
        if (admin == null) throw new IllegalStateException("demo volume device seed requires the local admin1 user (LocalUserSeeder)");
        long now = clock.nowMillis();
        source(now);
        for (Seed seed : SEEDS) device(seed, now);
        incidents(now);
        commands(admin, now);
        commissions(admin, now);
        log.info("demo volume device seed present: {} devices, incidents/commands/commission tasks in every state", SEEDS.size());
    }

    private void source(long now) {
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,source_mode,enabled,simulated,created_at,updated_at)
                SELECT ?,?,?,'mock',TRUE,TRUE,?,? WHERE NOT EXISTS (SELECT 1 FROM ops_integration_source WHERE source_id=?)
                """, SOURCE_ID, SOURCE_CODE, "本地演示体量模拟适配器", now, now, SOURCE_ID);
    }

    // ---------------------------------------------------------------- 台账、连接配置、最新状态、历史

    private void device(Seed seed, long now) {
        String id = deviceId(seed.seq());
        int i = seed.seq();
        boolean tcp = i % 3 == 0;
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,model,vendor,owner_name,region_name,address,longitude,latitude,
                    coordinate_system,altitude_m,altitude_datum,firmware_version,installed_at,enabled,
                    source_mode,simulated,version,created_at,updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'WGS-84',?,'AMSL',?,?,TRUE,'mock',TRUE,0,?,?
                WHERE NOT EXISTS (SELECT 1 FROM ops_device WHERE device_id=?)
                """, id, SOURCE_ID, "VOL-EXT-" + String.format(Locale.ROOT, "%03d", i), deviceNo(i), seed.name(),
                seed.typeCode(), seed.typeName(), seed.channel(), "待设备方确认", "演示模拟厂商",
                seed.region() + "低空监管中心", seed.region(), seed.region() + "演示布设点" + i,
                seed.lon(), seed.lat(), 12.0 + i, "mock-1.0", now - (30L + i) * DAY, now - (30L + i) * DAY, now, id);
        jdbc.update("""
                INSERT INTO device_connection_profile (device_id,transport,host,port,path,data_format,charset_name,
                    auth_mode,heartbeat_interval_seconds,report_interval_millis,sampling_rate_hz,
                    compression_enabled,retransmission_enabled,timeout_millis,retry_count,
                    longitude_offset_deg,latitude_offset_deg,altitude_offset_m,time_sync_mode,time_server,
                    timezone_name,time_sync_interval_seconds,version,updated_at)
                SELECT ?,?,?,?,?,'JSON','UTF-8','Token',30,1000,10,FALSE,TRUE,3000,3,0,0,0,'NTP',?,?,60,0,?
                WHERE NOT EXISTS (SELECT 1 FROM device_connection_profile WHERE device_id=?)
                """, id, tcp ? "TCP" : "HTTP", "192.0.2." + (40 + i), tcp ? 9001 : 8080, tcp ? null : "/api/v1/data",
                "time.example.invalid", "Asia/Shanghai", now, id);
        state(seed, id, now);
        history(seed, id, now);
        scope(id, seed.crossScope() ? OTHER_ORG : ORG, seed.crossScope() ? OTHER_DISTRICT : DISTRICT, now);
        boolean warn = "ABNORMAL".equals(seed.connectivity()) || "OFFLINE".equals(seed.connectivity());
        event("seed-vol-device-ev-" + i + "-1", id, "STATE_RECEIVED", warn ? "WARN" : "INFO",
                "演示模拟状态已接收：" + java.util.Map.of("ONLINE", "在线", "OFFLINE", "离线", "ABNORMAL", "异常", "UNKNOWN", "未知").getOrDefault(seed.connectivity(), seed.connectivity()), now - i * 30_000L);
    }

    private void state(Seed seed, String id, long now) {
        int i = seed.seq();
        String connectivity = seed.connectivity();
        long heartbeat = switch (connectivity) {
            case "OFFLINE" -> now - (30 + i) * MINUTE;
            case "UNKNOWN" -> now - 3 * HOUR;
            default -> now - (i % 3) * 20_000L;
        };
        boolean alarm = "ABNORMAL".equals(connectivity) || !"GOOD".equals(seed.health());
        double loss = "BAD".equals(seed.health()) ? 12.5 : "DEGRADED".equals(seed.health()) ? 3.8 : 0.2 + i * 0.05;
        String metrics = String.format(Locale.ROOT,
                "{\"link_latency_ms\":{\"label\":\"链路时延\",\"value\":%d,\"unit\":\"ms\",\"source\":\"mock-adapter\"},"
                + "\"packet_loss_pct\":{\"label\":\"丢包率\",\"value\":%.2f,\"unit\":\"%%\",\"source\":\"mock-adapter\"},"
                + "\"rssi_dbm\":{\"label\":\"信号强度\",\"value\":%d,\"unit\":\"dBm\",\"source\":\"mock-adapter\"}}",
                latency(seed, 0), loss, rssi(seed, 0));
        String workState = "ABNORMAL".equals(connectivity) ? "2" : "ONLINE".equals(connectivity) ? "1" : "0";
        jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,work_state_code,has_alarm,health_code,
                    observed_at,received_at,last_heartbeat_at,metrics_json,unknown_reason,simulated,version)
                SELECT ?,?,?,?,?,?,?,?,?,?,TRUE,0 WHERE NOT EXISTS (SELECT 1 FROM ops_device_state WHERE device_id=?)
                """, id, connectivity, workState, alarm, seed.health(), heartbeat, now, heartbeat, metrics,
                "UNKNOWN".equals(connectivity) ? "演示模拟：超过 3 小时未收到状态上报" : null, id);
    }

    /** 最近 30 分钟、每 2.5 分钟一点的三条指标曲线，供实时监测页的历史曲线使用。 */
    private void history(Seed seed, String id, long now) {
        for (int point = 0; point < 12; point++) {
            long at = now - (11L - point) * 150_000L;
            historyPoint(seed, id, "ll", "link_latency_ms", point, at, latency(seed, point), "ms");
            historyPoint(seed, id, "pl", "packet_loss_pct", point, at,
                    ("BAD".equals(seed.health()) ? 10.0 : "DEGRADED".equals(seed.health()) ? 3.0 : 0.2) + (point % 4) * 0.35, "%");
            historyPoint(seed, id, "rs", "rssi_dbm", point, at, rssi(seed, point), "dBm");
        }
    }

    private void historyPoint(Seed seed, String deviceId, String abbr, String code, int point, long at, double value, String unit) {
        String stateId = String.format(Locale.ROOT, "seed-vol-dh-%02d-%s-%02d", seed.seq(), abbr, point);
        jdbc.update("""
                INSERT INTO ops_device_state_history (state_id,device_id,connectivity,observed_at,received_at,
                    metric_code,metric_value,metric_unit,simulated)
                SELECT ?,?,?,?,?,?,?,?,TRUE WHERE NOT EXISTS (SELECT 1 FROM ops_device_state_history WHERE state_id=?)
                """, stateId, deviceId, seed.connectivity(), at, at, code, value, unit, stateId);
    }

    private static int latency(Seed seed, int point) {
        int base = "BAD".equals(seed.health()) ? 320 : "DEGRADED".equals(seed.health()) ? 140 : 24 + seed.seq() * 3;
        return base + (point % 5) * 4;
    }

    private static int rssi(Seed seed, int point) {
        int base = "BAD".equals(seed.health()) ? -92 : "DEGRADED".equals(seed.health()) ? -78 : -55 - seed.seq();
        return base - (point % 3);
    }

    /** 与 LocalStage5DeviceScopeSeeder 同样的守卫：设备、组织、区域都存在且尚无映射时才写入。 */
    private void scope(String deviceId, String orgId, String districtId, long now) {
        Timestamp at = Timestamp.from(Instant.ofEpochMilli(now));
        jdbc.update("""
                INSERT INTO device_business_scope (ops_device_id, owner_org_id, district_id, created_at, updated_at)
                SELECT d.device_id, ?, ?, ?, ?
                FROM ops_device d
                WHERE d.device_id = ?
                  AND EXISTS (SELECT 1 FROM app_org WHERE org_id = ?)
                  AND EXISTS (SELECT 1 FROM app_district WHERE district_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM device_business_scope s WHERE s.ops_device_id = d.device_id)
                """, orgId, districtId, at, at, deviceId, orgId, districtId);
    }

    private void event(String eventId, String deviceId, String type, String level, String message, long at) {
        jdbc.update("""
                INSERT INTO device_event_log (event_id,device_id,event_type,level_code,message,occurred_at,simulated)
                SELECT ?,?,?,?,?,?,TRUE WHERE NOT EXISTS (SELECT 1 FROM device_event_log WHERE event_id=?)
                """, eventId, deviceId, type, level, message, at, eventId);
    }

    // ---------------------------------------------------------------- 设备异常（工作台"设备告警"队列读取 device_incident）

    private void incidents(long now) {
        incident(1, 4, "LINK_DEGRADED", "HIGH", "PENDING", now - 40 * MINUTE, null, "链路丢包率 12.5%，连续三次状态上报标记异常");
        incident(2, 5, "DEVICE_OFFLINE", "HIGH", "PENDING", now - 35 * MINUTE, null, "超过 30 分钟未收到心跳，判定离线");
        incident(3, 12, "LINK_DEGRADED", "LOW", "PENDING", now - 20 * MINUTE, null, "信号强度 -92 dBm，低于健康阈值但仍在线");
        incident(4, 3, "LINK_DEGRADED", "MEDIUM", "PROCESSING", now - 3 * HOUR, null, "链路时延升高至 140 ms，运维人员正在排查供电与天线");
        incident(5, 10, "DEVICE_OFFLINE", "HIGH", "PROCESSING", now - 2 * HOUR, null, "设备离线，已通知现场人员到点检查");
        incident(6, 9, "LINK_DEGRADED", "MEDIUM", "PENDING_VERIFICATION", now - 5 * HOUR, null, "现场已更换网线，等待平台侧确认状态恢复");
        incident(7, 7, "STATE_UNKNOWN", "LOW", "PENDING_VERIFICATION", now - 6 * HOUR, null, "长时间无状态上报，重启指令已下发，等待恢复验证");
        incident(8, 8, "DEVICE_OFFLINE", "HIGH", "RECOVERED", now - DAY, now - DAY + 2 * HOUR, "电源故障导致离线，更换电源模块后恢复");
        incident(9, 1, "LINK_DEGRADED", "LOW", "RECOVERED", now - 2 * DAY, now - 2 * DAY + 30 * MINUTE, "短时链路抖动，自行恢复");
        incident(10, 6, "LINK_DEGRADED", "MEDIUM", "RECOVERED", now - 3 * DAY, now - 3 * DAY + 4 * HOUR, "基站侧网络维护造成丢包，维护结束后恢复");
    }

    private void incident(int seq, int deviceSeq, String type, String severity, String stage, long detectedAt, Long closedAt, String reason) {
        String id = String.format(Locale.ROOT, "seed-vol-device-incident-%02d", seq);
        jdbc.update("""
                INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,closed_at,simulated)
                SELECT ?,?,?,?,?,?,?,?,?,TRUE WHERE NOT EXISTS (SELECT 1 FROM device_incident WHERE incident_id=?)
                """, id, deviceId(deviceSeq), String.format(Locale.ROOT, "INC-0908-%03d", seq), type, severity, stage,
                detectedAt, reason, closedAt, id);
    }

    // ---------------------------------------------------------------- 重启指令与回执

    private void commands(String admin, long now) {
        // 未终结的三条不设 deadline：mock Worker 只处理 Outbox 事件，不给这三条建 Outbox，否则超时扫描会把它们改成 TIMED_OUT。
        command(1, 1, admin, "QUEUED", "演示：链路抖动后例行重启", now - 30_000L, null, null, null, null, null);
        command(2, 2, admin, "SENT", "演示：光电云台无响应", now - 2 * MINUTE, now - 20_000L, null, null, null, null);
        command(3, 6, admin, "ACCEPTED", "演示：AOA 校准后重启", now - 5 * MINUTE, now - 4 * MINUTE, null, null, "DEVICE_ACCEPTED", "设备已受理重启指令，等待执行完成");
        command(4, 8, admin, "SUCCEEDED", "演示：电源更换后重启验证", now - DAY, now - DAY + 5_000L, now - DAY + 5 * MINUTE, now - DAY + 8_000L,
                "MOCK_REBOOTED", "开发模拟适配器已生成重启完成回执");
        command(5, 3, admin, "SUCCEEDED", "演示：反制主机固件升级后重启", now - 3 * DAY, now - 3 * DAY + 4_000L, now - 3 * DAY + 5 * MINUTE, now - 3 * DAY + 9_000L,
                "MOCK_REBOOTED", "开发模拟适配器已生成重启完成回执");
        command(6, 9, admin, "FAILED", "演示：雷达异常尝试重启", now - 4 * HOUR, now - 4 * HOUR + 3_000L, now - 4 * HOUR + 5 * MINUTE, now - 4 * HOUR + 12_000L,
                "DEVICE_REJECTED", "设备返回错误码 0x13，重启未执行（模拟）");
        command(7, 5, admin, "TIMED_OUT", "演示：离线前最后一次重启尝试", now - 50 * MINUTE, now - 50 * MINUTE + 2_000L, now - 45 * MINUTE, now - 45 * MINUTE,
                "ADAPTER_TIMEOUT", "设备适配器未在截止时间前返回回执");
        command(8, 10, admin, "CANCELLED", "演示：误操作后取消", now - 2 * HOUR, null, now - 2 * HOUR + 5 * MINUTE, now - 2 * HOUR + 40_000L,
                "OPERATOR_CANCELLED", "操作员在下发前取消（模拟）");

        receipt(1, 3, "ACCEPTED", "DEVICE_ACCEPTED", now - 4 * MINUTE + 1_500L, "{\"simulated\":true,\"detail\":\"设备已受理重启指令\"}");
        receipt(2, 4, "COMPLETED", "MOCK_REBOOTED", now - DAY + 8_000L, "{\"simulated\":true,\"detail\":\"开发模拟适配器已生成重启完成回执\"}");
        receipt(3, 5, "COMPLETED", "MOCK_REBOOTED", now - 3 * DAY + 9_000L, "{\"simulated\":true,\"detail\":\"开发模拟适配器已生成重启完成回执\"}");
        receipt(4, 6, "COMPLETED", "DEVICE_ERROR", now - 4 * HOUR + 12_000L, "{\"simulated\":true,\"detail\":\"设备返回错误码 0x13，重启未执行\"}");

        event("seed-vol-device-ev-8-2", deviceId(8), "REBOOT_SUCCEEDED", "INFO", "模拟重启回执已接收，设备状态恢复在线", now - DAY + 8_000L);
        event("seed-vol-device-ev-3-2", deviceId(3), "REBOOT_SUCCEEDED", "INFO", "模拟重启回执已接收，设备状态恢复在线", now - 3 * DAY + 9_000L);
        event("seed-vol-device-ev-9-2", deviceId(9), "REBOOT_FAILED", "ERROR", "模拟重启失败：设备返回错误码 0x13，重启未执行", now - 4 * HOUR + 12_000L);
        event("seed-vol-device-ev-5-2", deviceId(5), "REBOOT_TIMED_OUT", "ERROR", "设备重启指令等待回执超时", now - 45 * MINUTE);
        event("seed-vol-device-ev-10-2", deviceId(10), "REBOOT_CANCELLED", "WARN", "重启指令已取消（模拟）", now - 2 * HOUR + 40_000L);
    }

    private void command(int seq, int deviceSeq, String admin, String status, String reason, long createdAt, Long issuedAt,
                         Long deadlineAt, Long completedAt, String resultCode, String resultDetail) {
        String id = String.format(Locale.ROOT, "seed-vol-device-cmd-%02d", seq);
        long updatedAt = completedAt != null ? completedAt : issuedAt != null ? issuedAt : createdAt;
        jdbc.update("""
                INSERT INTO device_command (command_id,command_no,device_id,requested_by,command_type,reason,status,
                    source_mode,simulated,issued_at,deadline_at,completed_at,result_code,result_detail,created_at,updated_at)
                SELECT ?,?,?,?,'REBOOT',?,?,'mock',TRUE,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM device_command WHERE command_id=?)
                """, id, String.format(Locale.ROOT, "RB-0908-%03d", seq), deviceId(deviceSeq), admin, reason, status,
                issuedAt, deadlineAt, completedAt, resultCode, resultDetail, createdAt, updatedAt, id);
    }

    /** 回执必须挂在 inbox_message 上（同 DeviceRepository.addReceipt 的来源约定）。 */
    private void receipt(int seq, int commandSeq, String kind, String deviceResultCode, long at, String payload) {
        String commandId = String.format(Locale.ROOT, "seed-vol-device-cmd-%02d", commandSeq);
        String inboxId = String.format(Locale.ROOT, "seed-vol-device-inbox-%02d", seq);
        String receiptId = String.format(Locale.ROOT, "seed-vol-device-rcpt-%02d", seq);
        jdbc.update("""
                INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at)
                SELECT ?,'mock-device-adapter',?,? WHERE NOT EXISTS (SELECT 1 FROM inbox_message WHERE inbox_id=?)
                """, inboxId, "receipt:" + commandId + ":" + kind.toLowerCase(Locale.ROOT), at, inboxId);
        jdbc.update("""
                INSERT INTO command_receipt (receipt_id,command_id,inbox_id,receipt_kind,device_result_code,occurred_at,received_at,payload)
                SELECT ?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM command_receipt WHERE receipt_id=?)
                """, receiptId, commandId, inboxId, kind, deviceResultCode, at, at, payload, receiptId);
    }

    // ---------------------------------------------------------------- 接入调测任务（每种状态各一条）

    private static final String CRITERIA_MOCK = "{\"source\":\"DEVELOPMENT_SIMULATION\",\"confirmed\":false,\"warning\":\"甲方设备协议与正式调测判据尚未确认\"}";
    private static final String CRITERIA_RETRY = "{\"source\":\"ADAPTER_RETRY_POLICY\",\"confirmed\":false}";
    private static final String CONFIGURATION = "{\"transport\":\"TCP\",\"host\":\"192.0.2.43\",\"port\":9001,\"data_format\":\"JSON\","
            + "\"charset_name\":\"UTF-8\",\"auth_mode\":\"Token\",\"timeout_millis\":3000,\"retry_count\":3}";

    private void commissions(String admin, long now) {
        // 终态任务先写：CREATED 任务通过 previous_task_id 引用 PASSED 任务，形成"重新连接"链。
        long passed = now - DAY;
        commission(6, 1, admin, "PASSED", null, 5, CONFIGURATION, CRITERIA_MOCK, results("MOCK_PASSED",
                "开发模拟流程完成，不代表真实设备验收通过", "PASSED", "PASSED", "PASSED", "PASSED"),
                passed, passed + 20_000L, passed + 45_000L, passed + 45_000L);
        events(6, passed, "CREATED", "CONNECTING", "CONNECTED", "READY", "RUNNING", "PASSED");
        long failed = now - 2 * DAY;
        commission(7, 12, admin, "FAILED", null, 5, CONFIGURATION, CRITERIA_MOCK, results("MOCK_FAILED",
                "接口数据校验未通过：上报字段缺少坐标（模拟）", "PASSED", "FAILED", "FAILED", "PASSED"),
                failed, failed + 20_000L, failed + 50_000L, failed + 50_000L);
        events(7, failed, "CREATED", "CONNECTING", "CONNECTED", "READY", "RUNNING", "FAILED");
        long untestable = now - 3 * DAY;
        commission(8, 5, admin, "UNTESTABLE", null, 5, CONFIGURATION, CRITERIA_RETRY,
                "{\"result_code\":\"ADAPTER_RETRY_EXHAUSTED\",\"detail\":\"设备离线，适配器重试耗尽，无法得出调测结论\",\"items\":[]}",
                untestable, untestable + 20_000L, untestable + 10 * MINUTE, untestable + 10 * MINUTE);
        events(8, untestable, "CREATED", "CONNECTING", "CONNECTED", "READY", "RUNNING", "UNTESTABLE");
        long cancelled = now - 4 * DAY;
        commission(9, 10, admin, "CANCELLED", null, 2, null, null, null, cancelled, cancelled + 15_000L, cancelled + 3 * MINUTE, cancelled + 3 * MINUTE);
        events(9, cancelled, "CREATED", "CONNECTING", "CANCELLED");

        String previous = commissionId(6);
        commission(1, 1, admin, "CREATED", previous, 0, null, null, null, now - 5 * MINUTE, null, null, now - 5 * MINUTE);
        events(1, now - 5 * MINUTE, "CREATED");
        commission(2, 2, admin, "CONNECTING", null, 1, null, null, null, now - 4 * MINUTE, now - 4 * MINUTE + 10_000L, null, now - 4 * MINUTE + 10_000L);
        events(2, now - 4 * MINUTE, "CREATED", "CONNECTING");
        commission(3, 6, admin, "CONNECTED", null, 2, null, null, null, now - 30 * MINUTE, now - 30 * MINUTE + 10_000L, null, now - 30 * MINUTE + 20_000L);
        events(3, now - 30 * MINUTE, "CREATED", "CONNECTING", "CONNECTED");
        commission(4, 3, admin, "READY", null, 3, CONFIGURATION, null, null, now - HOUR, now - HOUR + 10_000L, null, now - HOUR + 30_000L);
        events(4, now - HOUR, "CREATED", "CONNECTING", "CONNECTED", "READY");
        commission(5, 8, admin, "RUNNING", null, 4, CONFIGURATION, null, null, now - 10 * MINUTE, now - 10 * MINUTE + 10_000L, null, now - 10 * MINUTE + 40_000L);
        events(5, now - 10 * MINUTE, "CREATED", "CONNECTING", "CONNECTED", "READY", "RUNNING");
    }

    private static String commissionId(int seq) { return String.format(Locale.ROOT, "seed-vol-device-ct-%02d", seq); }

    private void commission(int seq, int deviceSeq, String admin, String status, String previousTaskId, long version,
                            String configurationJson, String criteriaSnapshot, String resultsJson,
                            long createdAt, Long startedAt, Long finishedAt, long updatedAt) {
        String id = commissionId(seq);
        jdbc.update("""
                INSERT INTO commission_task (commission_id,commission_no,previous_task_id,device_id,requested_by,status,
                    configuration_json,criteria_snapshot,results_json,source_mode,simulated,version,started_at,finished_at,created_at,updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,'mock',TRUE,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM commission_task WHERE commission_id=?)
                """, id, String.format(Locale.ROOT, "CT-0908-%03d", seq), previousTaskId, deviceId(deviceSeq), admin, status,
                configurationJson, criteriaSnapshot, resultsJson, version, startedAt, finishedAt, createdAt, updatedAt, id);
    }

    /** 步骤日志：按阶段顺序每 10 秒一条；终态取相应级别。 */
    private void events(int commissionSeq, long from, String... stages) {
        for (int i = 0; i < stages.length; i++) {
            String stage = stages[i];
            String level = switch (stage) {
                case "FAILED", "UNTESTABLE" -> "ERROR";
                case "CANCELLED" -> "WARN";
                default -> "INFO";
            };
            String message = switch (stage) {
                case "CREATED" -> "调测任务已创建，等待建立连接";
                case "CONNECTING" -> "正在通过开发模拟适配器建立逻辑连接";
                case "CONNECTED" -> "开发模拟适配器已建立逻辑连接";
                case "READY" -> "连接配置已冻结，等待开始调测";
                case "RUNNING" -> "调测流程执行中";
                case "PASSED" -> "开发模拟流程完成，不代表真实设备验收通过";
                case "FAILED" -> "接口数据校验未通过：上报字段缺少坐标（模拟）";
                case "UNTESTABLE" -> "设备离线，适配器重试耗尽，无法得出调测结论";
                case "CANCELLED" -> "调测任务已取消";
                default -> stage;
            };
            String eventId = String.format(Locale.ROOT, "seed-vol-device-cte-%02d-%d", commissionSeq, i);
            jdbc.update("""
                    INSERT INTO commission_task_event (event_id,commission_id,stage_code,level_code,message,occurred_at,simulated)
                    SELECT ?,?,?,?,?,?,TRUE WHERE NOT EXISTS (SELECT 1 FROM commission_task_event WHERE event_id=?)
                    """, eventId, commissionId(commissionSeq), stage, level, message, from + i * 10_000L, eventId);
        }
    }

    /** 与 MockAdapter.commission 的分项结构一致（code/label/result/value/unit/basis），供报告页解析。 */
    private static String results(String code, String detail, String transport, String payload, String coordinate, String clockResult) {
        return "{\"result_code\":\"" + code + "\",\"detail\":\"" + detail + "\",\"items\":["
                + item("TRANSPORT", "通信连通性", transport, "reachable")
                + "," + item("PAYLOAD", "接口数据校验", payload, "PASSED".equals(payload) ? "schema accepted" : "missing longitude/latitude")
                + "," + item("COORDINATE", "坐标字段检查", coordinate, "PASSED".equals(coordinate) ? "WGS-84" : "absent")
                + "," + item("CLOCK", "时钟字段检查", clockResult, "timestamp present") + "]}";
    }

    private static String item(String code, String label, String result, String value) {
        return "{\"code\":\"" + code + "\",\"label\":\"" + label + "\",\"result\":\"" + result + "\",\"value\":\"" + value
                + "\",\"unit\":null,\"basis\":\"DEVELOPMENT_SIMULATION\"}";
    }

    private record Seed(int seq, String name, String typeCode, String typeName, String channel, String region,
                        double lon, double lat, String connectivity, String health, boolean crossScope) { }
}
