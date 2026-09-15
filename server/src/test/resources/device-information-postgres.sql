-- Run with psql -v ON_ERROR_STOP=1 in a migrated stage456_verify_* database only.
DO $$ BEGIN
 IF current_database() !~ '^stage456_verify_[a-z0-9_]+$' THEN RAISE EXCEPTION 'isolated verification database required'; END IF;
END $$;
BEGIN;
INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version)
VALUES ('info-org','INFO-ORG','信息验证',TRUE,1,1,0);
INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version)
VALUES ('info-district','INFO-DISTRICT','信息验证',TRUE,1,1,0);
INSERT INTO mqtt_broker(broker_id,name,host,port,tls,client_id,allowed_cidrs,source_mode,owner_org_id,district_id,enabled,created_at,updated_at)
VALUES ('info-broker','测试连接','127.0.0.1',1883,FALSE,'info-client','127.0.0.1/32','replay','info-org','info-district',TRUE,1,1);
INSERT INTO mqtt_session_lease(broker_id,lease_until,connection_state,updated_at)
VALUES ('info-broker',100000,'CONNECTED',1);
INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,created_at,updated_at)
SELECT 'info-os-'||n,'info-os-'||n,'测试来源','LINGYUN_MQTT_V8_6','replay',TRUE,TRUE,1,1 FROM generate_series(1,2) n;
INSERT INTO integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,source_type,created_at,updated_at)
SELECT 'info-s-'||n,'info-s-'||n,'测试来源','LINGYUN_MQTT_V8_6','replay',TRUE,'RADAR',now(),now() FROM generate_series(1,2) n;
INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_name,channel,source_mode,simulated,created_at,updated_at)
SELECT 'info-od-'||n,'info-os-'||n,'external-'||n,'INFO-'||n,'测试设备','雷达','MQTT','replay',TRUE,1,1 FROM generate_series(1,2) n;
INSERT INTO device(device_id,source_id,external_device_id,device_no,name,source_mode,owner_org_id,district_id,created_at,updated_at)
SELECT 'info-d-'||n,'info-s-'||n,'external-'||n,'INFO-'||n,'测试设备','replay','info-org','info-district',now(),now() FROM generate_series(1,2) n;
INSERT INTO mqtt_device_binding(ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,device_type_abbr,external_device_id,source_mode,last_pt_time,last_msg_cnt)
VALUES ('info-od-1','info-d-1','info-os-1','info-s-1','info-broker','provider','radar','external-1','replay',1000,0);
INSERT INTO eo_edge(edge_id,broker_id,source_mode,owner_org_id,district_id,created_at,updated_at)
VALUES ('info-edge','info-broker','replay','info-org','info-district',1,1);
INSERT INTO eo_device_binding(ops_device_id,device_id,ops_source_id,source_id,edge_id,broker_id,external_device_id,source_mode)
VALUES ('info-od-2','info-d-2','info-os-2','info-s-2','info-edge','info-broker','external-2','replay');
UPDATE eo_device_binding SET last_heartbeat_at=1000,work_state=2,camera_status_json='{"zoomIndex":0}',
 heartbeat_json='{"event":"Heartbeat","timestamp":999,"metadata":{"workState":2,"taskId":"track-1"}}',camera_received_at=1000 WHERE ops_device_id='info-od-2';
INSERT INTO protocol_runtime_state(device_id,protocol_code,updated_at,radar_registers_json,radar_registers_at)
VALUES ('info-od-1','RADAR_TCP_V3_0_0',1000,'{"frequency_code":0,"rcs_filter_enabled":false}',1000);
INSERT INTO inbox_message(inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
VALUES ('info-inbox-new','lingyun:radar:info-d-1','1000:0','info-s-1',repeat('a',64),'{"ptTime":1000,"msgCnt":0,"objects":[]}'::json,1000,'RECEIVED'),
 ('info-inbox-late','lingyun:radar:info-d-1','999:9','info-s-1',repeat('b',64),'{"ptTime":999,"msgCnt":9,"objects":[]}'::json,2000,'RECEIVED');
DO $$ DECLARE p JSONB; BEGIN
 SELECT CAST(payload AS VARCHAR)::jsonb INTO p FROM inbox_message WHERE source='lingyun:radar:info-d-1' AND source_msg_id='1000:0';
 IF (p->>'ptTime')::bigint <> 1000 OR (p->>'msgCnt')::bigint <> 0 THEN RAISE EXCEPTION 'accepted inbox identity/JSON conversion failed'; END IF;
 SELECT heartbeat_json::jsonb INTO p FROM eo_device_binding WHERE ops_device_id='info-od-2';
 IF p#>>'{metadata,taskId}' <> 'track-1' OR p#>>'{metadata,workState}' <> '2' THEN RAISE EXCEPTION 'EO envelope lost'; END IF;
 SELECT radar_registers_json::jsonb INTO p FROM protocol_runtime_state WHERE device_id='info-od-1';
 IF p->>'frequency_code' <> '0' OR p->>'rcs_filter_enabled' <> 'false' THEN RAISE EXCEPTION 'zero/false register persistence failed'; END IF;
 IF (SELECT camera_received_at FROM eo_device_binding WHERE ops_device_id='info-od-2') <> 1000 THEN RAISE EXCEPTION 'camera timestamp missing'; END IF;
END $$;
-- Exercise both binding joins and all additional information repository SQL against real schemas.
SELECT m.ops_device_id,b.host,b.port,l.connection_state FROM mqtt_device_binding m JOIN mqtt_broker b ON b.broker_id=m.broker_id LEFT JOIN mqtt_session_lease l ON l.broker_id=m.broker_id WHERE m.ops_device_id='info-od-1';
SELECT m.ops_device_id,b.host,b.port,l.connection_state FROM eo_device_binding m JOIN mqtt_broker b ON b.broker_id=m.broker_id LEFT JOIN mqtt_session_lease l ON l.broker_id=m.broker_id WHERE m.ops_device_id='info-od-2';
SELECT * FROM radar_point_summary WHERE device_id='info-od-1';
SELECT * FROM radar_rtk_sample WHERE device_id='info-od-1' ORDER BY received_at DESC,sample_id DESC FETCH FIRST 1 ROWS ONLY;
SELECT c.command_no,c.command_type,c.status,r.device_result_code,r.occurred_at,r.received_at FROM device_command c JOIN command_receipt r ON r.command_id=c.command_id WHERE c.device_id='info-od-1' ORDER BY r.received_at DESC,r.receipt_id DESC FETCH FIRST 1 ROWS ONLY;
SELECT COUNT(*) FROM device_business_scope s JOIN app_user_data_scope u ON u.org_id=s.owner_org_id AND u.district_id=s.district_id JOIN app_org o ON o.org_id=s.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE WHERE s.ops_device_id='info-od-1' AND u.user_id='unassigned';
ROLLBACK;
SELECT 'PASS: information persistence, timestamps, accepted message identity, binding and repository queries' AS result, PostGIS_Version();
