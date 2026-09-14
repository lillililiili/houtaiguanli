package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository.DeviceQuery;
import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceService {

    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("priority", "CASE COALESCE(s.connectivity,'UNKNOWN') WHEN 'ABNORMAL' THEN 0 WHEN 'OFFLINE' THEN 1 WHEN 'UNKNOWN' THEN 2 ELSE 3 END,d.device_no"),
            Map.entry("device_no_asc", "d.device_no ASC"),
            Map.entry("device_no_desc", "d.device_no DESC"),
            Map.entry("name_asc", "d.name ASC,d.device_no ASC"),
            Map.entry("type_asc", "d.device_type_name ASC,d.device_no ASC"),
            Map.entry("owner_asc", "d.owner_name ASC,d.device_no ASC"),
            Map.entry("vendor_asc", "d.vendor ASC,d.device_no ASC"),
            Map.entry("connectivity_asc", "s.connectivity ASC,d.device_no ASC"),
            Map.entry("last_heartbeat_desc", "s.last_heartbeat_at DESC,d.device_no ASC"));

    private final DeviceRepository repository;
    private final DeviceAccessPolicy access;
    private final AppClock clock;
    private final AppProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final DeviceAdapterRegistry adapterRegistry;
    private final IntegrationSourceService sources;

    public DeviceService(DeviceRepository repository, DeviceAccessPolicy access, AppClock clock,
                         AppProperties properties, AuditService audit, ObjectMapper objectMapper,
                         DeviceAdapterRegistry adapterRegistry, IntegrationSourceService sources) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.adapterRegistry = adapterRegistry;
        this.sources = sources;
    }

    public DevicePage list(DeviceFilter filter, int page, int size, String sort) {
        access.requireDevicesRead();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String order = SORTS.get(sort == null || sort.isBlank() ? "priority" : sort);
        if (order == null) throw bad("VALIDATION_ERROR", "sort 不在允许范围内");
        DeviceQuery query = query(filter);
        List<DeviceSummary> items = repository.list(query, (safePage - 1) * safeSize, safeSize, order)
                .stream().map(this::summary).toList();
        return new DevicePage(items, safePage, safeSize, repository.count(query));
    }

    public DeviceOptions options() {
        access.requireDevicesRead();
        return new DeviceOptions(repository.distinct("type"), repository.distinct("channel"),
                repository.distinct("region"), repository.distinct("vendor"));
    }

    public DeviceOverview overview() {
        access.requireMonitoringRead();
        Map<String, Object> row = repository.overview();
        int total = number(row, "total"), live = number(row, "live_count"), simulated = number(row, "simulated_count");
        String mode = live == 0 ? "mock" : live == total ? "live" : "mixed";
        return new DeviceOverview(number(row, "total"), number(row, "online"), number(row, "offline"),
                number(row, "abnormal"), number(row, "unknown_count"), number(row, "alarm"),
                number(row, "vendor_count"), number(row, "model_count"),
                groups(repository.overviewGroups("channel")), groups(repository.overviewGroups("type")),
                mode, simulated == total);
    }

    /** 大屏地图点：只返回有经纬度的启用设备；非 WGS-84 仍带回坐标系，由调用方决定是否绘制。 */
    public List<DeviceMapMarker> mapMarkers(int limit) {
        access.requireMonitoringRead();
        int safe = Math.min(Math.max(limit, 1), 100);
        List<DeviceMapMarker> markers = new ArrayList<>();
        for (Map<String, Object> row : repository.listForTree(query(new DeviceFilter(null, null, null, null, null, null, true)), safe)) {
            DeviceMapMarker marker = marker(row);
            if (marker != null) markers.add(marker);
        }
        return List.copyOf(markers);
    }

    public DeviceTree tree(DeviceFilter filter) {
        access.requireMonitoringRead();
        DeviceQuery query = query(filter);
        long total = repository.count(query);
        List<DeviceSummary> items = repository.listForTree(query, 500).stream().map(this::summary).toList();
        return new DeviceTree(items, total, total > items.size());
    }

    public DeviceDetail detail(String id) {
        AuthUser user = access.requireDevicesRead();
        Map<String, Object> row = requiredDevice(id);
        ConnectionProfile connection = access.canOperateDevices(user) ? connection(repository.findProfile(id)) : null;
        ProtocolConfiguration protocol = protocolConfiguration(row, access.canOperateDevices(user));
        return new DeviceDetail(summary(row), text(row, "source_id"), text(row, "external_device_id"),
                text(row, "model"), text(row, "vendor"), text(row, "owner_name"), text(row, "region_name"),
                text(row, "address"), decimal(row, "longitude"), decimal(row, "latitude"),
                text(row, "coordinate_system"), decimal(row, "altitude_m"), text(row, "altitude_datum"),
                text(row, "firmware_version"), longValue(row, "installed_at"), connection,
                connection != null, text(row, "source_code"), text(row, "source_name"),
                text(row, "protocol_code"), text(row, "protocol_version"), protocol,
                metrics(text(row, "metrics_json")),
                connection != null ? text(row, "allowed_cidrs") : null);
    }

    public DeviceState state(String id) {
        access.requireMonitoringRead();
        Map<String, Object> row = requiredDevice(id);
        return new DeviceState(id, text(row, "connectivity", "UNKNOWN"), text(row, "work_state_code"),
                bool(row, "has_alarm"), text(row, "health_code", "UNKNOWN"), longValue(row, "observed_at"),
                longValue(row, "received_at"), longValue(row, "last_heartbeat_at"),
                text(row, "unknown_reason"), metrics(text(row, "metrics_json")), bool(row, "simulated"));
    }

    public StateHistory history(String id, String metricCode, Long from, Long to, int limit) {
        access.requireMonitoringRead();
        requiredDevice(id);
        if (metricCode == null || !metricCode.matches("[a-z0-9_]{1,64}"))
            throw bad("VALIDATION_ERROR", "metric_code 格式无效");
        long now = clock.nowMillis();
        long safeTo = to == null ? now : Math.min(to, now + 60_000L);
        long safeFrom = from == null ? safeTo - 3_600_000L : from;
        if (safeFrom > safeTo || safeTo - safeFrom > 86_400_000L)
            throw bad("VALIDATION_ERROR", "查询时间窗必须在 24 小时内");
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<StatePoint> points = repository.stateHistory(id, metricCode, safeFrom, safeTo, safeLimit).stream()
                .map(r -> new StatePoint(text(r, "state_id"), longValue(r, "observed_at"),
                        longValue(r, "received_at"), decimal(r, "metric_value"), text(r, "metric_unit"),
                        text(r, "connectivity"), bool(r, "simulated")))
                .sorted((a, b) -> Long.compare(a.receivedAt(), b.receivedAt())).toList();
        return new StateHistory(id, metricCode, safeFrom, safeTo, points);
    }

    public IncidentPage incidents(String deviceId, String severity, String stage, int page, int size) {
        access.requireMonitoringRead();
        int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(size, 1), 100);
        List<Incident> items = repository.incidents(blankToNull(deviceId), blankToNull(severity), blankToNull(stage),
                (safePage - 1) * safeSize, safeSize).stream().map(this::incident).toList();
        return new IncidentPage(items, safePage, safeSize,
                repository.countIncidents(blankToNull(deviceId), blankToNull(severity), blankToNull(stage)));
    }

    public EventBatch events(String deviceId, long afterSeq, int limit) {
        access.requireMonitoringRead();
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<DeviceEvent> items = repository.events(blankToNull(deviceId), Math.max(afterSeq, 0), safeLimit).stream()
                .map(r -> new DeviceEvent(longNumber(r, "event_seq"), text(r, "event_id"), text(r, "device_id"),
                        text(r, "device_no"), text(r, "device_name"), text(r, "event_type"),
                        text(r, "level_code"), text(r, "message"), longNumber(r, "occurred_at"), bool(r, "simulated")))
                .toList();
        long next = items.isEmpty() ? Math.max(afterSeq, 0) : items.get(items.size() - 1).eventSeq();
        return new EventBatch(items, next);
    }

    @Transactional
    public DeviceDetail create(DeviceMutation mutation) {
        AuthUser user = access.requireDevicesOperate();
        validate(mutation);
        long now = clock.nowMillis();
        String id = UUID.randomUUID().toString();
        Map<String, Object> values = values(mutation, now);
        values.put("device_id", id);
        SourceSelection source = source(mutation.sourceId());
        values.put("source_mode", source.mode());
        values.put("simulated", source.simulated());
        try {
            repository.insertDevice(values);
            repository.upsertProfile(id, profileValues(mutation.connection()), now);
            repository.replaceProtocolProfile(id, source.protocolCode(), protocolValues(source.protocolCode(), mutation.protocolConfiguration()), now);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NO_CONFLICT", "设备编号或来源设备编号已存在");
        }
        repository.addEvent(UUID.randomUUID().toString(), id, "CATALOG_CREATED", "INFO",
                "设备台账已创建", now, source.simulated());
        audit.record(user.userId(), user.account(), "device_create", "device", id, mutation.deviceNo(), null);
        return detail(id);
    }

    @Transactional
    public DeviceDetail update(String id, long version, DeviceMutation mutation) {
        AuthUser user = access.requireDevicesOperate();
        validate(mutation);
        requiredDevice(id);
        long now = clock.nowMillis();
        SourceSelection source = source(mutation.sourceId());
        Map<String, Object> values = values(mutation, now);
        values.put("source_mode", source.mode());
        values.put("simulated", source.simulated());
        try {
            if (repository.updateDevice(id, version, values) != 1)
                throw conflict();
            repository.upsertProfile(id, profileValues(mutation.connection()), now);
            repository.replaceProtocolProfile(id, source.protocolCode(), protocolValues(source.protocolCode(), mutation.protocolConfiguration()), now);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NO_CONFLICT", "设备编号或来源设备编号已存在");
        }
        repository.addEvent(UUID.randomUUID().toString(), id, "CATALOG_UPDATED", "INFO",
                "设备台账已更新", now, source.simulated());
        if (blankToNull(mutation.allowedCidrs()) != null && blankToNull(mutation.sourceId()) != null) {
            sources.replaceNetwork(mutation.sourceId(), mutation.allowedCidrs(),
                    mutation.connection() == null ? null : mutation.connection().credentialRef());
        }
        audit.record(user.userId(), user.account(), "device_update", "device", id, "version=" + version, null);
        return detail(id);
    }

    @Transactional
    public DeviceDetail setEnabled(String id, long version, boolean enabled, String reason) {
        AuthUser user = access.requireDevicesOperate();
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "reason 长度必须为 2–500 个字符");
        Map<String, Object> device = requiredDevice(id);
        long now = clock.nowMillis();
        if (repository.setEnabled(id, version, enabled, now) != 1) throw conflict();
        repository.addEvent(UUID.randomUUID().toString(), id, enabled ? "DEVICE_ENABLED" : "DEVICE_DISABLED", "WARN",
                (enabled ? "设备已启用：" : "设备已停用：") + reason.trim(), now, "mock".equals(properties.getSourceMode()));
        String sourceId = text(device, "source_id");
        if (sourceId != null) {
            boolean anyEnabled = repository.countEnabledOnSource(sourceId) > 0;
            sources.syncEnabled(sourceId, anyEnabled, reason.trim());
        }
        audit.record(user.userId(), user.account(), enabled ? "device_enable" : "device_disable",
                "device", id, reason.trim(), null);
        return detail(id);
    }

    @Transactional
    public Command createReboot(String deviceId, String idempotencyKey, String reason) {
        AuthUser user = access.requireMonitoringOperate();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 96)
            throw bad("VALIDATION_ERROR", "Idempotency-Key 必填且最长 96 个字符");
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "重启原因长度必须为 2–500 个字符");
        Map<String, Object> device = requiredDevice(deviceId);
        if (!bool(device, "enabled") || !"ONLINE".equals(text(device, "connectivity")))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "仅已启用且在线的设备可发起重启");
        String sourceMode = text(device, "source_mode");
        String protocolCode = text(device, "protocol_code");
        DeviceAdapterPort adapter = adapterRegistry.find(SourceMode.valueOf(sourceMode), protocolCode)
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_UNAVAILABLE", "设备协议适配器不可用"));
        if (!adapter.supportsReboot())
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "该设备协议未声明重启能力");
        String key = "reboot:" + idempotencyKey.trim();
        String hash = sha256(user.userId() + "|" + deviceId + "|REBOOT|" + reason.trim());
        Map<String, Object> replay = repository.findIdempotency(key);
        if (replay != null) {
            if (!hash.equals(text(replay, "request_hash")))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
            return requiredCommand(text(replay, "response_body"));
        }
        long now = clock.nowMillis();
        String id = UUID.randomUUID().toString();
        Map<String, Object> values = new HashMap<>();
        values.put("command_id", id);
        values.put("command_no", "RB-" + now + "-" + id.substring(0, 6).toUpperCase());
        values.put("device_id", deviceId);
        values.put("requested_by", user.userId());
        values.put("reason", reason.trim());
        boolean simulated = bool(device, "simulated");
        values.put("source_mode", sourceMode);
        values.put("simulated", simulated);
        values.put("deadline_at", now + 30_000L);
        values.put("created_at", now);
        values.put("updated_at", now);
        repository.insertCommand(values);
        repository.insertIdempotency(key, user.userId(), hash, id, now);
        repository.addOutbox(UUID.randomUUID().toString(), "device.reboot", id, now, now + 600L);
        repository.addEvent(UUID.randomUUID().toString(), deviceId, "REBOOT_QUEUED", "WARN",
                simulated ? "模拟重启指令已排队，等待适配器回执" : "设备重启指令已排队，等待适配器回执",
                now, simulated);
        audit.record(user.userId(), user.account(), "device_reboot_requested", "device_command", id, reason.trim(), null);
        return requiredCommand(id);
    }

    public Command command(String id) {
        access.requireMonitoringRead();
        return requiredCommand(id);
    }

    private Command requiredCommand(String id) {
        Map<String, Object> r = repository.findCommand(id);
        if (r == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_COMMAND_NOT_FOUND", "设备指令不存在");
        return new Command(text(r, "command_id"), text(r, "command_no"), text(r, "device_id"),
                text(r, "device_no"), text(r, "device_name"), text(r, "command_type"), text(r, "reason"),
                text(r, "status"), text(r, "source_mode"), bool(r, "simulated"), longValue(r, "issued_at"),
                longValue(r, "deadline_at"), longValue(r, "completed_at"), text(r, "result_code"),
                text(r, "result_detail"), repository.commandReceipts(id).stream()
                        .map(x -> new Receipt(text(x, "receipt_id"), text(x, "receipt_kind"), text(x, "device_result_code"),
                                longValue(x, "occurred_at"), longNumber(x, "received_at"), parseJson(text(x, "payload"))))
                        .toList(), longNumber(r, "created_at"), longNumber(r, "updated_at"));
    }

    private DeviceQuery query(DeviceFilter f) {
        if (f == null) return new DeviceQuery(null, null, null, null, null, null, null);
        String keyword = blankToNull(f.keyword());
        if (keyword != null && keyword.length() > 100) throw bad("VALIDATION_ERROR", "keyword 最长 100 个字符");
        String connectivity = blankToNull(f.connectivity());
        if (connectivity != null && !List.of("UNKNOWN", "ONLINE", "OFFLINE", "ABNORMAL").contains(connectivity))
            throw bad("VALIDATION_ERROR", "connectivity 不在允许范围内");
        return new DeviceQuery(keyword, blankToNull(f.typeCode()), blankToNull(f.channel()), blankToNull(f.region()),
                blankToNull(f.vendor()), connectivity, f.enabled());
    }

    private void validate(DeviceMutation m) {
        if (m == null || blankToNull(m.deviceNo()) == null || blankToNull(m.name()) == null
                || blankToNull(m.deviceTypeName()) == null || blankToNull(m.channel()) == null)
            throw bad("VALIDATION_ERROR", "设备编号、名称、类型名称和接入通道必填");
        if (m.deviceNo().trim().length() > 64 || m.name().trim().length() > 128)
            throw bad("VALIDATION_ERROR", "设备编号或名称过长");
        if (!"mock".equals(properties.getSourceMode())
                && (blankToNull(m.sourceId()) == null || blankToNull(m.externalDeviceId()) == null))
            throw bad("VALIDATION_ERROR", "必须绑定接入来源并填写来源设备 ID");
        if ((blankToNull(m.sourceId()) == null) != (blankToNull(m.externalDeviceId()) == null))
            throw bad("VALIDATION_ERROR", "source_id 与 external_device_id 必须同时填写或同时为空");
        if ((m.longitude() == null) != (m.latitude() == null))
            throw bad("VALIDATION_ERROR", "经度和纬度必须同时填写或同时为空");
        if (m.longitude() != null && (m.longitude().compareTo(BigDecimal.valueOf(-180)) < 0 || m.longitude().compareTo(BigDecimal.valueOf(180)) > 0))
            throw bad("VALIDATION_ERROR", "经度必须在 -180 到 180 之间");
        if (m.latitude() != null && (m.latitude().compareTo(BigDecimal.valueOf(-90)) < 0 || m.latitude().compareTo(BigDecimal.valueOf(90)) > 0))
            throw bad("VALIDATION_ERROR", "纬度必须在 -90 到 90 之间");
        ConnectionProfile c = m.connection();
        if (c != null && c.port() != null && (c.port() < 1 || c.port() > 65535))
            throw bad("VALIDATION_ERROR", "端口必须在 1 到 65535 之间");
        SourceSelection source = source(m.sourceId());
        if (source.protocolCode() != null) validateProtocol(source.protocolCode(), m.protocolConfiguration());
    }

    private void validateProtocol(String protocolCode, ProtocolConfiguration configuration) {
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocolCode)) {
            String role = configuration == null ? "DATA" : blankToNull(configuration.loginRole());
            if (role != null && !"DATA".equals(role))
                throw bad("VALIDATION_ERROR", "雷达首版只允许 login_role=DATA");
        } else if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(protocolCode)) {
            int address = configuration == null || configuration.deviceAddress() == null ? 1 : configuration.deviceAddress();
            if (address < 1 || address > 244)
                throw bad("VALIDATION_ERROR", "反制设备地址必须为 1–244，广播地址 245 被禁止");
            String encoding = configuration == null ? "AUTO" : blankToNull(configuration.wireEncoding());
            if (encoding != null && !List.of("AUTO", "RAW_BYTES", "ASCII_HEX_SPACED", "ASCII_HEX_COMPACT").contains(encoding))
                throw bad("VALIDATION_ERROR", "wire_encoding 不在允许范围内");
            int poll = configuration == null || configuration.pollIntervalMillis() == null ? 5000 : configuration.pollIntervalMillis();
            if (poll < 1000 || poll > 60000) throw bad("VALIDATION_ERROR", "poll_interval_millis 必须为 1000–60000");
        } else {
            throw bad("PROTOCOL_UNSUPPORTED", "来源协议不受支持");
        }
    }

    private Map<String, Object> values(DeviceMutation m, long now) {
        Map<String, Object> v = new HashMap<>();
        v.put("source_id", blankToNull(m.sourceId())); v.put("external_device_id", blankToNull(m.externalDeviceId()));
        v.put("device_no", m.deviceNo().trim()); v.put("name", m.name().trim());
        v.put("device_type_code", blankToNull(m.deviceTypeCode())); v.put("device_type_name", m.deviceTypeName().trim());
        v.put("channel", m.channel().trim()); v.put("model", blankToNull(m.model())); v.put("vendor", blankToNull(m.vendor()));
        v.put("owner_name", blankToNull(m.ownerName())); v.put("region_name", blankToNull(m.regionName()));
        v.put("address", blankToNull(m.address())); v.put("longitude", m.longitude()); v.put("latitude", m.latitude());
        v.put("coordinate_system", m.longitude() == null ? null : (blankToNull(m.coordinateSystem()) == null ? "WGS-84" : m.coordinateSystem().trim()));
        v.put("altitude_m", m.altitudeM()); v.put("altitude_datum", blankToNull(m.altitudeDatum()));
        v.put("firmware_version", blankToNull(m.firmwareVersion())); v.put("installed_at", m.installedAt());
        v.put("created_at", now); v.put("updated_at", now);
        return v;
    }

    private Map<String, Object> profileValues(ConnectionProfile c) {
        ConnectionProfile p = c == null ? ConnectionProfile.empty() : c;
        Map<String, Object> v = new HashMap<>();
        v.put("transport", blankToNull(p.transport())); v.put("host", blankToNull(p.host())); v.put("port", p.port());
        v.put("path", blankToNull(p.path())); v.put("data_format", blankToNull(p.dataFormat()));
        v.put("charset_name", blankToNull(p.charsetName())); v.put("auth_mode", blankToNull(p.authMode()));
        v.put("credential_ref", blankToNull(p.credentialRef()));
        v.put("heartbeat_interval_seconds", p.heartbeatIntervalSeconds()); v.put("report_interval_millis", p.reportIntervalMillis());
        v.put("sampling_rate_hz", p.samplingRateHz()); v.put("compression_enabled", p.compressionEnabled());
        v.put("retransmission_enabled", p.retransmissionEnabled()); v.put("timeout_millis", p.timeoutMillis());
        v.put("retry_count", p.retryCount()); v.put("longitude_offset_deg", p.longitudeOffsetDeg());
        v.put("latitude_offset_deg", p.latitudeOffsetDeg()); v.put("altitude_offset_m", p.altitudeOffsetM());
        v.put("time_sync_mode", blankToNull(p.timeSyncMode())); v.put("time_server", blankToNull(p.timeServer()));
        v.put("timezone_name", blankToNull(p.timezoneName())); v.put("time_sync_interval_seconds", p.timeSyncIntervalSeconds());
        return v;
    }

    private Map<String, Object> protocolValues(String protocolCode, ProtocolConfiguration configuration) {
        ProtocolConfiguration p = configuration == null ? ProtocolConfiguration.empty() : configuration;
        Map<String, Object> values = new HashMap<>();
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocolCode)) {
            values.put("login_role", blankToNull(p.loginRole()) == null ? "DATA" : p.loginRole().trim());
            values.put("recognition_code_ref", blankToNull(p.recognitionCodeRef()));
            values.put("rtk_enabled", Boolean.TRUE.equals(p.rtkEnabled()));
            values.put("coordinate_transform_enabled", Boolean.TRUE.equals(p.coordinateTransformEnabled()));
        } else if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(protocolCode)) {
            values.put("device_address", p.deviceAddress() == null ? 1 : p.deviceAddress());
            values.put("wire_encoding", blankToNull(p.wireEncoding()) == null ? "AUTO" : p.wireEncoding().trim());
            values.put("poll_interval_millis", p.pollIntervalMillis() == null ? 5000 : p.pollIntervalMillis());
        }
        return values;
    }

    private ProtocolConfiguration protocolConfiguration(Map<String, Object> device, boolean sensitiveVisible) {
        String code = text(device, "protocol_code");
        Map<String, Object> p = repository.findProtocolProfile(text(device, "device_id"), code);
        if (p == null) return null;
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(code)) {
            return new ProtocolConfiguration(text(p, "login_role"),
                    sensitiveVisible ? text(p, "recognition_code_ref") : null,
                    boolObject(p, "rtk_enabled"), boolObject(p, "coordinate_transform_enabled"),
                    null, null, null);
        }
        if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(code)) {
            return new ProtocolConfiguration(null, null, null, null, integer(p, "device_address"),
                    text(p, "wire_encoding"), integer(p, "poll_interval_millis"));
        }
        return null;
    }

    private SourceSelection source(String sourceId) {
        if (blankToNull(sourceId) == null) {
            String mode = properties.getSourceMode();
            return new SourceSelection(mode, "mock".equals(mode), null);
        }
        Map<String, Object> row = repository.findIntegrationSource(sourceId.trim());
        if (row == null) throw bad("VALIDATION_ERROR", "source_id 对应的接入来源不存在");
        String mode = text(row, "source_mode");
        String protocol = text(row, "protocol_code");
        if (DeviceProtocolCodes.LINGYUN_MQTT_V8_6.equals(protocol)
                || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(protocol))
            throw bad("MQTT_REGISTRATION_REQUIRED", "MQTT 设备必须通过接入设备接口登记和编辑");
        if ("live".equals(mode) && !adapterRegistry.supports(SourceMode.live, protocol))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROTOCOL_UNSUPPORTED", "live 来源协议适配器不可用");
        return new SourceSelection(mode, bool(row, "simulated"), protocol);
    }

    private DeviceMapMarker marker(Map<String, Object> r) {
        BigDecimal longitude = decimal(r, "longitude");
        BigDecimal latitude = decimal(r, "latitude");
        if (longitude == null || latitude == null) return null;
        return new DeviceMapMarker(text(r, "device_id"), text(r, "device_no"), text(r, "name"),
                text(r, "device_type_name"), text(r, "channel"), text(r, "connectivity", "UNKNOWN"),
                bool(r, "has_alarm"), longitude, latitude, text(r, "coordinate_system"));
    }

    private DeviceSummary summary(Map<String, Object> r) {
        return new DeviceSummary(text(r, "device_id"), text(r, "device_no"), text(r, "name"),
                text(r, "device_type_code"), text(r, "device_type_name"), text(r, "channel"),
                text(r, "owner_name"), text(r, "region_name"), text(r, "address"), text(r, "model"),
                text(r, "vendor"), bool(r, "enabled"), longNumber(r, "version"),
                text(r, "connectivity", "UNKNOWN"), text(r, "work_state_code"), bool(r, "has_alarm"),
                text(r, "health_code", "UNKNOWN"), longValue(r, "last_heartbeat_at"),
                bool(r, "simulated"), text(r, "source_mode"), text(r, "source_name"),
                text(r, "protocol_code"), text(r, "protocol_version"),
                decimal(r, "longitude"), decimal(r, "latitude"), text(r, "coordinate_system"));
    }

    private Incident incident(Map<String, Object> r) {
        return new Incident(text(r, "incident_id"), text(r, "incident_no"), text(r, "device_id"),
                text(r, "device_no"), text(r, "device_name"), text(r, "incident_type"), text(r, "severity"),
                text(r, "stage"), longNumber(r, "detected_at"), text(r, "reason"), longValue(r, "closed_at"),
                text(r, "block_reason"), bool(r, "simulated"), text(r, "reboot_command_id"));
    }

    private ConnectionProfile connection(Map<String, Object> r) {
        if (r == null) return null;
        return new ConnectionProfile(text(r, "transport"), text(r, "host"), integer(r, "port"), text(r, "path"),
                text(r, "data_format"), text(r, "charset_name"), text(r, "auth_mode"), text(r, "credential_ref"),
                integer(r, "heartbeat_interval_seconds"), integer(r, "report_interval_millis"), decimal(r, "sampling_rate_hz"),
                boolObject(r, "compression_enabled"), boolObject(r, "retransmission_enabled"), integer(r, "timeout_millis"),
                integer(r, "retry_count"), decimal(r, "longitude_offset_deg"), decimal(r, "latitude_offset_deg"),
                decimal(r, "altitude_offset_m"), text(r, "time_sync_mode"), text(r, "time_server"),
                text(r, "timezone_name"), integer(r, "time_sync_interval_seconds"));
    }

    private List<MetricValue> metrics(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            List<MetricValue> out = new ArrayList<>();
            raw.forEach((key, value) -> {
                if (value instanceof Map<?, ?> m) {
                    out.add(new MetricValue(key, m.get("label") == null ? key : String.valueOf(m.get("label")),
                            m.get("value"), m.get("unit") == null ? null : String.valueOf(m.get("unit")),
                            m.get("source") == null ? null : String.valueOf(m.get("source"))));
                }
            });
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, Object.class); }
        catch (Exception ex) { return json; }
    }

    private List<OverviewGroup> groups(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> new OverviewGroup(text(r, "group_name"), number(r, "total"),
                number(r, "online"), number(r, "offline"), number(r, "abnormal"), number(r, "unknown_count"))).toList();
    }

    private Map<String, Object> requiredDevice(String id) {
        Map<String, Object> row = repository.find(id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        return row;
    }

    private ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被其他操作更新，请刷新后重试");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static String text(Map<String, Object> r, String key) { Object v = r.get(key); return v == null ? null : String.valueOf(v); }
    private static String text(Map<String, Object> r, String key, String fallback) { String v = text(r, key); return v == null ? fallback : v; }
    private static boolean bool(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v)); }
    private static Boolean boolObject(Map<String, Object> r, String key) { return r.get(key) == null ? null : bool(r, key); }
    private static int number(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.intValue() : 0; }
    private static long longNumber(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.longValue() : 0L; }
    private static Long longValue(Map<String, Object> r, String key) { return r.get(key) == null ? null : longNumber(r, key); }
    private static Integer integer(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.intValue() : null; }
    private static BigDecimal decimal(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof BigDecimal b ? b : v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null; }

    public record DeviceFilter(String keyword, String typeCode, String channel, String region,
                               String vendor, String connectivity, Boolean enabled) { }
    public record DevicePage(List<DeviceSummary> items, int page, int size, long total) { }
    public record DeviceOptions(List<String> types, List<String> channels, List<String> regions, List<String> vendors) { }
    public record DeviceTree(List<DeviceSummary> items, long total, boolean truncated) { }
    public record DeviceOverview(int total, int online, int offline, int abnormal, int unknown, int alarm,
                                 int vendorCount, int modelCount, List<OverviewGroup> byChannel,
                                 List<OverviewGroup> byType, String sourceMode, boolean simulated) { }
    public record OverviewGroup(String name, int total, int online, int offline, int abnormal, int unknown) { }
    public record DeviceMapMarker(String deviceId, String deviceNo, String name, String deviceTypeName,
                                 String channel, String connectivity, boolean hasAlarm,
                                 BigDecimal longitude, BigDecimal latitude, String coordinateSystem) { }
    public record DeviceSummary(String deviceId, String deviceNo, String name, String deviceTypeCode,
                                String deviceTypeName, String channel, String ownerName, String regionName,
                                String address, String model, String vendor, boolean enabled, long version,
                                String connectivity, String workStateCode, boolean hasAlarm, String healthCode,
                                Long lastHeartbeatAt, boolean simulated, String sourceMode, String sourceName,
                                String protocolCode, String protocolVersion,
                                BigDecimal longitude, BigDecimal latitude, String coordinateSystem) { }
    public record DeviceDetail(DeviceSummary device, String sourceId, String externalDeviceId, String model,
                               String vendor, String ownerName, String regionName, String address,
                               BigDecimal longitude, BigDecimal latitude, String coordinateSystem,
                               BigDecimal altitudeM, String altitudeDatum, String firmwareVersion, Long installedAt,
                               ConnectionProfile connection, boolean connectionVisible, String sourceCode,
                               String sourceName, String protocolCode, String protocolVersion,
                               ProtocolConfiguration protocolConfiguration, List<MetricValue> metrics,
                               String allowedCidrs) { }
    public record DeviceState(String deviceId, String connectivity, String workStateCode, boolean hasAlarm,
                              String healthCode, Long observedAt, Long receivedAt, Long lastHeartbeatAt,
                              String unknownReason, List<MetricValue> metrics, boolean simulated) { }
    public record MetricValue(String code, String label, Object value, String unit, String source) { }
    public record StatePoint(String stateId, Long observedAt, Long receivedAt, BigDecimal value,
                             String unit, String connectivity, boolean simulated) { }
    public record StateHistory(String deviceId, String metricCode, long from, long to, List<StatePoint> points) { }
    public record Incident(String incidentId, String incidentNo, String deviceId, String deviceNo,
                           String deviceName, String incidentType, String severity, String stage,
                           long detectedAt, String reason, Long closedAt, String blockReason, boolean simulated,
                           String rebootCommandId) { }
    public record IncidentPage(List<Incident> items, int page, int size, long total) { }
    public record DeviceEvent(long eventSeq, String eventId, String deviceId, String deviceNo, String deviceName,
                              String eventType, String levelCode, String message, long occurredAt, boolean simulated) { }
    public record EventBatch(List<DeviceEvent> items, long nextSeq) { }
    public record Command(String commandId, String commandNo, String deviceId, String deviceNo, String deviceName,
                          String commandType, String reason, String status, String sourceMode, boolean simulated,
                          Long issuedAt, Long deadlineAt, Long completedAt, String resultCode, String resultDetail,
                          List<Receipt> receipts, long createdAt, long updatedAt) { }
    public record Receipt(String receiptId, String receiptKind, String deviceResultCode, Long occurredAt,
                          long receivedAt, Object payload) { }
    public record DeviceMutation(String sourceId, String externalDeviceId, String deviceNo, String name,
                                 String deviceTypeCode, String deviceTypeName, String channel, String model,
                                 String vendor, String ownerName, String regionName, String address,
                                 BigDecimal longitude, BigDecimal latitude, String coordinateSystem,
                                 BigDecimal altitudeM, String altitudeDatum, String firmwareVersion,
                                 Long installedAt, ConnectionProfile connection,
                                 ProtocolConfiguration protocolConfiguration, String allowedCidrs) { }
    public record ConnectionProfile(String transport, String host, Integer port, String path, String dataFormat,
                                    String charsetName, String authMode, String credentialRef, Integer heartbeatIntervalSeconds,
                                    Integer reportIntervalMillis, BigDecimal samplingRateHz,
                                    Boolean compressionEnabled, Boolean retransmissionEnabled,
                                    Integer timeoutMillis, Integer retryCount, BigDecimal longitudeOffsetDeg,
                                    BigDecimal latitudeOffsetDeg, BigDecimal altitudeOffsetM, String timeSyncMode,
                                    String timeServer, String timezoneName, Integer timeSyncIntervalSeconds) {
        static ConnectionProfile empty() {
            return new ConnectionProfile(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }
    }
    public record ProtocolConfiguration(String loginRole, String recognitionCodeRef, Boolean rtkEnabled,
                                        Boolean coordinateTransformEnabled, Integer deviceAddress,
                                        String wireEncoding, Integer pollIntervalMillis) {
        static ProtocolConfiguration empty() {
            return new ProtocolConfiguration(null, null, null, null, null, null, null);
        }
    }
    private record SourceSelection(String mode, boolean simulated, String protocolCode) { }
}
