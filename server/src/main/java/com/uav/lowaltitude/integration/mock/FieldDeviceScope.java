package com.uav.lowaltitude.integration.mock;

import java.util.Collection;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 现场只有融合感知箱（雷达、光电、反制）、TDOA、5G-A 和气象设备（2026-10-05 业务确认）。
 * 早期本地样例登记过 AOA、RemoteID、协议破解、诱骗、干扰、驱鸟炮和"融合终端"，已经从样例里拿掉；
 * 已经建好的本地库里这些样例设备在启动时打上删除标记（只动这里点名的样例编号，可用 SQL 清 deleted_at 恢复）。
 */
final class FieldDeviceScope {
    private FieldDeviceScope() { }

    static int retire(JdbcTemplate jdbc, Collection<String> deviceNos, long now) {
        int retired = 0;
        for (String deviceNo : deviceNos) {
            for (String deviceId : jdbc.queryForList(
                    "SELECT device_id FROM ops_device WHERE device_no=? AND deleted_at IS NULL", String.class, deviceNo)) {
                retired += jdbc.update("UPDATE ops_device SET enabled=FALSE,deleted_at=?,updated_at=?,version=version+1 WHERE device_id=? AND deleted_at IS NULL",
                        now, now, deviceId);
                jdbc.update("INSERT INTO device_event_log (event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,TRUE)",
                        UUID.randomUUID().toString(), deviceId, "CATALOG_DELETED", "INFO", "现场无此类设备，本地样例已移除", now);
            }
        }
        return retired;
    }
}
