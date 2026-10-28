#!/usr/bin/env python3
"""Bounded local MQTT birds -> C04 -> pending flight-plan risk acceptance.

No EO, control replies, notifications, risk/target inserts, existing-record updates,
or global switches. preview is SQL-read-only. execute adds two replay prerequisites,
registers two replay radars, publishes 24 frames/device, evaluates a fixed window.
Completed execute is read-only verification; interrupted batches are not refreshed.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import time
import urllib.request
import uuid


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def setup(args):
    root = args.backend_root.resolve() if args.backend_root else Path(__file__).resolve().parents[2]
    source = root / 'server/scripts/mqtt_business_test.py'
    require(source.is_file(), 'Pass --backend-root pointing to houtaiguanli')
    sys.path.insert(0, str(source.parent))
    spec = importlib.util.spec_from_file_location('safe_mqtt_harness', source)
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    harness.OUT = args.output.resolve()
    require(os.environ.get('MQTT_TEST_DB', 'houtaiguanli') == 'houtaiguanli', 'Only local houtaiguanli DB allowed')
    os.environ['MQTT_TEST_API'] = 'http://127.0.0.1:8081/api/v1'
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
    return harness


def rows(h, query):
    return json.loads(h.local_psql('SELECT COALESCE(json_agg(row_to_json(q)),\'[]\'::json) FROM (' + query + ') q;'))


def ids_sql(h, values):
    return ','.join(h.sql_literal(v) for v in values) or "''"


def new_manifest(batch):
    identity = uuid.uuid5(uuid.NAMESPACE_URL, 'dongying-pending-risk:' + batch)
    uid = lambda role: str(uuid.uuid5(identity, role))
    short = identity.hex[:12]
    plans = []
    for role, lat in [('risk', 37.64), ('control', 37.80)]:
        plans.append({'role': role, 'plan_id': uid('plan-' + role), 'route_id': uid('route-' + role),
                      'route_version_id': uid('route-version-' + role),
                      'plan_no': 'MQTT-PENDING-' + short + '-' + role.upper(),
                      'route_no': 'MQTT-ROUTE-' + short + '-' + role.upper(),
                      'name': ('MQTT未执行风险测试' if role == 'risk' else 'MQTT未执行不命中对照') + ' ' + short,
                      'centerline': [[119.04, lat], [119.11, lat]],
                      'corridor_width_m': 200, 'altitude_datum': 'AGL'})
    devices = {}
    for i, plan in enumerate(plans):
        key = plan['role']
        devices[key] = {'kind': 'radar', 'index': i + 1, 'external_id': 'prisk-' + short + '-' + key,
                        'location': {'longitude': 119.075, 'latitude': plan['centerline'][0][1], 'altitude': 10}}
    objects = [
        {'role': 'inside', 'device': 'risk', 'object_id': 81001, 'longitude': 119.050, 'latitude': 37.64,
         'height': 80, 'expected': {'severity': 'HIGH', 'corridor_relation': 'INSIDE', 'altitude_band': 'CLIMB', 'reason_code': 'SPACE_OBJECT_IN_CORRIDOR'}},
        {'role': 'near', 'device': 'risk', 'object_id': 81002, 'longitude': 119.075, 'latitude': 37.64 + 200 / 111000,
         'height': 80, 'expected': {'severity': 'MEDIUM', 'corridor_relation': 'NEAR', 'altitude_band': 'CLIMB', 'reason_code': 'SPACE_OBJECT_NEAR_ROUTE'}},
        {'role': 'height_unknown', 'device': 'risk', 'object_id': 81003, 'longitude': 119.100, 'latitude': 37.64,
         'expected': {'severity': 'MEDIUM', 'corridor_relation': 'INSIDE', 'altitude_band': 'UNKNOWN', 'reason_code': 'SPACE_OBJECT_ALTITUDE_UNKNOWN'}},
        {'role': 'outside', 'device': 'control', 'object_id': 81004, 'longitude': 119.075, 'latitude': 37.808,
         'height': 80, 'expected': None},
    ]
    return {'schema': 1, 'batch': batch, 'batch_uuid': str(identity), 'business_timezone': 'Asia/Shanghai',
            'source_mode': 'replay', 'provider': 'mqtt-test', 'host': '127.0.0.1', 'port': 1883,
            'duration_seconds': 120, 'interval_seconds': 5, 'frames_per_device': 24,
            'plan_start_offset_minutes': 10, 'plan_end_offset_minutes': 40,
            'plans': plans, 'devices': devices, 'objects': objects, 'phase': 'PREVIEW',
            'notes': ['Synthetic bird observations; no UAV identity or plan execution inference.',
                      'No notification or control actions; risk state stays backend-derived.',
                      'Raw protocol altitude is reference-unknown; missing height must stay unknown.',
                      'Object count and trend unavailable; no LOW or CRITICAL expectation.']}


def preflight(h, m, eo_confirmed_disabled=False, enforce_eo_gate=False):
    require('prod' not in os.environ.get('SPRING_PROFILES_ACTIVE', 'local').lower(), 'Production profile refused')
    require(h.local_psql('SELECT current_database();') == 'houtaiguanli', 'Wrong database')
    broker = rows(h, "SELECT broker_id,name,host,port,source_mode,enabled,owner_org_id,district_id FROM mqtt_broker WHERE name='local-sim-replay'")
    require(len(broker) == 1, 'Expected one local-sim-replay broker')
    b = broker[0]
    require(b['source_mode'] == 'replay' and b['enabled'] and b['host'] in ('localhost', '127.0.0.1') and b['port'] == 1883, 'Broker must be enabled local replay:1883')
    m['broker'] = b
    params = rows(h, "SELECT p.rule_set_version_id,p.param_key,p.value_text,p.param_status FROM rule_param p JOIN rule_set s ON s.active_version_id=p.rule_set_version_id WHERE p.rule_code='C04' ORDER BY p.param_key")
    values = {p['param_key']: float(p['value_text']) for p in params}
    require(all(values.get(k) == v for k, v in {'corridor_near_m': 300, 'climb_band_agl_m': 150, 'approach_band_agl_m': 300, 'plan_window_pad_min': 15}.items()), 'C04 parameters changed; review scenario contract')
    m['rule_params'] = params
    window_from = h.millis()
    window_to = window_from + m['duration_seconds'] * 1000
    pad_ms = int(values['plan_window_pad_min'] * 60000)
    m['spatial_isolation_window'] = {'window_from': window_from, 'window_to': window_to, 'plan_window_pad_ms': pad_ms}
    route_ids = ids_sql(h, [p['route_id'] for p in m['plans']])
    isolation = []
    for p in m['plans']:
        geom = 'LINESTRING(' + ','.join(f'{x} {y}' for x, y in p['centerline']) + ')'
        distance = rows(h, f"SELECT min(ST_Distance(rv.centerline::geography,ST_GeomFromText({h.sql_literal(geom)},4326)::geography)) AS distance_m FROM route_version rv JOIN flight_plan fp ON fp.route_version_id=rv.route_version_id WHERE rv.route_id NOT IN ({route_ids}) AND fp.start_at<=to_timestamp({window_to + pad_ms}/1000.0) AND fp.end_at>=to_timestamp({window_from - pad_ms}/1000.0)")[0]['distance_m']
        require(distance is None or distance > 10000, 'New route must remain >10km from routes applicable to the current C04 window')
        isolation.append({'role': p['role'], 'nearest_applicable_route_m': distance})
    m['spatial_isolation'] = isolation
    enabled_eo = rows(h, f"SELECT e.ops_device_id,e.device_id,e.source_mode FROM eo_device_binding e JOIN ops_device d ON d.device_id=e.ops_device_id JOIN device_business_scope s ON s.ops_device_id=d.device_id WHERE d.enabled=TRUE AND s.owner_org_id={h.sql_literal(b['owner_org_id'])} AND s.district_id={h.sql_literal(b['district_id'])} ORDER BY e.ops_device_id")
    m['eo_auto_track_gate'] = {'checked_at': h.millis(), 'enabled_eo_in_scope': enabled_eo,
                               'confirmation_required': bool(enabled_eo),
                               'confirmed_disabled_for_this_execute': bool(enforce_eo_gate and eo_confirmed_disabled),
                               'runtime_key': 'app.eo-edge.auto-track.enabled',
                               'requirement': 'Recheck current running process and effective configuration before each execute. A saved manifest or earlier startup is not confirmation.'}
    if enforce_eo_gate:
        require(not enabled_eo or eo_confirmed_disabled,
                'Enabled EO exists in this scope. Independently verify app.eo-edge.auto-track.enabled=false for the current running service, then pass --eo-auto-track-confirmed-disabled for this execute only. No backend setting is changed.')
    return m


def protected_snapshot(h, m):
    excluded = ids_sql(h, [p['plan_id'] for p in m['plans']])
    route_ids = ids_sql(h, [p['route_id'] for p in m['plans']])
    return {'plans': rows(h, f'SELECT * FROM flight_plan WHERE plan_id NOT IN ({excluded}) ORDER BY plan_id'),
            'routes': rows(h, f'SELECT * FROM route WHERE route_id NOT IN ({route_ids}) ORDER BY route_id'),
            'route_versions': rows(h, f'SELECT * FROM route_version WHERE route_id NOT IN ({route_ids}) ORDER BY route_version_id'),
            'risks': rows(h, f'SELECT * FROM flight_risk WHERE plan_id NOT IN ({excluded}) ORDER BY risk_id')}


def create_prerequisites(h, m):
    lit = h.sql_literal
    b = m['broker']
    start, end = m['started_at'] + 600000, m['started_at'] + 2400000
    statements = ['BEGIN;']
    for p in m['plans']:
        geom = 'LINESTRING(' + ','.join(f'{x} {y}' for x, y in p['centerline']) + ')'
        statements.append(f"""
          INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
          VALUES({lit(p['route_id'])},{lit(p['route_no'])},{lit(p['name'])},TRUE,'replay',{lit(b['owner_org_id'])},{lit(b['district_id'])},now(),now(),0)
          ON CONFLICT(route_id) DO NOTHING;
          INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,change_reason,created_at)
          VALUES({lit(p['route_version_id'])},{lit(p['route_id'])},1,ST_GeomFromText({lit(geom)},4326),200,0,400,'AGL',to_timestamp({m['started_at']}/1000.0),{lit('本批次 MQTT 模拟测试；新增资料，不修改既有航线')},now())
          ON CONFLICT(route_version_id) DO NOTHING;
          INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)
          VALUES({lit(p['plan_id'])},{lit(p['plan_no'])},'PENDING','replay',to_timestamp({start}/1000.0),to_timestamp({end}/1000.0),{lit(p['route_version_id'])},{lit(b['owner_org_id'])},{lit(b['district_id'])},now(),now(),0)
          ON CONFLICT(plan_id) DO NOTHING;
        """)
        statements.append(f"""DO $$ BEGIN
          IF NOT EXISTS(SELECT 1 FROM flight_plan p JOIN route_version rv USING(route_version_id) JOIN route r USING(route_id)
            WHERE p.plan_id={lit(p['plan_id'])} AND p.plan_no={lit(p['plan_no'])} AND p.status_code='PENDING' AND p.source_mode='replay'
            AND p.start_at=to_timestamp({start}/1000.0) AND p.end_at=to_timestamp({end}/1000.0)
            AND p.owner_org_id={lit(b['owner_org_id'])} AND p.district_id={lit(b['district_id'])} AND p.version=0 AND p.uav_sn IS NULL
            AND rv.route_version_id={lit(p['route_version_id'])} AND rv.altitude_datum='AGL' AND rv.corridor_width_m=200
            AND ST_Equals(rv.centerline,ST_GeomFromText({lit(geom)},4326)) AND r.route_id={lit(p['route_id'])}
            AND r.source_mode='replay' AND r.version=0)
          THEN RAISE EXCEPTION 'Existing batch prerequisite differs; preserving original row'; END IF;
        END $$;""")
    audit_id = str(uuid.uuid5(uuid.UUID(m['batch_uuid']), 'prerequisite-audit'))
    detail = json.dumps({'batch': m['batch'], 'source': 'LOCAL_MQTT_SIMULATION_PREREQUISITES', 'plan_ids': [p['plan_id'] for p in m['plans']],
                         'started_at': m['started_at'], 'description': '新增 replay 待执行计划和独立航线；不是飞行、通知或办理结果'}, ensure_ascii=False)
    statements.append(f"""INSERT INTO audit_log(audit_id,user_id,account,module_code,action,object_type,object_id,detail,occurred_at,ip,result,user_agent)
       VALUES({lit(audit_id)},NULL,'local-mqtt-simulation','flight-plan','MQTT_SIMULATION_PREREQUISITES_CREATED','SIMULATION_BATCH',
       {lit(m['batch_uuid'])},{lit(detail)},{m['started_at']},'127.0.0.1','SUCCESS','mqtt_pending_risk_test.py') ON CONFLICT(audit_id) DO NOTHING;""")
    statements.append('COMMIT;')
    h.local_psql('\n'.join(statements))


def onboard(h, api, m, path):
    b = m['broker']
    for role, d in m['devices'].items():
        location = d['location']
        body = {'protocol_code': 'LINGYUN_MQTT_V8_6', 'broker_id': b['broker_id'], 'provider_code': m['provider'],
                'external_device_id': d['external_id'], 'device_type_abbr': 'radar', 'source_mode': 'replay',
                'owner_org_id': b['owner_org_id'], 'district_id': b['district_id'], 'device_no': d['external_id'],
                'name': 'MQTT未执行风险测试 ' + role + ' ' + m['batch_uuid'][:8], 'vendor': '本机模拟',
                'model': 'MQTT-PENDING-RISK-TEST', 'longitude': location['longitude'], 'latitude': location['latitude'], 'altitude_m': 10}
        d['registration'] = body
        d['detail'] = api.data('POST', '/devices/onboard', body, 'onboard-pending-' + m['batch_uuid'] + '-' + role)
        require(d['detail']['device']['source_mode'] == 'replay', 'Registered device not replay')
        save(path, m)


def object_payload(obj, observed, elapsed):
    result = {'objectId': obj['object_id'], 'time': observed,
              'longitude': obj['longitude'] + elapsed / (111320 * math.cos(math.radians(obj['latitude']))),
              'latitude': obj['latitude'], 'altitude': 100, 'speed': 1,
              'extension': {'objectType': 40, 'speedX': 1, 'speedY': 0, 'speedZ': 0}}
    if 'height' in obj:
        result['height'] = obj['height']
    return result


def wait_frame(h, device, sequence):
    deadline = time.monotonic() + 4
    while time.monotonic() < deadline:
        found = rows(h, f"SELECT i.status FROM inbox_message i JOIN mqtt_device_binding b ON i.source='lingyun:radar:'||b.device_id WHERE b.ops_device_id={h.sql_literal(device['detail']['device']['device_id'])} AND (i.payload::jsonb->>'msgCnt')::int={sequence}")
        if found and all(r['status'] == 'DONE' for r in found):
            return
        require(not any(r['status'] == 'FAILED' for r in found), 'Fusion failed for MQTT frame')
        time.sleep(0.1)
    raise RuntimeError('Frame did not finish processing; stopped before another source can obscure the failure')


def stream(h, m, path):
    client = h.mqtt_client('prisk-' + m['batch_uuid'][:18])
    client.connect('127.0.0.1', 1883, 30)
    client.loop_start()
    try:
        # Onboarding subscriptions refresh asynchronously. Prove heartbeat reception first.
        ready_deadline = time.monotonic() + 15
        while True:
            for device in m['devices'].values():
                topic, payload = h.heartbeat_message(m, device, h.millis())
                client.publish(topic, json.dumps(payload).encode(), qos=1, retain=False).wait_for_publish(3)
            external = ids_sql(h, [f"bridge/{m['provider']}/device/radar/{d['external_id']}" for d in m['devices'].values()])
            ready = rows(h, f"SELECT count(DISTINCT topic) AS count FROM mqtt_receive_diagnostic WHERE topic IN ({external}) AND outcome='ACCEPTED' AND reason='STATIC_UPDATED' AND received_at>={m['started_at']}")[0]['count']
            if ready == 2:
                break
            require(time.monotonic() < ready_deadline, 'MQTT subscriptions not ready; no target observations published')
            time.sleep(0.5)
        m['stream_started_at'] = h.millis()
        save(path, m)
        clock = time.monotonic()
        with (path.parent / 'mqtt.ndjson').open('a', encoding='utf-8') as log:
            for seq in range(m['frames_per_device']):
                due = clock + seq * m['interval_seconds']
                while time.monotonic() < due:
                    require(not (path.parent / 'STOP').exists(), 'Batch stopped by local STOP flag')
                    time.sleep(min(0.2, max(0, due - time.monotonic())))
                require(not (path.parent / 'STOP').exists(), 'Batch stopped by local STOP flag')
                require(time.monotonic() - clock < m['duration_seconds'], 'Batch duration limit reached')
                observed = h.millis()
                elapsed = (observed - m['stream_started_at']) / 1000
                for role in ('control', 'risk'):
                    device = m['devices'][role]
                    messages = [h.heartbeat_message(m, device, observed)]
                    messages.append((f"bridge/{m['provider']}/device_data/radar/{device['external_id']}",
                                     {'deviceId': device['external_id'], 'ptTime': observed, 'msgCnt': seq,
                                      'objects': [object_payload(o, observed, elapsed) for o in m['objects'] if o['device'] == role]}))
                    for topic, payload in messages:
                        require(time.monotonic() - clock < m['duration_seconds'], 'Publication deadline reached')
                        require(not (path.parent / 'STOP').exists(), 'Batch stopped')
                        log.write(json.dumps({'topic': topic, 'payload': payload}, ensure_ascii=False) + '\n')
                        log.flush()
                        info = client.publish(topic, json.dumps(payload, ensure_ascii=False).encode(), qos=1, retain=False)
                        info.wait_for_publish(10)
                        require(info.is_published(), 'MQTT publication not acknowledged')
                    wait_frame(h, device, seq)
                m['last_observed_at'] = observed
                m['frames_sent_per_device'] = seq + 1
                save(path, m)
    finally:
        client.disconnect()
        client.loop_stop()
    m['publication_window'] = {'from': m['stream_started_at'], 'to': m['last_observed_at'] + 1}
    # C04 reads latest states. Assess the final complete frame, excluding earlier terminated targets.
    m['window_from'] = m['last_observed_at']
    m['window_to'] = m['last_observed_at'] + 1
    save(path, m)


def bindings(h, m):
    ops_ids = ids_sql(h, [d['detail']['device']['device_id'] for d in m['devices'].values() if 'detail' in d])
    result = rows(h, f"SELECT ops_device_id,device_id,external_device_id FROM mqtt_device_binding WHERE ops_device_id IN ({ops_ids})")
    require(len(result) == 2, 'Expected two MQTT fusion bindings')
    return result


def inbox_state(h, m):
    sources = ids_sql(h, ['lingyun:radar:' + b['device_id'] for b in bindings(h, m)])
    return rows(h, f"SELECT source,status,count(*) AS count,array_agg((payload::jsonb->>'msgCnt')::int ORDER BY (payload::jsonb->>'msgCnt')::int) AS sequences FROM inbox_message WHERE source IN ({sources}) GROUP BY source,status ORDER BY source,status")


def wait_inbox(h, m, path):
    deadline = time.monotonic() + 30
    expected = list(range(m['frames_per_device']))
    while time.monotonic() < deadline:
        state = inbox_state(h, m)
        save(path.parent / 'inbox.json', state)
        require(not any(s['status'] == 'FAILED' for s in state), 'MQTT inbox contains FAILED rows')
        if len(state) == 2 and all(s['status'] == 'DONE' and s['sequences'] == m.get('accepted_sequence_expectations', {}).get(s['source'], expected) for s in state):
            return
        # Explicit one-time recovery of the already published first batch. Never loosen future runs.
        legacy = m.get('recovery_evidence', {}).get('missing_initial_frame_confirmed', False)
        if legacy and len(state) == 2 and all(s['status'] == 'DONE' and s['sequences'] == expected[1:] for s in state):
            return
        time.sleep(1)
    raise RuntimeError('MQTT inbox did not settle with the expected sequence set; no C04 evaluation attempted')


def batch_targets(h, m):
    device_ids = ids_sql(h, [b['device_id'] for b in bindings(h, m)])
    return rows(h, f"SELECT t.*,l.external_target_id,l.device_id,ls.height_agl_m,ls.altitude_amsl_m,ls.observed_at,ST_X(ls.location) AS longitude,ST_Y(ls.location) AS latitude FROM target t JOIN target_source_link l ON l.target_id=t.target_id JOIN target_latest_state ls ON ls.target_id=t.target_id WHERE l.device_id IN ({device_ids}) ORDER BY l.external_target_id")


def evaluate(h, api, m, path):
    targets = batch_targets(h, m)
    require(len(targets) == 4 and len({t['target_id'] for t in targets}) == 4, 'Expected four distinct batch targets; evaluate refused')
    owned = ids_sql(h, [t['target_id'] for t in targets])
    foreign = rows(h, f"SELECT t.target_id,t.target_no,t.object_type_code FROM target t JOIN target_latest_state ls ON ls.target_id=t.target_id JOIN space_object_subtype sub ON sub.enabled=TRUE AND CAST(sub.aliases AS TEXT) LIKE CONCAT('%\"',COALESCE(NULLIF(t.subtype,''),t.object_type_code),'\"%') WHERE ls.observed_at>=to_timestamp({m['window_from']}/1000.0) AND ls.observed_at<to_timestamp({m['window_to']}/1000.0) AND t.target_id NOT IN ({owned})")
    save(path.parent / 'other-fresh-objects.json', foreign)
    require(not foreign, 'C04 is global: other fresh space objects found; no evaluation attempted')
    body = {'rule_code': 'C04', 'window_from': m['window_from'], 'window_to': m['window_to']}
    prior = rows(h, f"SELECT * FROM rule_evaluation_run WHERE rule_code='C04' AND window_from=to_timestamp({m['window_from']}/1000.0) AND window_to=to_timestamp({m['window_to']}/1000.0) ORDER BY started_at")
    require(all(r['status'] == 'SUCCESS' for r in prior), 'Unfinished/failed prior evaluation requires inspection')
    require(len(prior) <= 2, 'More than two prior runs for this batch window; inspect before proceeding')
    m['evaluation'] = prior[0] if prior else api.data('POST', '/rule-evaluations', body, 'pending-c04-' + m['batch_uuid'])
    save(path, m)
    require(m['evaluation'].get('status') == 'SUCCESS' and m['evaluation'].get('risks_created') == 3, 'Initial C04 expected three new risks; inspect evaluation')
    # Separate HTTP key deliberately exercises rule-level source-risk idempotency.
    m['dedup_evaluation'] = prior[1] if len(prior) == 2 else api.data('POST', '/rule-evaluations', body, 'pending-c04-dedup-' + m['batch_uuid'])
    save(path, m)
    require(m['dedup_evaluation'].get('status') == 'SUCCESS' and m['dedup_evaluation'].get('risks_created') == 0 and m['dedup_evaluation'].get('risks_deduplicated') == 3, 'C04 fixed-window dedup assertion failed')


def verify(h, m, path, api=None):
    plans = ids_sql(h, [p['plan_id'] for p in m['plans']])
    actual_plans = rows(h, f'SELECT * FROM flight_plan WHERE plan_id IN ({plans}) ORDER BY plan_id')
    targets = batch_targets(h, m)
    risks = rows(h, f'SELECT r.*,f.corridor_relation,f.altitude_band,f.unknown_reasons,f.distance_to_route_m FROM flight_risk r JOIN space_risk_fact f ON f.risk_id=r.risk_id WHERE r.plan_id IN ({plans}) ORDER BY r.risk_id')
    assertions = []
    check = lambda name, ok: assertions.append({'assertion': name, 'passed': bool(ok)})
    initial, duplicate = m.get('evaluation', {}), m.get('dedup_evaluation', {})
    check('first C04 run creates three risks', initial.get('status') == 'SUCCESS' and initial.get('risks_created') == 3)
    check('fixed-window second C04 run only deduplicates', duplicate.get('status') == 'SUCCESS' and duplicate.get('risks_created') == 0 and duplicate.get('risks_deduplicated') == 3)
    check('two new replay PENDING plans', len(actual_plans) == 2 and all(p['status_code'] == 'PENDING' and p['source_mode'] == 'replay' and not p['uav_sn'] for p in actual_plans))
    check('four independent replay bird targets', len(targets) == 4 and len({t['target_id'] for t in targets}) == 4 and all(t['object_type_code'] == 'BIRD' and t['source_mode'] == 'replay' for t in targets))
    check('exactly three backend-derived risks', len(risks) == 3)
    target_map = {str(t['external_target_id']): t for t in targets}
    risk_plan = next(p['plan_id'] for p in m['plans'] if p['role'] == 'risk')
    for obj in m['objects']:
        target = target_map.get(str(obj['object_id']), {})
        actual = [r for r in risks if r['target_id'] == target.get('target_id')]
        expected = obj['expected']
        check('scenario ' + obj['role'], (len(actual) == 0) if expected is None else (len(actual) == 1 and actual[0]['plan_id'] == risk_plan and all(actual[0][k] == v for k, v in expected.items())))
    check('initial risks unverified, no forged notifications', all(r['state_code'] == 'PENDING_VERIFICATION' and r['version'] == 0 and r['source_mode'] == 'replay' for r in risks))
    check('unknown count and trend preserved', all(set(r['unknown_reasons']) == {'OBJECT_COUNT_UNAVAILABLE', 'TREND_UNAVAILABLE'} for r in risks))
    target_ids = ids_sql(h, [t['target_id'] for t in targets])
    track_counts = rows(h, f"SELECT t.target_id,t.layer,count(p.point_id) FILTER (WHERE p.point_kind='MEAS') AS points FROM track t JOIN track_point p USING(track_id) WHERE t.target_id IN ({target_ids}) GROUP BY t.target_id,t.layer ORDER BY t.target_id,t.layer")
    accepted = {s['source']: s['count'] for s in inbox_state(h, m) if s['status'] == 'DONE'}
    point_expectations = {t['target_id']: accepted.get('lingyun:radar:' + t['device_id'], 0) for t in targets}
    raw_tracks = [t for t in track_counts if t['layer'] == 'RAW']
    check('four raw tracks preserve every accepted MQTT observation', len(raw_tracks) == 4 and all(t['points'] == point_expectations[t['target_id']] for t in raw_tracks))
    eo_tasks = rows(h, f'SELECT task_id,target_id,status,created_at FROM eo_tracking_task WHERE target_id IN ({target_ids}) ORDER BY task_id')
    check('no EO tracking tasks for batch targets', len(eo_tasks) == 0)
    baseline_path = path.parent / 'before.json'
    if baseline_path.exists():
        baseline = json.loads(baseline_path.read_text())
        current = protected_snapshot(h, m)
        for key in baseline:
            check('preexisting ' + key + ' unchanged', digest(baseline[key]) == digest(current[key]))
    evidence = {'batch': m['batch'], 'verified_at': h.millis(), 'plans': actual_plans, 'targets': targets,
                'risks': risks, 'track_point_counts': track_counts, 'eo_tracking_tasks': eo_tasks,
                'inbox': inbox_state(h, m), 'transport_limitations': m.get('recovery_evidence'), 'assertions': assertions}
    if api:
        evidence['api_plans'] = [api.data('GET', '/flight-plans/' + p['plan_id']) for p in m['plans']]
        evidence['api_risks'] = [api.data('GET', '/risks/' + r['risk_id']) for r in risks]
        evidence['api_space_facts'] = [api.data('GET', '/risks/' + r['risk_id'] + '/space-fact') for r in risks]
        evidence['api_risk_lists'] = [api.data('GET', '/risks?page=1&size=100&exclude_demo_samples=true&plan_id=' + p['plan_id']) for p in m['plans']]
        check('filtered risk API totals are risk=3 control=0', [int(result['total']) for result in evidence['api_risk_lists']] == [3, 0])
    evidence['status'] = 'PASS' if all(a['passed'] for a in assertions) else 'FAIL'
    evidence_path = path.parent / 'verification.json'
    if evidence_path.exists():
        original = json.loads(evidence_path.read_text())
        save(path.parent / ('verification-' + str(original['verified_at']) + '.json'), original)
    save(evidence_path, evidence)
    print(json.dumps({'status': evidence['status'], 'batch': m['batch'], 'plans': [{'plan_id': p['plan_id'], 'plan_no': p['plan_no']} for p in actual_plans], 'risks': len(risks), 'assertions': assertions}, ensure_ascii=False, indent=2))
    require(evidence['status'] == 'PASS', 'Verification failed; inspect verification.json')


def execute(h, m, path, eo_confirmed_disabled=False):
    if m['phase'] == 'COMPLETE':
        verify(h, m, path)
        return
    require(m['phase'] == 'PREVIEW', 'This batch already started/interrupted. Do not refresh its clock; verify or use a new batch.')
    require(not (path.parent / 'STOP').exists(), 'Batch STOP exists')
    preflight(h, m, eo_confirmed_disabled=eo_confirmed_disabled, enforce_eo_gate=True)
    require(os.environ.get('APP_FUSION_ENABLED', '').lower() == 'true', 'Set APP_FUSION_ENABLED=true after verifying running local fusion is enabled')
    # Authenticate before fixing the batch clock: failed connectivity has no scenario writes.
    api = h.Api(m['batch'])
    save(path.parent / 'before.json', protected_snapshot(h, m))
    # Persist fixed start before the first business write. A crash never changes it.
    m.update(started_at=h.millis(), phase='STARTED')
    save(path, m)
    create_prerequisites(h, m)
    onboard(h, api, m, path)
    stream(h, m, path)
    wait_inbox(h, m, path)
    evaluate(h, api, m, path)
    verify(h, m, path, api)
    m.update(phase='COMPLETE', completed_at=h.millis())
    save(path, m)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preview', 'execute', 'resume-evaluation', 'verify', 'stop', 'rollback-plan'])
    parser.add_argument('--backend-root', type=Path)
    parser.add_argument('--output', type=Path, default=Path('/private/tmp/dongying-pending-risk-sim/batches'))
    parser.add_argument('--batch', required=True, help='Stable batch name, e.g. pending-risk-20260917-01')
    parser.add_argument('--eo-auto-track-confirmed-disabled', action='store_true',
                        help='Execute-only confirmation after freshly verifying app.eo-edge.auto-track.enabled=false on the current running service; never copy blindly into an automation')
    args = parser.parse_args()
    require(re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.batch), 'Invalid batch')
    folder = args.output.resolve() / args.batch
    path = folder / 'manifest.json'
    if args.command == 'stop':
        folder.mkdir(parents=True, exist_ok=True)
        (folder / 'STOP').write_text('Operator stopped this bounded batch. Historical records retained.\n')
        print('Local batch STOP flag written; sender checks within 0.2s. No global service affected.')
        return
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / 'batch.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        h = setup(args)
        m = json.loads(path.read_text()) if path.exists() else new_manifest(args.batch)
        if args.command == 'preview':
            preflight(h, m)
            save(path, m)
            print(json.dumps(m, ensure_ascii=False, indent=2))
        elif args.command == 'execute':
            execute(h, m, path, eo_confirmed_disabled=args.eo_auto_track_confirmed_disabled)
        elif args.command == 'resume-evaluation':
            require(m['phase'] == 'STARTED' and m.get('window_to'), 'Requires an interrupted batch with a fixed published window')
            require(args.eo_auto_track_confirmed_disabled, 'Recheck current EO auto-track runtime before resuming evaluation')
            require(not (folder / 'STOP').exists(), 'Batch STOP exists')
            require(h.millis() - m['window_to'] <= 300000, 'Interrupted observation is older than 5 minutes; preserve historical evidence and use a new live batch')
            api = h.Api(m['batch'])
            wait_inbox(h, m, path)
            evaluate(h, api, m, path)
            verify(h, m, path, api)
            m.update(phase='COMPLETE', completed_at=h.millis())
            save(path, m)
        elif args.command == 'verify':
            require(path.exists(), 'No executed batch manifest')
            verify(h, m, path)
        else:
            require(path.exists(), 'No batch manifest')
            plan = {'batch': args.batch, 'automatic_deletion': False,
                    'stop': f'python3 {Path(__file__).name} stop --batch {args.batch}',
                    'reason': 'MQTT inbox, target tracks, C04 facts and audits are append-only evidence. No safe cascading business rollback API exists.',
                    'prerequisites': m['plans'], 'devices': [d.get('detail', {}).get('device', {}).get('device_id') for d in m['devices'].values()],
                    'compensation': ['Stop this publisher only; retain existing history.', 'Review current device versions and any later user processing before separately disabling only these replay devices.', 'Do not delete linked risk, notification, audit or frozen material.'],
                    'backups': str(folder / 'before.json'), 'evidence': str(folder / 'verification.json')}
            save(folder / 'rollback-plan.json', plan)
            print(json.dumps(plan, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, BlockingIOError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
