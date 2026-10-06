package com.uav.lowaltitude.modules.integrationconfig.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalObservationSimulatorDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalObservationSimulatorRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalObservationSimulatorRepository.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Receives explicit local simulator inputs and routes normalized target frames
 * into the existing replay/fusion inbox. No seed data is created here.
 */
@Service
@Profile(SimulationPolicy.PROFILE)
public class LocalObservationSimulatorService {
    private static final String NORMALIZED_PREFIX = "sim-normalized:";

    private final DeviceAccessPolicy interfaces;
    private final AccessService access;
    private final LocalObservationSimulatorRepository repository;
    private final FusionInboxRepository inbox;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;

    public LocalObservationSimulatorService(DeviceAccessPolicy interfaces, AccessService access,
            LocalObservationSimulatorRepository repository, FusionInboxRepository inbox,
            ObjectMapper json, AppClock clock, AuditService audit) {
        this.interfaces = interfaces;
        this.access = access;
        this.repository = repository;
        this.inbox = inbox;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public RegisteredDevice registerObservationDevice(ObservationDeviceInput input) {
        AuthUser actor = interfaces.requireInterfacesOperate();
        repository.lockActor(actor.userId());
        access.requireTuple(input.ownerOrgId(), input.districtId());
        validateScope(input.ownerOrgId(), input.districtId());
        validateCoordinate(input.longitude(), input.latitude());
        String hash = hash(input);
        Registration previous = repository.registration(input.messageId());
        if (previous != null) {
            if (!hash.equals(previous.requestHash())) throw conflict("同一消息编号的观测源资料已变化，请使用新编号");
            return new RegisteredDevice(previous.sourceId(), previous.deviceId(), previous.messageId());
        }
        String sourceId = stableId("SIM_NORMALIZED_SOURCE:", input.messageId());
        String deviceId = stableId("SIM_NORMALIZED_DEVICE:", input.messageId());
        repository.insertObservationRegistration(sourceId, deviceId, input.messageId(), input.ownerOrgId(),
                input.districtId(), hash, "sim-normalized-" + sourceId, input.name(), point(input.longitude(), input.latitude()), clock.nowMillis());
        audit.record(actor.userId(), actor.account(), "local_observation_source_register", "device", deviceId,
                "登记规范化模拟观测源；单位=" + input.ownerOrgId() + "；区域=" + input.districtId(), null);
        return new RegisteredDevice(sourceId, deviceId, input.messageId());
    }

    @Transactional
    public RegisteredDevice registerWeatherDevice(WeatherDeviceInput input) {
        AuthUser actor = interfaces.requireInterfacesOperate();
        repository.lockActor(actor.userId());
        access.requireTuple(input.ownerOrgId(), input.districtId());
        validateScope(input.ownerOrgId(), input.districtId());
        validateCoordinate(input.longitude(), input.latitude());
        String hash = hash(input);
        WeatherRegistration previous = repository.weatherRegistration(input.messageId());
        if (previous != null) {
            if (!hash.equals(previous.requestHash())) throw conflict("同一消息编号的气象设备资料已变化，请使用新编号");
            return new RegisteredDevice(previous.sourceId(), previous.deviceId(), previous.messageId());
        }
        String sourceId = stableId("SIM_WEATHER_SOURCE:", input.messageId());
        String deviceId = stableId("SIM_WEATHER_DEVICE:", input.messageId());
        repository.insertWeatherRegistration(sourceId, deviceId, input.messageId(), input.ownerOrgId(), input.districtId(),
                hash, "sim-weather-" + sourceId, input.name(), input.deviceNo(), point(input.longitude(), input.latitude()), clock.nowMillis());
        audit.record(actor.userId(), actor.account(), "local_weather_device_register", "device", deviceId,
                "登记气象模拟设备；单位=" + input.ownerOrgId() + "；区域=" + input.districtId(), null);
        return new RegisteredDevice(sourceId, deviceId, input.messageId());
    }

    @Transactional
    public AcceptedInput acceptTargetObservations(TargetObservationsInput input) {
        AuthUser actor = interfaces.requireInterfacesOperate();
        SourceBinding binding = repository.sourceBinding(input.sourceId());
        if (binding == null || !binding.deviceEnabled() || !binding.sourceEnabled()
                || (input.ownerOrgId() != null && !input.ownerOrgId().equals(binding.ownerOrgId()))
                || (input.districtId() != null && !input.districtId().equals(binding.districtId()))) {
            throw missing("观测源不存在、已停用或不在当前单位区域范围内");
        }
        access.requireTuple(binding.ownerOrgId(), binding.districtId());
        validateTargetItems(input);
        Map<String, Object> payloadInput = new LinkedHashMap<>();
        payloadInput.put("message_id", input.messageId());
        payloadInput.put("source_id", input.sourceId());
        payloadInput.put("device_id", binding.deviceId());
        payloadInput.put("observed_at", input.observedAt());
        payloadInput.put("owner_org_id", binding.ownerOrgId());
        payloadInput.put("district_id", binding.districtId());
        payloadInput.put("items", input.items());
        String payload = encode(payloadInput);
        String hash = sha256(payload);
        boolean inserted = inbox.insertEnvelope(NORMALIZED_PREFIX + input.sourceId(), input.messageId(), clock.nowMillis(),
                input.sourceId(), hash, payload);
        if (inserted) {
            audit.record(actor.userId(), actor.account(), "local_normalized_observation", "device", binding.deviceId(),
                    "接收规范化模拟目标观测；数量=" + input.items().size(), null);
        }
        return new AcceptedInput(input.messageId(), input.sourceId(), binding.deviceId(), input.items().size());
    }

    @Transactional
    public AcceptedInput acceptWeatherObservation(WeatherObservationInput input) {
        AuthUser actor = interfaces.requireInterfacesOperate();
        WeatherBinding binding = repository.weatherBinding(input.deviceId());
        if (binding == null || !binding.deviceEnabled() || !binding.sourceEnabled()
                || !access.canAccessTuple(binding.ownerOrgId(), binding.districtId())) {
            throw missing("气象设备不存在、已停用或不在当前权限范围内");
        }
        validateCoordinate(input.longitude(), input.latitude());
        String payload = encode(input);
        String hash = sha256(payload);
        ExistingWeatherObservation previous = repository.weatherObservation(input.messageId());
        if (previous != null) {
            if (!hash.equals(previous.requestHash())) throw conflict("同一消息编号的气象观测内容已变化，请使用新编号");
            return new AcceptedInput(input.messageId(), binding.sourceId(), input.deviceId(), 1);
        }
        repository.insertWeatherObservation(input.messageId(), input.deviceId(), binding.ownerOrgId(), binding.districtId(),
                input.observedAt(), point(input.longitude(), input.latitude()), payload, hash, clock.nowMillis());
        audit.record(actor.userId(), actor.account(), "local_weather_observation", "device", input.deviceId(),
                "接收气象模拟观测", null);
        return new AcceptedInput(input.messageId(), binding.sourceId(), input.deviceId(), 1);
    }

    private void validateTargetItems(TargetObservationsInput input) {
        for (TargetItem item : input.items()) {
            boolean hasPosition = item.longitude() != null || item.latitude() != null;
            if (hasPosition && (item.longitude() == null || item.latitude() == null)) throw bad("目标经纬度必须成对提供");
            if (hasPosition) validateCoordinate(item.longitude(), item.latitude());
            positive(item.speedMps(), "speed_mps");
            range(item.headingDeg(), BigDecimal.ZERO, new BigDecimal("360"), "heading_deg", false);
            range(item.classConfidence(), BigDecimal.ZERO, BigDecimal.ONE, "class_confidence", true);
            boolean hasPilot = item.pilotLongitude() != null || item.pilotLatitude() != null;
            if (hasPilot && (item.pilotLongitude() == null || item.pilotLatitude() == null)) throw bad("飞手经纬度必须成对提供");
            if (hasPilot) validateCoordinate(item.pilotLongitude(), item.pilotLatitude());
        }
    }

    private void validateScope(String ownerOrgId, String districtId) {
        if (!repository.activeScope(ownerOrgId, districtId)) throw bad("单位或区域不存在或已停用");
    }

    private static void validateCoordinate(BigDecimal longitude, BigDecimal latitude) {
        range(longitude, new BigDecimal("-180"), new BigDecimal("180"), "longitude", true);
        range(latitude, new BigDecimal("-90"), new BigDecimal("90"), "latitude", true);
    }

    private static void positive(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) throw bad(field + " 不能为负数");
    }

    private static void range(BigDecimal value, BigDecimal min, BigDecimal max, String field, boolean inclusiveMax) {
        if (value == null || value.compareTo(min) < 0 || (inclusiveMax ? value.compareTo(max) > 0 : value.compareTo(max) >= 0))
            if (value != null) throw bad(field + " 超出有效范围");
    }

    private static String point(BigDecimal longitude, BigDecimal latitude) {
        return "SRID=4326;POINT(" + longitude.toPlainString() + " " + latitude.toPlainString() + ")";
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("模拟输入无法序列化", ex); }
    }

    private String hash(Object value) { return sha256(encode(value)); }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static String stableId(String prefix, String value) {
        return UUID.nameUUIDFromBytes((prefix + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "SIMULATION_CONFLICT", message); }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
}
