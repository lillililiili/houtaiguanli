package com.uav.lowaltitude.modules.device.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.device.NetworkTargetPolicy;
import com.uav.lowaltitude.integration.device.ProtocolException;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.modules.device.infrastructure.IntegrationSourceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class IntegrationSourceService {

    private final IntegrationSourceRepository repository;
    private final DeviceAccessPolicy access;
    private final DeviceAdapterRegistry adapters;
    private final NetworkTargetPolicy networkPolicy;
    private final AppClock clock;
    private final AuditService audit;
    private final EnvironmentCredentialResolver credentials;

    public IntegrationSourceService(IntegrationSourceRepository repository, DeviceAccessPolicy access,
                                    DeviceAdapterRegistry adapters, NetworkTargetPolicy networkPolicy,
                                    AppClock clock, AuditService audit, EnvironmentCredentialResolver credentials) {
        this.repository = repository;
        this.access = access;
        this.adapters = adapters;
        this.networkPolicy = networkPolicy;
        this.clock = clock;
        this.audit = audit;
        this.credentials = credentials;
    }

    public SourcePage list(int page, int size) {
        access.requireInterfacesRead();
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        return new SourcePage(repository.list((safePage - 1) * safeSize, safeSize).stream().map(this::source).toList(),
                safePage, safeSize, repository.count());
    }

    public Source get(String id) {
        access.requireInterfacesRead();
        return required(id);
    }

    @Transactional
    public Source create(Mutation mutation) {
        AuthUser user = access.requireInterfacesOperate();
        validate(mutation);
        long now = clock.nowMillis();
        String id = UUID.randomUUID().toString();
        Map<String, Object> values = values(mutation, now);
        values.put("source_id", id);
        try { repository.insert(values); }
        catch (DataIntegrityViolationException ex) { throw conflict("来源编码已存在"); }
        audit.record(user.userId(), user.account(), "integration_source_create", "integration_source", id,
                mutation.sourceCode(), null);
        return required(id);
    }

    @Transactional
    public Source update(String id, long version, Mutation mutation) {
        AuthUser user = access.requireInterfacesOperate();
        validate(mutation);
        Source before = required(id);
        if (DeviceProtocolCodes.LINGYUN_MQTT_V8_6.equals(before.protocolCode())
                || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(before.protocolCode()))
            throw bad("MQTT_REGISTRATION_REQUIRED", "MQTT 接入请在设备登记和 MQTT 连接配置中维护");
        if (before.enabled() && !before.protocolCode().equals(mutation.protocolCode()))
            throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE", "请先停用来源再修改协议");
        try {
            if (repository.update(id, version, values(mutation, clock.nowMillis())) != 1) throw versionConflict();
        } catch (DataIntegrityViolationException ex) { throw conflict("来源编码已存在"); }
        audit.record(user.userId(), user.account(), "integration_source_update", "integration_source", id,
                mutation.sourceCode(), null);
        return required(id);
    }

    @Transactional
    public Source setEnabled(String id, long version, boolean enabled, String reason) {
        AuthUser user = access.requireInterfacesOperate();
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "reason 长度必须为 2–500 个字符");
        Source source = required(id);
        if (DeviceProtocolCodes.LINGYUN_MQTT_V8_6.equals(source.protocolCode())
                || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(source.protocolCode()))
            throw bad("MQTT_REGISTRATION_REQUIRED", "MQTT 接入请在设备登记和 MQTT 连接配置中维护");
        if (enabled) validateEnable(source);
        if (repository.setEnabled(id, version, enabled, clock.nowMillis()) != 1) throw versionConflict();
        Source after = required(id);
        syncStandardRadar(after, enabled, clock.nowMillis());
        audit.record(user.userId(), user.account(), enabled ? "integration_source_enable" : "integration_source_disable",
                "integration_source", id, reason.trim(), null);
        return after;
    }

    @Transactional
    public Source insertLive(Mutation mutation) {
        AuthContext.require();
        validate(mutation);
        long now = clock.nowMillis();
        String id = UUID.randomUUID().toString();
        Map<String, Object> values = values(mutation, now);
        values.put("source_id", id);
        try { repository.insert(values); }
        catch (DataIntegrityViolationException ex) { throw conflict("来源编码已存在"); }
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(mutation.protocolCode()))
            repository.ensureStandardLiveRadar(mutation.sourceCode().trim(), mutation.name().trim(), "3.0.0", false, now);
        AuthUser user = AuthContext.require();
        audit.record(user.userId(), user.account(), "integration_source_create", "integration_source", id,
                mutation.sourceCode(), null);
        return required(id);
    }

    @Transactional
    public Source activate(String id, long version, String reason) {
        AuthContext.require();
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "reason 长度必须为 2–500 个字符");
        Source source = required(id);
        validateEnable(source);
        if (repository.setEnabled(id, version, true, clock.nowMillis()) != 1) throw versionConflict();
        Source after = required(id);
        syncStandardRadar(after, true, clock.nowMillis());
        AuthUser user = AuthContext.require();
        audit.record(user.userId(), user.account(), "integration_source_enable", "integration_source", id, reason.trim(), null);
        return after;
    }

    @Transactional
    public void replaceNetwork(String sourceId, String allowedCidrs, String credentialRef) {
        AuthContext.require();
        Source before = required(sourceId);
        Mutation mutation = new Mutation(before.sourceCode(), before.name(), before.protocolCode(),
                before.protocolVersion(), credentialRef, allowedCidrs);
        validate(mutation);
        if (repository.update(sourceId, before.version(), values(mutation, clock.nowMillis())) != 1) throw versionConflict();
        Source after = required(sourceId);
        if (after.enabled()) validateEnable(after);
    }

    @Transactional
    public void syncEnabled(String sourceId, boolean enabled, String reason) {
        AuthContext.require();
        Source source = required(sourceId);
        if (source.enabled() == enabled) return;
        if (enabled) validateEnable(source);
        if (repository.setEnabled(sourceId, source.version(), enabled, clock.nowMillis()) != 1) throw versionConflict();
        syncStandardRadar(source, enabled, clock.nowMillis());
        audit.record(AuthContext.require().userId(), AuthContext.require().account(),
                enabled ? "integration_source_enable" : "integration_source_disable",
                "integration_source", sourceId, reason == null ? "" : reason.trim(), null);
    }

    private void syncStandardRadar(Source source, boolean enabled, long now) {
        if (source == null || !DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(source.protocolCode())) return;
        if (source.sourceCode() == null || source.name() == null) return;
        repository.ensureStandardLiveRadar(source.sourceCode(), source.name(), source.protocolVersion(), enabled, now);
    }

    private void validateEnable(Source source) {
        if (!adapters.supports(SourceMode.live, source.protocolCode()))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROTOCOL_UNSUPPORTED", "来源协议适配器不可用");
        if (source.allowedCidrs() == null || source.allowedCidrs().isBlank())
            throw bad("NETWORK_TARGET_FORBIDDEN", "启用 live 来源前必须配置 CIDR 白名单");
        List<Map<String, Object>> devices = repository.assignedDevices(source.sourceId());
        if (devices.isEmpty()) throw bad("PROTOCOL_NOT_CONFIGURED", "来源尚未关联设备");
        for (Map<String, Object> device : devices) {
            if (!bool(device, "enabled")) continue;
            String host = text(device, "host");
            Integer port = integer(device, "port");
            if (!"TCP".equalsIgnoreCase(text(device, "transport")) || host == null || port == null)
                throw bad("PROTOCOL_NOT_CONFIGURED", "设备 " + text(device, "device_no") + " 未完整配置 TCP 地址");
            if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(source.protocolCode())
                    && text(device, "radar_profile_device_id") == null)
                throw bad("PROTOCOL_NOT_CONFIGURED", "雷达设备 " + text(device, "device_no") + " 缺少协议配置");
            if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(source.protocolCode())
                    && text(device, "recognition_code_ref") != null) {
                try { credentials.resolve(text(device, "recognition_code_ref")); }
                catch (ProtocolException ex) { throw bad(ex.code(), "雷达设备 " + text(device, "device_no") + "：" + ex.getMessage()); }
            }
            if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(source.protocolCode())
                    && text(device, "counter_profile_device_id") == null)
                throw bad("PROTOCOL_NOT_CONFIGURED", "反制设备 " + text(device, "device_no") + " 缺少协议配置");
            try { networkPolicy.resolveAllowed(host, source.allowedCidrs()); }
            catch (ProtocolException ex) { throw bad(ex.code(), "设备 " + text(device, "device_no") + "：" + ex.getMessage()); }
        }
    }

    private void validate(Mutation mutation) {
        if (mutation == null || blank(mutation.sourceCode()) == null || blank(mutation.name()) == null)
            throw bad("VALIDATION_ERROR", "source_code 和 name 必填");
        if (!List.of(DeviceProtocolCodes.RADAR_TCP_V3_0_0,
                DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0).contains(mutation.protocolCode()))
            throw bad("PROTOCOL_UNSUPPORTED", "仅支持本轮核准的雷达与四通道反制协议");
        String expectedVersion = DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(mutation.protocolCode()) ? "3.0.0" : "2.0";
        if (blank(mutation.protocolVersion()) != null && !expectedVersion.equals(mutation.protocolVersion().trim()))
            throw bad("PROTOCOL_MODEL_UNVERIFIED", "协议代码与版本不匹配");
        if (mutation.credentialRef() != null && !mutation.credentialRef().isBlank()
                && (!mutation.credentialRef().startsWith("env:") || mutation.credentialRef().length() <= 4))
            throw bad("VALIDATION_ERROR", "credential_ref 首版仅支持 env:变量名");
    }

    private Map<String, Object> values(Mutation mutation, long now) {
        Map<String, Object> values = new HashMap<>();
        values.put("source_code", mutation.sourceCode().trim());
        values.put("name", mutation.name().trim());
        values.put("protocol_code", mutation.protocolCode());
        values.put("protocol_version", DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(mutation.protocolCode()) ? "3.0.0" : "2.0");
        values.put("credential_ref", blank(mutation.credentialRef()));
        values.put("allowed_cidrs", blank(mutation.allowedCidrs()));
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    private Source required(String id) {
        Map<String, Object> row = repository.find(id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "INTEGRATION_SOURCE_NOT_FOUND", "接入来源不存在");
        return source(row);
    }

    private Source source(Map<String, Object> row) {
        return new Source(text(row, "source_id"), text(row, "source_code"), text(row, "name"),
                text(row, "protocol_code"), text(row, "protocol_version"), text(row, "source_mode"),
                bool(row, "enabled"), text(row, "credential_ref"), text(row, "allowed_cidrs"),
                bool(row, "simulated"), longNumber(row, "version"), longNumber(row, "device_count"),
                longNumber(row, "created_at"), longNumber(row, "updated_at"));
    }

    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "INTEGRATION_SOURCE_CONFLICT", message); }
    private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "来源已被更新，请刷新后重试"); }
    private static String blank(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static boolean bool(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value)); }
    private static Integer integer(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Number n ? n.intValue() : null; }
    private static long longNumber(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Number n ? n.longValue() : 0; }

    public record SourcePage(List<Source> items, int page, int size, long total) { }
    public record Source(String sourceId, String sourceCode, String name, String protocolCode,
                         String protocolVersion, String sourceMode, boolean enabled, String credentialRef,
                         String allowedCidrs, boolean simulated, long version, long deviceCount,
                         long createdAt, long updatedAt) { }
    public record Mutation(String sourceCode, String name, String protocolCode, String protocolVersion,
                           String credentialRef, String allowedCidrs) { }
}
