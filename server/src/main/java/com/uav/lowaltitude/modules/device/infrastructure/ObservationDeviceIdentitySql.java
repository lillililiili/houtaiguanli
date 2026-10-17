package com.uav.lowaltitude.modules.device.infrastructure;

/** Explicit protocol registrations that identify one enabled physical device in both catalogs. */
public final class ObservationDeviceIdentitySql {
    private ObservationDeviceIdentitySql() { }

    public static final String PHYSICAL_IDENTITIES = """
            SELECT DISTINCT f.device_id,f.source_id,d.device_id AS ops_device_id,
              scope.owner_org_id,scope.district_id,f.source_mode,
              (d.simulated=TRUE AND os.simulated=TRUE) AS simulated
            FROM (
              SELECT device_id,source_id,ops_device_id,ops_source_id,external_device_id,source_mode,
                'LINGYUN_MQTT_V8_6' AS protocol_code FROM mqtt_device_binding
              UNION ALL
              SELECT device_id,source_id,ops_device_id,ops_source_id,external_device_id,source_mode,
                'EO_EDGE_MQTT_20250826' AS protocol_code FROM eo_device_binding
              UNION ALL
              SELECT f.device_id,f.source_id,d.device_id,d.source_id,d.external_device_id,d.source_mode,
                'RADAR_TCP_V3_0_0' AS protocol_code
              FROM ops_device d JOIN ops_integration_source os ON os.source_id=d.source_id
              JOIN device f ON f.device_id=d.device_id
              JOIN integration_source fs ON fs.source_id=f.source_id AND fs.source_code=os.source_code
              WHERE os.protocol_code='RADAR_TCP_V3_0_0'
            ) binding
            JOIN ops_device d ON d.device_id=binding.ops_device_id AND d.source_id=binding.ops_source_id
              AND d.external_device_id=binding.external_device_id AND d.source_mode=binding.source_mode
              AND d.enabled=TRUE AND d.deleted_at IS NULL
            JOIN ops_integration_source os ON os.source_id=d.source_id AND os.enabled=TRUE
              AND os.source_mode=d.source_mode AND os.protocol_code=binding.protocol_code
            JOIN device f ON f.device_id=binding.device_id AND f.source_id=binding.source_id
              AND f.external_device_id=binding.external_device_id AND f.source_mode=binding.source_mode AND f.enabled=TRUE
            JOIN integration_source fs ON fs.source_id=f.source_id AND fs.enabled=TRUE
              AND fs.source_mode=f.source_mode AND fs.protocol_code=binding.protocol_code
            JOIN device_business_scope scope ON scope.ops_device_id=d.device_id
              AND scope.owner_org_id=f.owner_org_id AND scope.district_id=f.district_id
            """;
}
