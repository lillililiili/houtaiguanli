package com.uav.lowaltitude.modules.flightavoidance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.device.application.RetiredDeviceNumbers;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;
import com.uav.lowaltitude.modules.flightavoidance.infrastructure.AdsbReceiverRepository;
import com.uav.lowaltitude.modules.flightavoidance.infrastructure.FlightAvoidanceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 航班接收机登记与航班数据接收。
 *
 * 真实接收机的数据格式各厂家不同，要按厂家说明书接入；在那之前后台登记为待接入（停用），不参与检查。
 * 设备模拟器的接收机只在本地测试环境（local+qa）可用，数据标为模拟。
 */
@Service
public class AdsbReceiverService {
    /** 平时的航班数据只留最近 10 分钟。 */
    private static final long KEEP_MILLIS = 10 * 60_000L;

    private final AdsbReceiverRepository receivers;
    private final FlightAvoidanceRepository flights;
    private final DeviceRepository devices;
    private final DeviceAccessPolicy permissions;
    private final AccessService access;
    private final RetiredDeviceNumbers retiredNumbers;
    private final SimulationPolicy simulation;
    private final AuditService audit;
    private final AppClock clock;

    public AdsbReceiverService(AdsbReceiverRepository receivers, FlightAvoidanceRepository flights, DeviceRepository devices,
            DeviceAccessPolicy permissions, AccessService access, RetiredDeviceNumbers retiredNumbers, SimulationPolicy simulation,
            AuditService audit, AppClock clock) {
        this.receivers = receivers; this.flights = flights; this.devices = devices; this.permissions = permissions;
        this.access = access; this.retiredNumbers = retiredNumbers; this.simulation = simulation; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReceiverProfile get(String id) { permissions.requireDevicesRead(); return visible(id); }

    @Transactional
    public ReceiverProfile create(ReceiverInput input) {
        var actor = permissions.requireDevicesOperate();
        access.requireTuple(input.ownerOrgId().trim(), input.districtId().trim());
        if (!receivers.activeScope(input.ownerOrgId().trim(), input.districtId().trim())) throw bad("单位或区域已停用");
        String deviceNo = input.deviceNo().trim();
        retiredNumbers.reject(deviceNo);
        String id = UUID.randomUUID().toString();
        long now = clock.nowMillis();
        try {
            receivers.insert(id, deviceNo, input.name().trim(), clean(input.vendor()), clean(input.model()), clean(input.address()),
                    input.ownerOrgId().trim(), input.districtId().trim(), coordinate(input.longitude()), coordinate(input.latitude()),
                    input.receiveRangeKm(), false, now);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NO_CONFLICT", "设备编号已存在，请检查设备台账");
        }
        devices.addEvent(UUID.randomUUID().toString(), id, "CATALOG_CREATED", "INFO", "航班接收机已登记，待接入", now, false);
        audit.record(actor.userId(), actor.account(), "adsb_receiver_create", "device", id, "登记待接入航班接收机", null);
        return visible(id);
    }

    @Transactional
    public ReceiverProfile update(String id, ReceiverInput input) {
        var actor = permissions.requireDevicesOperate();
        ReceiverProfile before = visible(id);
        if (input.version() == null) throw bad("版本必填");
        if (!before.deviceNo().equals(input.deviceNo().trim()) || !before.ownerOrgId().equals(input.ownerOrgId().trim())
                || !before.districtId().equals(input.districtId().trim()))
            throw bad("设备编号和所属范围不可在此修改");
        if (receivers.update(id, input.name().trim(), clean(input.vendor()), clean(input.model()), clean(input.address()),
                coordinate(input.longitude()), coordinate(input.latitude()), input.receiveRangeKm(), input.version(),
                clock.nowMillis()) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "设备已被修改，请重新加载后保存");
        audit.record(actor.userId(), actor.account(), "adsb_receiver_update", "device", id, "更新航班接收机档案", null);
        return visible(id);
    }

    /* ---- 设备模拟器（local+qa） ---- */

    @Transactional
    public ReceiverProfile prepareSimulated(SimulatedReceiverInput input) {
        requireSimulation();
        var actor = AuthContext.require();
        String org = input.ownerOrgId().trim(), district = input.districtId().trim(), deviceNo = input.deviceNo().trim();
        if (!receivers.activeScope(org, district))
            throw new ApiException(HttpStatus.CONFLICT, "QA_SCOPE_REQUIRED", "所选单位或区县不存在或已停用");
        long now = clock.nowMillis();
        var existing = receivers.byNumber(deviceNo);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (!AdsbReceiverRepository.TYPE.equals(row.get("device_type_code")) || !Boolean.TRUE.equals(row.get("simulated"))
                    || row.get("deleted_at") != null || !org.equals(row.get("owner_org_id")) || !district.equals(row.get("district_id")))
                throw new ApiException(HttpStatus.CONFLICT, "QA_DEVICE_CONFLICT", "已有同编号设备，来源、归属或类型不是这台模拟航班接收机");
            String id = String.valueOf(row.get("device_id"));
            receivers.refreshSimulated(id, input.name().trim(), coordinate(input.longitude()), coordinate(input.latitude()),
                    input.receiveRangeKm(), now);
            return receivers.find(id);
        }
        retiredNumbers.reject(deviceNo);
        String id = UUID.randomUUID().toString();
        receivers.insert(id, deviceNo, input.name().trim(), "设备模拟器", null, null, org, district, coordinate(input.longitude()),
                coordinate(input.latitude()), input.receiveRangeKm(), true, now);
        devices.addEvent(UUID.randomUUID().toString(), id, "CATALOG_CREATED", "INFO", "设备模拟器登记模拟航班接收机", now, true);
        audit.record(actor.userId(), actor.account(), "adsb_receiver_simulated", "device", id, "登记模拟航班接收机（设备模拟器）", null);
        return receivers.find(id);
    }

    /** 模拟航班接收机送来的一批航班。空列表表示在线但附近没有航班。 */
    @Transactional
    public int receiveSimulated(String deviceId, SimulatedReport report) {
        requireSimulation();
        ReceiverProfile receiver = receivers.find(deviceId);
        if (receiver == null || !receiver.simulated())
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "模拟航班接收机不存在");
        if (!receiver.enabled()) throw new ApiException(HttpStatus.CONFLICT, "DEVICE_DISABLED", "模拟航班接收机已停用");
        if (!flights.lockReceiver(deviceId)) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "模拟航班接收机不存在");
        long now = clock.nowMillis();
        int accepted = 0;
        for (ReportedAircraft a : report.aircraft()) {
            // 只收接收范围内、时间不在将来的数据。
            if (a.observedAt() > now + 5_000) continue;
            if (receiver.longitude() != null && FlightCheck.distanceKm(receiver.longitude().doubleValue(), receiver.latitude().doubleValue(),
                    a.longitude(), a.latitude()) > receiver.receiveRangeKm()) continue;
            flights.upsertAircraft(deviceId, new FlightCheck.Aircraft(a.icao24().toUpperCase(), clean(a.callsign()), clean(a.airline()),
                    a.longitude(), a.latitude(), a.altitudeM(), a.groundSpeedMps(), a.trackDeg(), a.observedAt()), now, true);
            accepted++;
        }
        flights.touchReceiver(deviceId, now);
        flights.pruneAircraft(now - KEEP_MILLIS);
        return accepted;
    }

    /** 模拟流程结束：停用模拟接收机，后面别的流程的反制不再受它影响。 */
    @Transactional
    public boolean releaseSimulated(String deviceId) {
        requireSimulation();
        ReceiverProfile receiver = receivers.find(deviceId);
        if (receiver == null || !receiver.simulated())
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "模拟航班接收机不存在");
        boolean changed = receivers.disableSimulated(deviceId, clock.nowMillis()) == 1;
        if (changed) {
            var actor = AuthContext.require();
            audit.record(actor.userId(), actor.account(), "adsb_receiver_simulated_release", "device", deviceId, "模拟航班接收机已停用（模拟结束）", null);
        }
        return changed;
    }

    private void requireSimulation() {
        if (!simulation.allowed())
            throw new ApiException(HttpStatus.FORBIDDEN, "SIMULATION_FORBIDDEN", "只有本地测试环境可以使用设备模拟器");
        access.require("interfaces.op");
        access.require("devices.auth");
        if (!"ALL".equals(AuthContext.require().scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN, "QA_GLOBAL_SCOPE_REQUIRED", "本地测试设备准备需要全局管理范围");
    }

    private ReceiverProfile visible(String id) {
        ReceiverProfile row = receivers.find(id);
        if (row == null || !access.canAccessTuple(row.ownerOrgId(), row.districtId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在或不在授权范围内");
        return row;
    }

    private static BigDecimal coordinate(BigDecimal value) { return value.setScale(7, RoundingMode.HALF_UP); }
    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
