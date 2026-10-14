package com.uav.lowaltitude.modules.device.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 恢复设备模拟器自己登记的回放模拟设备（2026-10-14 用户确认方案 A）：已退役的撤销删除，已停用的重新启用。
 * 用的是同一条设备记录，历史、来源和绑定都不动，并留设备事件和审计。只认经回放 MQTT 连接登记、
 * 标明模拟的设备，且接入身份要和本次登记逐项一致；真实设备删除后的规则不变，不能恢复。
 */
@Service
public class SimulatedDeviceRestoreService {
    private final DeviceAccessPolicy permissions;
    private final DeviceRepository devices;
    private final MqttRepository lingyun;
    private final EoEdgeRepository edges;
    private final MqttConfigurationService connections;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;

    public SimulatedDeviceRestoreService(DeviceAccessPolicy permissions, DeviceRepository devices, MqttRepository lingyun,
                                         EoEdgeRepository edges, MqttConfigurationService connections,
                                         IdempotencyGuard idempotency, AuditService audit, AppClock clock) {
        this.permissions = permissions;
        this.devices = devices;
        this.lingyun = lingyun;
        this.edges = edges;
        this.connections = connections;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 返回设备 ID。设备本来就在用时什么也不改，原样返回。没有这个编号的设备（或不在数据范围内）返回 null：
     * 没有可恢复的，调用方接着新登记。模拟器每次新登记前都先问一次，这不算失败，不写失败审计。
     */
    @Transactional
    public String restore(RestoreRequest request, String key) {
        AuthUser user = permissions.requireDevicesOperate();
        String deviceNo = required(request.deviceNo(), 64, "设备编号");
        String brokerId = required(request.brokerId(), 36, "MQTT 连接");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 2 || reason.length() > 500) throw bad("恢复原因长度必须为 2–500 个字符");
        // 锁序与启停设备一致：先连接，后设备。
        MqttConfiguration.Broker broker = lingyun.broker(brokerId, true);
        Map<String, Object> row = devices.lockByDeviceNoIncludingRetired(deviceNo);
        if (row == null || !devices.canDeleteInScope(text(row, "device_id"), user.userId(), user.scopeMode())) return null;
        String id = text(row, "device_id");
        String label = "设备 " + deviceNo + "（" + text(row, "name") + "）";
        if (!"replay".equals(text(row, "source_mode")) || !Boolean.TRUE.equals(row.get("simulated")))
            throw notRestorable(label + "不是设备模拟器登记的模拟设备，不能恢复；真实设备删除后，请用新的设备编号重新接入");
        EoEdgeConfiguration.Binding eo = edges.binding(id, true);
        MqttConfiguration.Binding mqtt = eo == null ? lingyun.binding(id, true) : null;
        if (eo == null && mqtt == null)
            throw notRestorable(label + "不是经 MQTT 回放连接登记的模拟设备，不能恢复");
        List<String> mismatches = mismatches(request, eo, mqtt);
        if (!mismatches.isEmpty())
            throw notRestorable(label + "的接入身份和这次登记的不一致（" + String.join("、", mismatches) + "），不能恢复");
        boolean retired = row.get("deleted_at") != null;
        boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
        if (!retired && enabled) return id;
        if (broker == null || !"replay".equals(broker.sourceMode()) || !broker.enabled()
                || !Objects.equals(broker.ownerOrgId(), text(row, "owner_org_id"))
                || !Objects.equals(broker.districtId(), text(row, "district_id")))
            throw notRestorable("当前模拟连接不存在、没有启用、不是回放连接，或和" + label + "不在同一单位区域，不能恢复");
        if (mqtt != null) DeviceCatalogTypes.requireRegistrable(mqtt.deviceTypeAbbr());
        connections.validateConnection(broker);
        long version = ((Number) row.get("version")).longValue();
        idempotency.claim(key, "device.restore-simulated:" + id + ":" + version + ":" + reason);
        long now = clock.nowMillis();
        if (retired) {
            if (devices.restoreRetiredSimulated(id, version, now) != 1) throw versionConflict();
            version++;
        }
        if (!enabled) {
            int changed = eo != null ? edges.enableDevice(eo, version, true, now) : lingyun.enableDevice(mqtt, version, true, now);
            if (changed != 1) throw versionConflict();
        }
        String before = retired ? "已退役" : "已停用";
        devices.addEvent(UUID.randomUUID().toString(), id, "CATALOG_RESTORED", "INFO",
                "模拟设备已恢复（原为" + before + "）：" + reason, now, true);
        audit.record(user.userId(), user.account(), "device_restore", "device", id,
                "恢复" + before + "的模拟设备 " + deviceNo + "：" + reason, null);
        return id;
    }

    private static List<String> mismatches(RestoreRequest request, EoEdgeConfiguration.Binding eo, MqttConfiguration.Binding mqtt) {
        List<String> result = new ArrayList<>();
        String protocol = eo != null ? EoEdgeEnvelope.PROTOCOL : LingyunEnvelope.PROTOCOL;
        differs(result, "协议", request.protocolCode(), protocol);
        differs(result, "MQTT 连接", request.brokerId(), eo != null ? eo.brokerId() : mqtt.brokerId());
        differs(result, "外部设备编号", request.externalDeviceId(), eo != null ? eo.externalDeviceId() : mqtt.externalDeviceId());
        differs(result, "来源模式", request.sourceMode(), eo != null ? eo.sourceMode() : mqtt.sourceMode());
        if (eo != null) differs(result, "光电边端编号", request.edgeId(), eo.edgeId());
        else {
            differs(result, "提供方", request.providerCode(), mqtt.providerCode());
            differs(result, "设备类型", request.deviceTypeAbbr(), mqtt.deviceTypeAbbr());
        }
        return result;
    }

    /** 请求里写了的身份字段必须和已有绑定一致；没写的不比。 */
    private static void differs(List<String> result, String label, String requested, String actual) {
        if (requested != null && !requested.equals(actual)) result.add(label);
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim()))
            throw bad(label + "必填，且不能超长或带首尾空格");
        return value;
    }

    private static String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : String.valueOf(row.get(key));
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiException notRestorable(String message) {
        return new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_RESTORABLE", message);
    }

    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "设备已被更新，请刷新后重试");
    }

    /** 与设备登记同一组身份字段（snake_case 由全局配置转换）；原因必填。 */
    public record RestoreRequest(String deviceNo, String brokerId, String protocolCode, String externalDeviceId,
                                 String providerCode, String deviceTypeAbbr, String edgeId, String sourceMode,
                                 String reason) { }
}
