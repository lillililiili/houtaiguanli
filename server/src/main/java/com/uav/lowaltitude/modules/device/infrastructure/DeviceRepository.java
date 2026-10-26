package com.uav.lowaltitude.modules.device.infrastructure;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.platform.query.StatisticsScope;

@Repository
public class DeviceRepository {

    // One read-time interpretation for detail, filtering, sorting, reports and aggregates.
    // Use the application clock rather than database wall time so every caller shares the freshness contract.
    private static final String EFFECTIVE_CONNECTIVITY = """
            (CASE WHEN d.simulated=TRUE AND d.source_mode='replay'
                AND EXISTS(SELECT 1 FROM ops_integration_source weather_source
                    WHERE weather_source.source_id=d.source_id AND weather_source.protocol_code='WEATHER_SIMULATOR')
                AND (d.enabled=FALSE OR s.observed_at IS NULL OR s.observed_at<:weather_fresh_after)
                THEN 'UNKNOWN'
            WHEN d.device_type_code='adsb_receiver' THEN (CASE WHEN d.enabled=FALSE THEN 'UNKNOWN'
                WHEN EXISTS(SELECT 1 FROM adsb_receiver adsb WHERE adsb.device_id=d.device_id AND adsb.last_data_at>=:adsb_fresh_after)
                THEN 'ONLINE' ELSE 'OFFLINE' END)
            ELSE COALESCE(s.connectivity,'UNKNOWN') END)
            """;
    private static final String CONNECTIVITY_COUNTS =
            "SUM(CASE WHEN " + EFFECTIVE_CONNECTIVITY + "='ONLINE' THEN 1 ELSE 0 END) AS online, "
            + "SUM(CASE WHEN " + EFFECTIVE_CONNECTIVITY + "='OFFLINE' THEN 1 ELSE 0 END) AS offline, "
            + "SUM(CASE WHEN " + EFFECTIVE_CONNECTIVITY + "='ABNORMAL' THEN 1 ELSE 0 END) AS abnormal, "
            + "SUM(CASE WHEN " + EFFECTIVE_CONNECTIVITY + "='UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count ";

    public com.uav.lowaltitude.platform.report.BusinessReportSource.Dataset reportDataset() {
        Map<String,Object> params = readParameters();
        String sql = "SELECT d.device_id AS id,d.name AS label,CAST(NULL AS BIGINT) AS at_ms,"
            + EFFECTIVE_CONNECTIVITY + " AS state,COALESCE(NULLIF(d.device_type_name,''),d.device_type_code) AS kind,CAST(NULL AS VARCHAR) AS severity,d.region_name AS region,"
            + "CASE WHEN d.simulated=TRUE AND d.source_mode='live' THEN 'mock' ELSE d.source_mode END AS source_mode,"
            + "d.device_no AS related,s.health_code AS result,CAST(NULL AS VARCHAR) AS note"
            + " FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id WHERE d.deleted_at IS NULL" + deviceScope(params);
        return new com.uav.lowaltitude.platform.report.BusinessReportSource.Dataset(sql, params);
    }

    private static final String DEVICE_SELECT = """
            SELECT d.*, %s AS connectivity, s.work_state_code, s.has_alarm, s.health_code,
                   s.observed_at, s.received_at, s.last_heartbeat_at, s.metrics_json,
                   s.health_observed_at,s.health_source_id,s.health_source_mode,s.health_simulated,
                   s.alarm_observed_at,s.alarm_source_id,s.alarm_source_mode,s.alarm_simulated,
                   s.unknown_reason, s.version AS state_version,
                   src.source_code, src.name AS source_name, src.protocol_code, src.protocol_version,
                   src.allowed_cidrs, src.credential_ref AS source_credential_ref,
                   src.enabled AS source_enabled,
                   sensing.coverage_kind, sensing.radius_m AS coverage_radius_m,
                   sensing.range_m AS coverage_range_m, sensing.azimuth_deg AS coverage_azimuth_deg,
                   sensing.fov_deg AS coverage_fov_deg, sensing.source_label AS coverage_source_label,
                   sensing.version AS coverage_version, sensing.updated_at AS coverage_updated_at,
                   COALESCE(mqtt_binding.device_id, eo_binding.device_id, tcp_device.device_id) AS fusion_device_id,
                   mqtt_binding.last_sense_at AS sense_received_at, mqtt_binding.last_pt_time AS sense_observed_at,
                   business_scope.owner_org_id,business_scope.district_id,
                   (mqtt_binding.ops_device_id IS NOT NULL OR eo_binding.ops_device_id IS NOT NULL) AS mqtt_heartbeat
            FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id = d.device_id
            LEFT JOIN ops_integration_source src ON src.source_id=d.source_id
            LEFT JOIN device_sensing_profile sensing ON sensing.device_id=d.device_id
            LEFT JOIN mqtt_device_binding mqtt_binding ON mqtt_binding.ops_device_id=d.device_id
            LEFT JOIN eo_device_binding eo_binding ON eo_binding.ops_device_id=d.device_id
            LEFT JOIN device tcp_device ON tcp_device.device_id=d.device_id AND src.protocol_code='RADAR_TCP_V3_0_0'
            LEFT JOIN device_business_scope business_scope ON business_scope.ops_device_id=d.device_id
            """.formatted(EFFECTIVE_CONNECTIVITY);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final com.uav.lowaltitude.platform.time.AppClock clock;
    private final StatisticsScope statistics;

    public DeviceRepository(JdbcTemplate jdbc, com.uav.lowaltitude.platform.time.AppClock clock, StatisticsScope statistics) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
        this.statistics = statistics;
    }

    private Map<String,Object> readParameters() {
        Map<String,Object> params = new HashMap<>();
        params.put("weather_fresh_after", clock.nowMillis() - 15000);
        // 航班接收机：30 秒内收到过航班数据就是在线（2026-10-26 航班避让）。
        params.put("adsb_fresh_after", clock.nowMillis() - 30000);
        return params;
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device", Long.class);
        return count == null ? 0 : count;
    }

    public long countEnabledOnSource(String sourceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE source_id=? AND enabled=TRUE", Long.class, sourceId);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> list(DeviceQuery query, int offset, int size, String orderBy) {
        SqlWhere where = where(query);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return named.queryForList(DEVICE_SELECT + where.sql + " ORDER BY " + orderBy.replace("s.connectivity", EFFECTIVE_CONNECTIVITY) + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.params);
    }

    public long count(DeviceQuery query) {
        SqlWhere where = where(query);
        Long count = named.queryForObject(
                "SELECT COUNT(*) FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id " + where.sql,
                where.params,
                Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> listForTree(DeviceQuery query, int limit) {
        return listForTree(query, 0, limit);
    }

    public List<Map<String, Object>> listForTree(DeviceQuery query, int offset, int limit) {
        SqlWhere where = where(query);
        where.params.put("offset", offset);
        where.params.put("limit", limit);
        return named.queryForList(DEVICE_SELECT + where.sql
                + " ORDER BY d.channel, d.device_type_name, d.device_no, d.device_id OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY", where.params);
    }

    public List<Map<String, Object>> listForMap(DeviceQuery query) {
        SqlWhere where = where(query);
        return named.queryForList(DEVICE_SELECT + where.sql
                + " AND d.longitude IS NOT NULL AND d.latitude IS NOT NULL ORDER BY d.device_id", where.params);
    }

    public Map<String, Object> find(String deviceId) {
        Map<String,Object> params=readParameters();
        params.put("device_id",deviceId);
        List<Map<String, Object>> rows = named.queryForList(
                DEVICE_SELECT + " WHERE d.device_id=:device_id AND d.deleted_at IS NULL" + deviceScope(params), params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Internal evaluator checks the frozen source tuple; public reads must keep using find. */
    public Map<String,Object> findForRecovery(String deviceId) {
        Map<String,Object> params=readParameters();
        params.put("device_id",deviceId);
        var rows=named.queryForList(DEVICE_SELECT+" WHERE d.device_id=:device_id AND d.deleted_at IS NULL",params);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** Shared order with ingestion and device work creation: catalog, state, then domain rows. */
    public void lockForRecovery(String deviceId) {
        jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=? FOR UPDATE",deviceId);
        jdbc.queryForList("SELECT device_id FROM ops_device_state WHERE device_id=? FOR UPDATE",deviceId);
    }

    /** Scheduled work skips a busy device; user actions retain the blocking, version-checked path above. */
    public boolean tryLockForRecovery(String deviceId) {
        if(jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=? FOR UPDATE SKIP LOCKED",deviceId).isEmpty())return false;
        var state=jdbc.queryForList("SELECT device_id FROM ops_device_state WHERE device_id=? FOR UPDATE SKIP LOCKED",deviceId);
        return !state.isEmpty()||jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_state WHERE device_id=?",Long.class,deviceId)==0;
    }

    public List<Map<String,Object>> openIncidentsForRecovery(String deviceId,boolean lock) {
        return jdbc.queryForList("SELECT * FROM device_incident WHERE device_id=? AND stage<>'RECOVERED'"
                +" ORDER BY incident_id"+(lock?" FOR UPDATE":""),deviceId);
    }

    /** A v2 case freezes an incident's source; current device edits cannot rewrite that lineage. */
    public boolean hasIncidentSourceConflict(String deviceId,String sourceMode,String sourceId,boolean simulated) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM device_incident i
            JOIN ops_maintenance_incident l ON l.incident_id=i.incident_id
            JOIN ops_device_maintenance_task t ON t.task_id=l.task_id
            WHERE i.device_id=? AND i.stage<>'RECOVERED' AND t.workflow_version=2
              AND (t.workflow_source_id IS DISTINCT FROM ? OR t.workflow_source_mode IS DISTINCT FROM ?
                   OR t.simulated<>?)
            """,Long.class,deviceId,sourceId,sourceMode,simulated)>0;
    }

    /** Read once under the device lock when several historical cases share the same device. */
    public List<Map<String,Object>> incidentRecoverySources(String deviceId) {
        return jdbc.queryForList("""
            SELECT DISTINCT t.workflow_source_id AS source_id,t.workflow_source_mode AS source_mode,t.simulated
            FROM device_incident i JOIN ops_maintenance_incident l ON l.incident_id=i.incident_id
            JOIN ops_device_maintenance_task t ON t.task_id=l.task_id
            WHERE i.device_id=? AND i.stage<>'RECOVERED' AND t.workflow_version=2
            """,deviceId);
    }

    /** Internal plan inspection always has an explicit business tuple; never an unscoped device scan. */
    public List<Map<String,Object>> forPlanInspection(String org, String district, boolean userScope) {
        if (org == null || district == null) return List.of();
        Map<String,Object> params = readParameters();
        params.put("inspection_org", org); params.put("inspection_district", district);
        return named.queryForList(DEVICE_SELECT + " WHERE d.deleted_at IS NULL"
            + " AND business_scope.owner_org_id=:inspection_org AND business_scope.district_id=:inspection_district"
            + " AND EXISTS(SELECT 1 FROM app_org o WHERE o.org_id=:inspection_org AND o.enabled=TRUE)"
            + " AND EXISTS(SELECT 1 FROM app_district a WHERE a.district_id=:inspection_district AND a.enabled=TRUE)"
            + (userScope ? deviceScope(params) : "") + " ORDER BY d.device_id", params);
    }

    public List<Map<String,Object>> planInspectionIncidents(String id, String org, String district) {
        return jdbc.queryForList("SELECT i.*,d.device_no,d.name AS device_name FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id WHERE i.device_id=?"
            + " AND EXISTS(SELECT 1 FROM device_business_scope s WHERE s.ops_device_id=i.device_id AND s.owner_org_id=? AND s.district_id=?)",
            id, org, district);
    }

    public Map<String, Object> findProfile(String deviceId) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT * FROM device_connection_profile WHERE device_id=:device_id",
                Map.of("device_id", deviceId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findSensingProfile(String deviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM device_sensing_profile WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertSensingProfile(String deviceId, Map<String, Object> values, long now) {
        Map<String, Object> p = new HashMap<>(values);
        p.put("device_id", deviceId);
        p.put("updated_at", now);
        named.update("""
                INSERT INTO device_sensing_profile (
                    device_id, coverage_kind, radius_m, range_m, azimuth_deg, fov_deg,
                    source_label, version, updated_at)
                VALUES (:device_id, :coverage_kind, :radius_m, :range_m, :azimuth_deg, :fov_deg,
                    :source_label, 0, :updated_at)
                """, p);
    }

    public int updateSensingProfile(String deviceId, long expectedVersion, Map<String, Object> values, long now) {
        Map<String, Object> p = new HashMap<>(values);
        p.put("device_id", deviceId);
        p.put("expected_version", expectedVersion);
        p.put("updated_at", now);
        return named.update("""
                UPDATE device_sensing_profile SET coverage_kind=:coverage_kind, radius_m=:radius_m,
                    range_m=:range_m, azimuth_deg=:azimuth_deg, fov_deg=:fov_deg,
                    source_label=:source_label, version=version+1, updated_at=:updated_at
                WHERE device_id=:device_id AND version=:expected_version
                """, p);
    }

    public int deleteSensingProfile(String deviceId, long expectedVersion) {
        return jdbc.update("DELETE FROM device_sensing_profile WHERE device_id=? AND version=?", deviceId, expectedVersion);
    }

    public Map<String, Object> findIntegrationSource(String sourceId) {
        if (sourceId == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ops_integration_source WHERE source_id=?", sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findProtocolProfile(String deviceId, String protocolCode) {
        String table = switch (protocolCode == null ? "" : protocolCode) {
            case "RADAR_TCP_V3_0_0" -> "radar_v3_profile";
            case "COUNTERMEASURE_TCP_4CH_V2_0" -> "countermeasure_4ch_profile";
            default -> null;
        };
        if (table == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String,Object>> distinctTypes() {
        Map<String,Object> params=new HashMap<>();
        return named.queryForList("SELECT DISTINCT d.device_type_code,d.device_type_name FROM ops_device d"
                + " WHERE d.deleted_at IS NULL AND d.device_type_code IS NOT NULL AND d.device_type_code<>''"
                + deviceScope(params) + " ORDER BY d.device_type_name,d.device_type_code",params);
    }

    public List<String> distinct(String column) {
        String safe = switch (column) {
            case "type" -> "device_type_name";
            case "channel" -> "channel";
            case "region" -> "region_name";
            case "vendor" -> "vendor";
            default -> throw new IllegalArgumentException("unsupported option column");
        };
        Map<String,Object> params=new HashMap<>();
        return named.queryForList("SELECT DISTINCT " + safe + " FROM ops_device d WHERE " + safe
                + " IS NOT NULL AND " + safe + "<>'' AND d.deleted_at IS NULL" + deviceScope(params) + " ORDER BY " + safe,params,String.class);
    }

    public List<String> distinct(String column, boolean formalOnly) {
        if (!formalOnly) return distinct(column);
        String safe = switch (column) { case "type" -> "device_type_name"; case "channel" -> "channel"; case "region" -> "region_name"; case "vendor" -> "vendor"; default -> throw new IllegalArgumentException("unsupported option column"); };
        Map<String,Object> params=new HashMap<>();
        return named.queryForList("SELECT DISTINCT " + safe + " FROM ops_device d WHERE " + safe + " IS NOT NULL AND " + safe + "<>'' AND d.deleted_at IS NULL AND d.source_mode='live' AND d.simulated=FALSE" + deviceScope(params) + " ORDER BY " + safe,params,String.class);
    }

    public List<Map<String,Object>> distinctTypes(boolean formalOnly) {
        if (!formalOnly) return distinctTypes();
        Map<String,Object> params=readParameters();
        return named.queryForList("SELECT DISTINCT d.device_type_code,d.device_type_name FROM ops_device d WHERE d.deleted_at IS NULL AND d.device_type_code IS NOT NULL AND d.device_type_code<>'' AND d.source_mode='live' AND d.simulated=FALSE" + deviceScope(params) + " ORDER BY d.device_type_name,d.device_type_code",params);
    }

    public Map<String, Object> overview() {
        return overview(null);
    }

    public Map<String, Object> overview(String ownerOrgId) { return overview(ownerOrgId, false); }

    /** 设备计数的范围：全部；正式接入（live 且不是模拟设备）；统计口径（见 {@link StatisticsScope}）。 */
    public enum CountScope {
        ALL, FORMAL, STATISTICS;

        public static CountScope of(boolean formalOnly) { return formalOnly ? FORMAL : ALL; }
    }

    private String filter(CountScope scope) {
        return switch (scope) {
            case ALL -> "";
            case FORMAL -> " AND d.source_mode='live' AND d.simulated=FALSE";
            case STATISTICS -> " AND " + statistics.deviceSql("d");
        };
    }

    public Map<String, Object> overview(String ownerOrgId, boolean formalOnly) {
        return overview(ownerOrgId, CountScope.of(formalOnly), null);
    }

    public Map<String, Object> overview(String ownerOrgId, boolean formalOnly, Boolean enabled) {
        return overview(ownerOrgId, CountScope.of(formalOnly), enabled);
    }

    public Map<String, Object> overview(String ownerOrgId, CountScope scope, Boolean enabled) {
        return overview(ownerOrgId, new DeviceQuery(null, null, null, null, null, null, enabled,
                scope == CountScope.FORMAL, scope == CountScope.STATISTICS));
    }

    public Map<String, Object> overview(String ownerOrgId, DeviceQuery query) {
        SqlWhere where = where(query);
        Map<String,Object> params=where.params;
        String ownerFilter = "";
        if (ownerOrgId != null) {
            params.put("overview_org_id", ownerOrgId);
            ownerFilter = " AND EXISTS (SELECT 1 FROM device_business_scope selected_scope WHERE selected_scope.ops_device_id=d.device_id AND selected_scope.owner_org_id=:overview_org_id)";
        }
        return named.queryForMap("""
                SELECT COUNT(*) AS total, %s,
                       SUM(CASE WHEN s.has_alarm=TRUE THEN 1 ELSE 0 END) AS alarm,
                       COUNT(DISTINCT CASE WHEN d.vendor IS NOT NULL AND d.vendor<>'' THEN d.vendor END) AS vendor_count,
                       COUNT(DISTINCT CASE WHEN d.model IS NOT NULL AND d.model<>'' THEN d.model END) AS model_count
                       ,SUM(CASE WHEN d.source_mode='live' THEN 1 ELSE 0 END) AS live_count
                       ,SUM(CASE WHEN d.simulated=TRUE THEN 1 ELSE 0 END) AS simulated_count
                FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id
                """.formatted(CONNECTIVITY_COUNTS) + where.sql + ownerFilter,params);
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn) {
        Map<String,Object> params=readParameters();
        String safe = "channel".equals(groupColumn) ? "d.channel" : "d.device_type_name";
        return named.queryForList("SELECT " + safe + " AS group_name, COUNT(*) AS total, "
                + CONNECTIVITY_COUNTS
                + "FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id "
                + "WHERE d.deleted_at IS NULL" + deviceScope(params) + " GROUP BY " + safe + " ORDER BY " + safe,params);
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn, boolean formalOnly) {
        return overviewGroups(groupColumn, formalOnly, null);
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn, boolean formalOnly, Boolean enabled) {
        return overviewGroups(groupColumn, CountScope.of(formalOnly), enabled);
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn, CountScope scope, Boolean enabled) {
        return overviewGroups(groupColumn, new DeviceQuery(null, null, null, null, null, null, enabled,
                scope == CountScope.FORMAL, scope == CountScope.STATISTICS));
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn, DeviceQuery query) {
        SqlWhere where = where(query);
        String safe = "channel".equals(groupColumn) ? "d.channel" : "d.device_type_name";
        return named.queryForList("SELECT " + safe + " AS group_name, COUNT(*) AS total, " + CONNECTIVITY_COUNTS
                + "FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id" + where.sql
                + " GROUP BY " + safe + " ORDER BY " + safe,where.params);
    }

    public void insertDevice(Map<String, Object> values) {
        named.update("""
                INSERT INTO ops_device (
                    device_id, source_id, external_device_id, device_no, name, device_type_code,
                    device_type_name, channel, model, vendor, owner_name, region_name, address,
                    longitude, latitude, coordinate_system, altitude_m, altitude_datum,
                    firmware_version, installed_at, enabled, source_mode, simulated, version,
                    created_at, updated_at)
                VALUES (
                    :device_id, :source_id, :external_device_id, :device_no, :name, :device_type_code,
                    :device_type_name, :channel, :model, :vendor, :owner_name, :region_name, :address,
                    :longitude, :latitude, :coordinate_system, :altitude_m, :altitude_datum,
                    :firmware_version, :installed_at, TRUE, :source_mode, :simulated, 0,
                    :created_at, :updated_at)
                """, values);
    }

    public int updateDevice(String deviceId, long expectedVersion, Map<String, Object> values) {
        Map<String, Object> p = new HashMap<>(values);
        p.put("device_id", deviceId);
        p.put("expected_version", expectedVersion);
        return named.update("""
                UPDATE ops_device SET source_id=:source_id, external_device_id=:external_device_id,
                    device_no=:device_no, name=:name, device_type_code=:device_type_code,
                    device_type_name=:device_type_name, channel=:channel, model=:model, vendor=:vendor,
                    owner_name=:owner_name, region_name=:region_name, address=:address,
                    longitude=:longitude, latitude=:latitude, coordinate_system=:coordinate_system,
                    altitude_m=:altitude_m, altitude_datum=:altitude_datum,
                    firmware_version=:firmware_version, installed_at=:installed_at,
                    source_mode=:source_mode, simulated=:simulated,
                    version=version+1, updated_at=:updated_at
                WHERE device_id=:device_id AND version=:expected_version AND deleted_at IS NULL
                """, p);
    }

    public int setEnabled(String deviceId, long expectedVersion, boolean enabled, long now) {
        return jdbc.update("UPDATE ops_device SET enabled=?, version=version+1, updated_at=? WHERE device_id=? AND version=? AND deleted_at IS NULL",
                enabled, now, deviceId, expectedVersion);
    }

    public void upsertProfile(String deviceId, Map<String, Object> p, long now) {
        jdbc.update("DELETE FROM device_connection_profile WHERE device_id=?", deviceId);
        Map<String, Object> values = new HashMap<>(p);
        values.put("device_id", deviceId);
        values.put("updated_at", now);
        named.update("""
                INSERT INTO device_connection_profile (
                    device_id, transport, host, port, path, data_format, charset_name, auth_mode, credential_ref,
                    heartbeat_interval_seconds, report_interval_millis, sampling_rate_hz,
                    compression_enabled, retransmission_enabled, timeout_millis, retry_count,
                    longitude_offset_deg, latitude_offset_deg, altitude_offset_m, time_sync_mode,
                    time_server, timezone_name, time_sync_interval_seconds, version, updated_at)
                VALUES (
                    :device_id, :transport, :host, :port, :path, :data_format, :charset_name, :auth_mode, :credential_ref,
                    :heartbeat_interval_seconds, :report_interval_millis, :sampling_rate_hz,
                    :compression_enabled, :retransmission_enabled, :timeout_millis, :retry_count,
                    :longitude_offset_deg, :latitude_offset_deg, :altitude_offset_m, :time_sync_mode,
                    :time_server, :timezone_name, :time_sync_interval_seconds, 0, :updated_at)
                """, values);
    }

    public void replaceProtocolProfile(String deviceId, String protocolCode, Map<String, Object> values, long now) {
        jdbc.update("DELETE FROM radar_v3_profile WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM countermeasure_4ch_profile WHERE device_id=?", deviceId);
        if ("RADAR_TCP_V3_0_0".equals(protocolCode)) {
            named.update("""
                    INSERT INTO radar_v3_profile (device_id,login_role,recognition_code_ref,rtk_enabled,
                        coordinate_transform_enabled,version,updated_at)
                    VALUES (:device_id,:login_role,:recognition_code_ref,:rtk_enabled,
                        :coordinate_transform_enabled,0,:updated_at)
                    """, withDevice(values, deviceId, now));
        } else if ("COUNTERMEASURE_TCP_4CH_V2_0".equals(protocolCode)) {
            named.update("""
                    INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,
                        poll_interval_millis,version,updated_at)
                    VALUES (:device_id,:device_address,:wire_encoding,:poll_interval_millis,0,:updated_at)
                    """, withDevice(values, deviceId, now));
        }
    }

    private static Map<String, Object> withDevice(Map<String, Object> values, String deviceId, long now) {
        Map<String, Object> out = new HashMap<>(values);
        out.put("device_id", deviceId);
        out.put("updated_at", now);
        return out;
    }

    public List<Map<String, Object>> stateHistory(String deviceId, String metricCode, long from, long to, int limit) {
        return named.queryForList("""
                SELECT state_id, device_id, connectivity, observed_at, received_at,
                       metric_code, metric_value, metric_unit, simulated
                FROM ops_device_state_history
                WHERE device_id=:device_id AND metric_code=:metric_code
                  AND received_at>=:from_time AND received_at<=:to_time
                ORDER BY received_at DESC, state_id DESC OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("device_id", deviceId, "metric_code", metricCode, "from_time", from, "to_time", to, "limit", limit));
    }

    /** 已删除设备的异常不再进入处理队列（与设备事件一致）；记录本身保留。 */
    public List<Map<String, Object>> incidents(String deviceId, String severity, String stage, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT i.*, d.device_no, d.name AS device_name FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id WHERE d.deleted_at IS NULL");
        sql.append(deviceScope(p));
        add(sql, p, "i.device_id", "device_id", deviceId);
        add(sql, p, "i.severity", "severity", severity);
        add(sql, p, "i.stage", "stage", stage);
        p.put("offset", offset); p.put("size", size);
        sql.append(" ORDER BY i.detected_at DESC, i.incident_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY");
        return named.queryForList(sql.toString(), p);
    }

    public long countIncidents(String deviceId, String severity, String stage) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id WHERE d.deleted_at IS NULL");
        sql.append(deviceScope(p));
        add(sql, p, "i.device_id", "device_id", deviceId);
        add(sql, p, "i.severity", "severity", severity);
        add(sql, p, "i.stage", "stage", stage);
        Long count = named.queryForObject(sql.toString(), p, Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> events(String deviceId, long afterSeq, int limit) {
        return events(deviceId, afterSeq, limit, false);
    }

    public List<Map<String, Object>> events(String deviceId, long afterSeq, int limit, boolean latest) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT publication.event_seq AS event_seq,e.event_id,e.device_id,e.event_type,e.level_code,
                    e.message,e.occurred_at,e.simulated,d.device_no,d.name AS device_name
                FROM device_event_log e JOIN device_event_publication publication ON publication.raw_event_seq=e.event_seq
                JOIN ops_device d ON d.device_id=e.device_id
                WHERE d.deleted_at IS NULL AND publication.event_seq>:after_seq
                """);
        p.put("after_seq", afterSeq); p.put("limit", limit);
        add(sql, p, "e.device_id", "device_id", deviceId);
        sql.append(eventScope(p));
        boolean recent = latest && afterSeq == 0;
        sql.append(recent ? " ORDER BY publication.event_seq DESC" : " ORDER BY publication.event_seq");
        sql.append(" OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY");
        String query = recent ? "SELECT * FROM (" + sql + ") recent_events ORDER BY event_seq" : sql.toString();
        return named.queryForList(query, p);
    }

    public boolean eventDeviceVisible(String deviceId) {
        Map<String, Object> p = new HashMap<>();
        p.put("device_id", deviceId);
        return named.queryForObject("SELECT COUNT(*) FROM ops_device d WHERE d.device_id=:device_id AND d.deleted_at IS NULL"
                + eventScope(p), p, Long.class) > 0;
    }

    // Monitoring logs use the same explicit, enabled organization/district tuple as device information.
    private String eventScope(Map<String, Object> params) {
        if(managementOrg()!=null) return unitDeviceScope(params);
        var actor = com.uav.lowaltitude.platform.security.AuthContext.get();
        if (actor == null) return " AND 1=0";
        if ("ALL".equals(actor.scopeMode())) return "";
        if (!"ASSIGNED".equals(actor.scopeMode())) return " AND 1=0";
        params.put("event_scope_user", actor.userId());
        return """
                 AND EXISTS (SELECT 1 FROM device_business_scope bs
                 JOIN app_user_data_scope us ON us.org_id=bs.owner_org_id AND us.district_id=bs.district_id
                 JOIN app_org o ON o.org_id=bs.owner_org_id AND o.enabled=TRUE
                 JOIN app_district district ON district.district_id=bs.district_id AND district.enabled=TRUE
                 WHERE bs.ops_device_id=d.device_id AND us.user_id=:event_scope_user)
                """;
    }

    public void addEvent(String eventId, String deviceId, String type, String level, String message, long now, boolean simulated) {
        jdbc.update("INSERT INTO device_event_log (event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                eventId, deviceId, type, level, message, now, simulated);
    }

    public Map<String, Object> findIdempotency(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM idempotency_request WHERE idem_key=?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertIdempotency(String key, String userId, String hash, String responseBody, long now) {
        jdbc.update("INSERT INTO idempotency_request (idem_key,user_id,request_hash,response_body,created_at) VALUES (?,?,?,?,?)",
                key, userId, hash, responseBody, now);
    }

    public void insertCommand(Map<String, Object> p) {
        named.update("""
                INSERT INTO device_command (command_id,command_no,device_id,requested_by,command_type,reason,status,
                    source_mode,simulated,deadline_at,created_at,updated_at)
                VALUES (:command_id,:command_no,:device_id,:requested_by,'REBOOT',:reason,'QUEUED',
                    :source_mode,:simulated,:deadline_at,:created_at,:updated_at)
                """, p);
    }

    public Map<String, Object> findCommand(String commandId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.*, d.device_no, d.name AS device_name
                FROM device_command c JOIN ops_device d ON d.device_id=c.device_id WHERE c.command_id=?
                """, commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> commandReceipts(String commandId) {
        return jdbc.queryForList("""
                SELECT receipt_id,receipt_kind,device_result_code,occurred_at,received_at,payload
                FROM command_receipt WHERE command_id=? ORDER BY received_at,receipt_id
                """, commandId);
    }

    public List<Map<String, Object>> expiredCommands(long now, int limit) {
        return jdbc.queryForList("""
                SELECT c.command_id,c.device_id,c.status,c.command_type FROM device_command c
                WHERE c.status IN ('QUEUED','SENT','ACCEPTED') AND c.deadline_at IS NOT NULL AND c.deadline_at<?
                ORDER BY c.deadline_at OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """, now, limit);
    }

    public int updateCommand(String commandId, String expectedStatus, String status, long now, String code, String detail) {
        return jdbc.update("""
                UPDATE device_command SET status=?, issued_at=CASE WHEN ?='SENT' THEN ? ELSE issued_at END,
                    completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN ? ELSE completed_at END,
                    result_code=?, result_detail=?, updated_at=?
                WHERE command_id=? AND status=?
                """, status, status, now, status, now, code, detail, now, commandId, expectedStatus);
    }

    public void addOutbox(String id, String topic, String payload, long now, long availableAt) {
        jdbc.update("INSERT INTO outbox_event (outbox_id,topic,payload,created_at,available_at,attempt_count) VALUES (?,?,?,?,?,0)",
                id, topic, payload, now, availableAt);
    }

    public List<Map<String, Object>> dueOutbox(long now, int limit) {
        return jdbc.queryForList("SELECT * FROM outbox_event WHERE processed_at IS NULL AND available_at<=? AND topic IN ('device.reboot','commission.connect','commission.run','eo.track.begin','eo.track.end','eo.camera.status','device.control.lingyun','device.control.countermeasure') ORDER BY available_at,created_at OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
                now, limit);
    }

    public int claimOutbox(String id, long oldAvailableAt, long leaseUntil) {
        return jdbc.update("UPDATE outbox_event SET available_at=?, attempt_count=attempt_count+1 WHERE outbox_id=? AND processed_at IS NULL AND available_at=?",
                leaseUntil, id, oldAvailableAt);
    }

    public void completeOutbox(String id, long now) {
        jdbc.update("UPDATE outbox_event SET processed_at=?, last_error=NULL WHERE outbox_id=?", now, id);
    }

    public void failOutbox(String id, long nextAt, String error) {
        jdbc.update("UPDATE outbox_event SET available_at=?, last_error=? WHERE outbox_id=? AND processed_at IS NULL",
                nextAt, error == null ? "adapter failure" : error.substring(0, Math.min(error.length(), 500)), id);
    }

    public void addReceipt(String receiptId, String commandId, String inboxId, String resultCode, long now, String payload) {
        jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at) VALUES (?,?,?,?)",
                inboxId, "mock-device-adapter", "receipt:" + commandId, now);
        jdbc.update("INSERT INTO command_receipt (receipt_id,command_id,inbox_id,receipt_kind,device_result_code,occurred_at,received_at,payload) VALUES (?,?,?,?,?,?,?,?)",
                receiptId, commandId, inboxId, "COMPLETED", resultCode, now, now, payload);
    }

    public Map<String, Object> findIncident(String incidentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.*, d.device_no, d.name AS device_name
                FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id
                WHERE i.incident_id=?
                """, incidentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Catalog before state, consistent with recovery and ingress. Never-seen or disabled devices are not offline incidents. */
    public List<Map<String, Object>> lockMqttConnectivity() {
        var candidates=jdbc.queryForList("""
                SELECT s.device_id,s.connectivity,s.last_heartbeat_at,d.source_mode
                FROM ops_device_state s JOIN ops_device d ON d.device_id=s.device_id
                WHERE d.enabled=TRUE AND s.last_heartbeat_at IS NOT NULL
                  AND (EXISTS (SELECT 1 FROM mqtt_device_binding b JOIN mqtt_broker m ON m.broker_id=b.broker_id
                               WHERE b.ops_device_id=d.device_id AND m.enabled=TRUE)
                    OR EXISTS (SELECT 1 FROM eo_device_binding b JOIN mqtt_broker m ON m.broker_id=b.broker_id
                               WHERE b.ops_device_id=d.device_id AND m.enabled=TRUE))
                ORDER BY s.device_id
                """);
        var locked=new java.util.ArrayList<Map<String,Object>>();
        for(var row:candidates) {
            String id=String.valueOf(row.get("device_id"));
            lockForRecovery(id);
            var current=findForRecovery(id);
            if(current!=null&&Boolean.TRUE.equals(current.get("enabled"))&&current.get("last_heartbeat_at")!=null)
                locked.add(current);
        }
        return locked;
    }

    /** 模拟器正常停止时写入的设备事件类型（见 LocalQaDeviceStatusService.plannedOffline）。 */
    public static final String PLANNED_OFFLINE_EVENT = "PLANNED_OFFLINE";
    /** 声明前已发出、稍后才到平台的心跳不结束计划下线；超过这段时间后收到的心跳视为设备重新开始上报。 */
    static final long PLANNED_OFFLINE_HEARTBEAT_GRACE_MILLIS = 5_000;

    /**
     * 回放模拟设备是否处于计划下线：模拟器正常停止时声明过计划下线，且此后没有再收到心跳。
     * 只认标明模拟的回放设备和模拟事件；真实设备、模拟器崩溃（没有声明）或重新上报后再停发，都按原规则开单。
     */
    public boolean plannedOffline(String deviceId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_event_log e
                JOIN ops_device d ON d.device_id=e.device_id
                JOIN ops_device_state s ON s.device_id=d.device_id
                WHERE e.device_id=? AND e.event_type=? AND e.simulated=TRUE
                  AND d.simulated=TRUE AND d.source_mode='replay' AND d.deleted_at IS NULL
                  AND s.last_heartbeat_at IS NOT NULL AND e.occurred_at>=s.last_heartbeat_at-?
                """, Long.class, deviceId, PLANNED_OFFLINE_EVENT, PLANNED_OFFLINE_HEARTBEAT_GRACE_MILLIS) > 0;
    }

    public boolean openMqttIncident(String deviceId, long now, boolean simulated) {
        String id = UUID.randomUUID().toString();
        return jdbc.update("""
                INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated,block_reason)
                SELECT ?,?,?,'MQTT_HEARTBEAT_TIMEOUT','HIGH','PENDING',?,?,?,?
                WHERE NOT EXISTS (SELECT 1 FROM device_incident WHERE device_id=?
                    AND incident_type='MQTT_HEARTBEAT_TIMEOUT' AND stage<>'RECOVERED')
                """, id, deviceId, "INC-" + id.replace("-", ""), now,
                "有效 MQTT 心跳超时；请检查设备电源、网络和发布进程", simulated,
                "协议未定义重启指令；恢复心跳后执行平台恢复核验", deviceId) == 1;
    }

    public int startIncidentReboot(String incidentId, String commandId) {
        return jdbc.update("""
                UPDATE device_incident SET stage='PROCESSING', reboot_command_id=?, block_reason=NULL
                WHERE incident_id=? AND stage='PENDING'
                """, commandId, incidentId);
    }

    public int advanceIncidentByCommand(String commandId, String fromStage, String toStage) {
        return jdbc.update("UPDATE device_incident SET stage=? WHERE reboot_command_id=? AND stage=?",
                toStage, commandId, fromStage);
    }

    public int closeIncident(String incidentId, String expectedStage, long closedAt) {
        return jdbc.update("UPDATE device_incident SET stage='RECOVERED', closed_at=? WHERE incident_id=? AND stage=?",
                closedAt, incidentId, expectedStage);
    }

    public void insertRecoveryCheck(String checkId, String incidentId, String checkedBy, long checkedAt,
                                    String result, String ruleVersion, String snapshot, String reason) {
        jdbc.update("""
                INSERT INTO device_recovery_check (check_id,incident_id,checked_by,checked_at,result,rule_version,state_snapshot,reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, checkId, incidentId, checkedBy, checkedAt, result, ruleVersion, snapshot, reason);
    }

    public void insertSystemRecoveryCheck(String checkId,String incidentId,long checkedAt,String ruleVersion,
                                          String snapshot,String reason) {
        jdbc.update("""
                INSERT INTO device_recovery_check(check_id,incident_id,checked_by,checked_at,result,rule_version,
                    state_snapshot,reason,actor_type) VALUES (?,?,NULL,?,'PASS',?,?,?,'SYSTEM')
                """,checkId,incidentId,checkedAt,ruleVersion,snapshot,reason);
    }

    public Map<String, Object> latestRecoveryCheck(String incidentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM device_recovery_check WHERE incident_id=?
                ORDER BY checked_at DESC, check_id DESC OFFSET 0 ROWS FETCH FIRST 1 ROW ONLY
                """, incidentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findRecoveryCheck(String checkId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM device_recovery_check WHERE check_id=?", checkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void requireSourceInUnit(String source,String org) {
        Long outside=jdbc.queryForObject("SELECT COUNT(*) FROM ops_device d WHERE d.source_id=? AND NOT EXISTS(SELECT 1 FROM device_business_scope bs WHERE bs.ops_device_id=d.device_id AND bs.owner_org_id=?)",Long.class,source,org);
        if(source==null || outside==null || outside>0) throw new com.uav.lowaltitude.platform.api.ApiException(HttpStatus.FORBIDDEN,"UNIT_SOURCE_FORBIDDEN","接入来源关联了本单位外的设备，请由平台维护");
    }

    public boolean canDeleteInScope(String id, String userId, String scopeMode) {
        if(managementOrg()!=null) {
            Map<String,Object> p=new HashMap<>(); p.put("unit_device_id",id);
            return named.queryForObject("SELECT COUNT(*) FROM ops_device d WHERE d.device_id=:unit_device_id"+unitDeviceScope(p),p,Long.class)>0;
        }
        if ("ALL".equals(scopeMode)) return true;
        if (!"ASSIGNED".equals(scopeMode)) return false;
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_business_scope bs
                JOIN app_user_data_scope us ON us.org_id=bs.owner_org_id AND us.district_id=bs.district_id
                JOIN app_org o ON o.org_id=bs.owner_org_id AND o.enabled=TRUE
                JOIN app_district dd ON dd.district_id=bs.district_id AND dd.enabled=TRUE
                WHERE bs.ops_device_id=? AND us.user_id=?
                """, Long.class, id, userId) > 0;
    }

    public int markDeleted(String id, long version, long now) {
        return jdbc.update("""
                UPDATE ops_device SET deleted_at=?,updated_at=?,version=version+1
                WHERE device_id=? AND version=? AND enabled=FALSE AND deleted_at IS NULL
                """, now, now, id, version);
    }

    /**
     * 目标所在机构、区域内的光电设备：台账总数与此刻在线台数（ZT-18：没有跟踪任务时说清光电本身的状况）。
     * 与光电跟踪选设备同一口径（按目标的机构与区域），不看调用者的设备范围。
     */
    public EoAvailability eoAvailability(String orgId, String districtId) {
        if (orgId == null || districtId == null) return new EoAvailability(0, 0);
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN d.enabled=TRUE AND st.connectivity='ONLINE' THEN 1 ELSE 0 END),0) AS online
                FROM ops_device d JOIN device_business_scope bs ON bs.ops_device_id=d.device_id
                LEFT JOIN ops_device_state st ON st.device_id=d.device_id
                WHERE d.deleted_at IS NULL AND UPPER(d.device_type_code) IN ('EO','OE')
                  AND bs.owner_org_id=? AND bs.district_id=?
                """, (rs, i) -> new EoAvailability(rs.getInt("total"), rs.getInt("online")), orgId, districtId);
    }

    public record EoAvailability(int total, int online) { }

    public boolean hasActiveWork(String id) {
        return hasActiveWork(id, false);
    }

    /** Only catalog retirement may distinguish inert mock snapshots from dispatched work.
     * Keep the stricter guard for control, recovery and maintenance. Never change the
     * old command/receipt into a successful or cancelled real operation.
     */
    public boolean hasCatalogBlockingWork(String id) {
        return !catalogBlockingReasons(id).isEmpty();
    }

    /** 挡住退役（删除）的未完成工作，逐类写明；与 hasCatalogBlockingWork 同一口径，没有则为空。 */
    public List<String> catalogBlockingReasons(String id) {
        return activeWork(id, statistics.counted("replay")).reasons();
    }

    private boolean hasActiveWork(String id, boolean allowMockSnapshots) {
        return !activeWork(id, allowMockSnapshots).reasons().isEmpty();
    }

    private ActiveWork activeWork(String id, boolean allowMockSnapshots) {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM device_command c WHERE c.device_id=? AND c.status IN ('QUEUED','SENT','ACCEPTED')
                        AND (?=FALSE OR NOT (c.source_mode='mock' AND c.simulated=TRUE AND c.command_type='REBOOT'
                            AND c.deadline_at IS NULL
                            AND EXISTS (SELECT 1 FROM ops_device d LEFT JOIN ops_integration_source s ON s.source_id=d.source_id
                                WHERE d.device_id=c.device_id AND d.source_mode='mock' AND d.simulated=TRUE
                                AND (d.source_id IS NULL OR (s.source_mode='mock' AND s.simulated=TRUE AND s.protocol_code IS NULL)))
                            AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.payload=c.command_id)))) AS commands,
                       (SELECT COUNT(*) FROM commission_task WHERE device_id=? AND status IN ('CREATED','CONNECTING','CONNECTED','READY','RUNNING')) AS commissions,
                       (SELECT COUNT(*) FROM eo_tracking_task WHERE ops_device_id=? AND status IN ('OPEN','ENDING')) AS eo_tasks,
                       (SELECT COUNT(*) FROM disposal_device_run r JOIN disposal_authorization a
                        ON a.authorization_id=r.authorization_id WHERE r.device_id=? AND a.status='EXECUTING')
                     + (SELECT COUNT(*) FROM disposal_authorization a WHERE a.device_id=? AND (a.status='EXECUTING' OR (%s))) AS disposals
                """.formatted(com.uav.lowaltitude.modules.disposal.infrastructure.DisposalSafetySql.UNCERTAIN_START),
                (rs, row) -> new ActiveWork(rs.getLong("commands"), rs.getLong("commissions"), rs.getLong("eo_tasks"),
                rs.getLong("disposals")), id, allowMockSnapshots, id, id, id, id);
    }

    private record ActiveWork(long commands, long commissions, long eoTasks, long disposals) {
        List<String> reasons() {
            List<String> reasons = new ArrayList<>();
            if (commands > 0) reasons.add("有未完成的设备指令");
            if (commissions > 0) reasons.add("有进行中的调测任务");
            if (eoTasks > 0) reasons.add("光电跟踪任务还没结束");
            if (disposals > 0) reasons.add("反制正在执行");
            return reasons;
        }
    }

    /** 同编号的退役（已删除）设备；各登记入口写库前用它把“编号已退役”说清。 */
    public Map<String, Object> retiredByDeviceNo(String deviceNo) {
        return jdbc.queryForList("SELECT device_no,name,source_mode,simulated FROM ops_device WHERE device_no=? AND deleted_at IS NOT NULL",
                deviceNo).stream().findFirst().orElse(null);
    }

    /** 按设备编号锁住设备行（含已退役即已删除的记录），只供恢复模拟设备用。 */
    public Map<String, Object> lockByDeviceNoIncludingRetired(String deviceNo) {
        jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_no=? FOR UPDATE", deviceNo);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.device_id,d.device_no,d.name,d.source_mode,d.simulated,d.enabled,d.deleted_at,d.version,
                       bs.owner_org_id,bs.district_id
                FROM ops_device d LEFT JOIN device_business_scope bs ON bs.ops_device_id=d.device_id
                WHERE d.device_no=?
                """, deviceNo);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    /** 撤销退役：同一条设备记录回到台账，历史不动；只对回放模拟设备生效，启用另走各协议的启用。 */
    public int restoreRetiredSimulated(String id, long version, long now) {
        return jdbc.update("""
                UPDATE ops_device SET deleted_at=NULL,updated_at=?,version=version+1
                WHERE device_id=? AND version=? AND deleted_at IS NOT NULL AND source_mode='replay' AND simulated=TRUE
                """, now, id, version);
    }

    /** Recovery is not an idle-device check. Only confirmed, fresh AUTO tracking may continue during recovery. */
    public boolean hasRecoveryBlockingWork(String id,long reportCutoff,long now) {
        return Boolean.TRUE.equals(named.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM device_command WHERE device_id=:device
                    AND status IN ('QUEUED','SENT','ACCEPTED'))
                OR EXISTS (SELECT 1 FROM commission_task WHERE device_id=:device
                    AND status IN ('CREATED','CONNECTING','CONNECTED','READY','RUNNING'))
                OR EXISTS (SELECT 1 FROM disposal_device_run r JOIN disposal_authorization a
                    ON a.authorization_id=r.authorization_id WHERE r.device_id=:device AND a.status='EXECUTING')
                OR EXISTS (SELECT 1 FROM disposal_authorization WHERE device_id=:device AND status='EXECUTING')
                OR EXISTS (SELECT 1 FROM disposal_authorization a WHERE a.device_id=:device AND (
                """ + com.uav.lowaltitude.modules.disposal.infrastructure.DisposalSafetySql.UNCERTAIN_START + "))" + """
                OR EXISTS (
                    SELECT 1 FROM eo_tracking_task t JOIN ops_device d ON d.device_id=t.ops_device_id
                    LEFT JOIN eo_device_binding b ON b.ops_device_id=d.device_id
                    LEFT JOIN device_command bc ON bc.command_id=t.begin_command_id
                    LEFT JOIN device_command ec ON ec.command_id=t.end_command_id
                    WHERE t.ops_device_id=:device AND (
                        (t.status IN ('OPEN','ENDING') AND NOT COALESCE(
                            t.status='OPEN' AND t.origin='AUTO' AND t.end_command_id IS NULL AND t.ended_at IS NULL
                            AND bc.status='SUCCEEDED' AND bc.command_type='EO_BEGIN_TRACK'
                            AND bc.device_id=d.device_id AND bc.source_mode=d.source_mode AND bc.simulated=d.simulated
                            AND b.ops_source_id=d.source_id AND b.source_mode=d.source_mode
                            AND b.external_device_id=d.external_device_id
                            AND t.last_report_at BETWEEN :cutoff AND :now
                            AND t.last_report_observed_at BETWEEN :cutoff AND :now
                            AND (t.last_failure_observed_at IS NULL OR t.last_report_observed_at>t.last_failure_observed_at)
                            AND t.last_report_at>=t.created_at AND t.last_report_observed_at>=t.created_at,
                            FALSE)) OR (
                """ + EoEdgeRepository.UNCERTAIN + ")))",
                Map.of("device",id,"cutoff",reportCutoff,"now",now),Boolean.class));
    }

    private SqlWhere where(DeviceQuery q) {
        Map<String, Object> p = readParameters();
        StringBuilder sql = new StringBuilder(" WHERE d.deleted_at IS NULL");
        sql.append(filter(q.statisticsScope() ? CountScope.STATISTICS : CountScope.of(q.formalOnly())));
        sql.append(deviceScope(p));
        if (q.keyword != null && !q.keyword.isBlank()) {
            sql.append(" AND (LOWER(d.device_no) LIKE :keyword OR LOWER(d.name) LIKE :keyword)");
            p.put("keyword", "%" + q.keyword.toLowerCase() + "%");
        }
        add(sql, p, "d.device_type_code", "type_code", q.typeCode);
        add(sql, p, "d.channel", "channel", q.channel);
        add(sql, p, "d.region_name", "region", q.region);
        add(sql, p, "d.vendor", "vendor", q.vendor);
        add(sql, p, EFFECTIVE_CONNECTIVITY, "connectivity", q.connectivity);
        if (q.enabled != null) {
            sql.append(" AND d.enabled=:enabled");
            p.put("enabled", q.enabled);
        }
        return new SqlWhere(sql.toString(), p);
    }

    private static void add(StringBuilder sql, Map<String, Object> p, String column, String key, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append("=:").append(key);
        p.put(key, value);
    }

    /** 同一机构区域内、已启用且在线的反制设备。协议码、凌云类型和是否只取模拟设备按调用方需要附加。 */
    public List<String> operableCounterDevices(String typeCode, String sourceMode, String orgId, String districtId,
            String protocolCode, String bindingAbbr, boolean simulatedOnly) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", typeCode);
        params.put("mode", sourceMode);
        params.put("org", orgId);
        params.put("district", districtId);
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT d.device_id FROM ops_device d
                JOIN ops_device_state st ON st.device_id=d.device_id
                JOIN device_business_scope scope ON scope.ops_device_id=d.device_id
                LEFT JOIN ops_integration_source src ON src.source_id=d.source_id
                LEFT JOIN mqtt_device_binding mqtt ON mqtt.ops_device_id=d.device_id
                WHERE d.deleted_at IS NULL AND d.enabled=TRUE AND st.connectivity='ONLINE'
                  AND d.device_type_code=:type AND d.source_mode=:mode
                  AND scope.owner_org_id=:org AND scope.district_id=:district
                """);
        if (protocolCode != null) {
            sql.append(" AND src.protocol_code=:protocol");
            params.put("protocol", protocolCode);
        }
        if (bindingAbbr != null) {
            sql.append(" AND mqtt.device_type_abbr=:abbr");
            params.put("abbr", bindingAbbr);
        }
        if (simulatedOnly) sql.append(" AND d.simulated=TRUE");
        sql.append(" ORDER BY d.device_id");
        return named.query(sql.toString(), params, (rs, row) -> rs.getString(1));
    }

    /** Background dispatch must use the original actor's current scope, without a thread-bound session. */
    public Map<String, Object> findForActor(String deviceId, com.uav.lowaltitude.platform.security.AuthUser actor) {
        if (actor == null) return null;
        Map<String,Object> params = readParameters();
        params.put("device_id", deviceId);
        var rows = named.queryForList(DEVICE_SELECT + " WHERE d.device_id=:device_id AND d.deleted_at IS NULL"
                + deviceScope(params, actor), params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // All device protocols share the same explicit organization/district authorization tuple.
    private static String managementOrg() {
        String org = com.uav.lowaltitude.platform.security.UnitOperationsContext.orgId();
        return org != null ? org : com.uav.lowaltitude.platform.report.ReportAccessContext.orgId();
    }

    private static String unitDeviceScope(Map<String,Object> params) {
        params.put("unit_device_org", managementOrg());
        return " AND EXISTS(SELECT 1 FROM device_business_scope bs JOIN app_org o ON o.org_id=bs.owner_org_id AND o.enabled=TRUE JOIN app_district dd ON dd.district_id=bs.district_id AND dd.enabled=TRUE WHERE bs.ops_device_id=d.device_id AND bs.owner_org_id=:unit_device_org)";
    }

    private String deviceScope(Map<String,Object> params) {
        return deviceScope(params, com.uav.lowaltitude.platform.security.AuthContext.get());
    }

    private String deviceScope(Map<String,Object> params, com.uav.lowaltitude.platform.security.AuthUser actor) {
        if(managementOrg()!=null) return unitDeviceScope(params);
        if(actor==null || "ALL".equals(actor.scopeMode())) return "";
        if(!"ASSIGNED".equals(actor.scopeMode())) return " AND 1=0";
        params.put("device_scope_actor",actor.userId());
        return """
                 AND EXISTS(SELECT 1 FROM device_business_scope bs JOIN app_user_data_scope us
                     ON us.org_id=bs.owner_org_id AND us.district_id=bs.district_id
                     JOIN app_org org ON org.org_id=bs.owner_org_id AND org.enabled=TRUE
                     JOIN app_district district ON district.district_id=bs.district_id AND district.enabled=TRUE
                     WHERE bs.ops_device_id=d.device_id AND us.user_id=:device_scope_actor)
                """;
    }

    public record DeviceQuery(String keyword, String typeCode, String channel, String region,
                              String vendor, String connectivity, Boolean enabled, boolean formalOnly, boolean statisticsScope) {
        public DeviceQuery(String keyword, String typeCode, String channel, String region,
                           String vendor, String connectivity, Boolean enabled, boolean formalOnly) {
            this(keyword, typeCode, channel, region, vendor, connectivity, enabled, formalOnly, false);
        }
    }

    private record SqlWhere(String sql, Map<String, Object> params) {
    }
}
