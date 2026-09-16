#!/usr/bin/env python3
"""Local replay-only MQTT business test harness. It refuses production profiles.

prepare creates dedicated replay devices and sensing profiles via the existing HTTP API.
When all seeded plans are historical, it clones one plan into a unique current batch row through
the local Compose database; frozen seed rows are never updated or deleted.
stream publishes a dense, plausible situation scene and answers protocol B on those devices only.
Outputs are untracked artifacts under server/target/mqtt-acceptance/<batch>.
Passwords come from MQTT_TEST_PASSWORD / MQTT_TEST_REVIEWER_PASSWORD.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import queue
import re
import subprocess
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
from pathlib import Path

from publish_lingyun_ndjson import mqtt_client
from reply_lingyun_control import response_bytes, reply_topic

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'server' / 'target' / 'mqtt-acceptance'
TYPES = {'radar': 1, 'tdoa': 10, '5ga': 0}
DEVICE_COUNTS = {'radar': 6, 'eo': 6, '5ga': 12, 'tdoa': 18}
TARGET_COUNT = 24
SENSING_INTERVAL_SECONDS = 10
CITY_LAYOUT_CENTER = {'longitude': 118.58, 'latitude': 37.58}
DEVICE_LAYOUT = {
    'radar': {'lon_radius': 0.140, 'lat_radius': 0.130, 'phase': 0.15},
    'eo': {'lon_radius': 0.100, 'lat_radius': 0.100, 'phase': 0.65},
    '5ga': {'lon_radius': 0.200, 'lat_radius': 0.180, 'phase': 0.35},
    'tdoa': {'lon_radius': 0.270, 'lat_radius': 0.240, 'phase': 0.05},
}
PRIMARY_DEVICE_OFFSETS = {
    'radar': (0.030, 0.0), 'eo': (-0.012, 0.0),
    '5ga': (0.010, 0.002), 'tdoa': (-0.010, -0.002),
}


def generated_batch():
    return time.strftime('situation-%m%d-%H%M%S-') + uuid.uuid4().hex[:5]


def require_safe_runtime():
    profiles = os.environ.get('SPRING_PROFILES_ACTIVE', 'local').lower()
    if 'prod' in profiles:
        raise ValueError('This replay harness refuses production profiles')
    if os.environ.get('APP_FUSION_ENABLED', '').lower() != 'true':
        raise ValueError('Set APP_FUSION_ENABLED=true before running the acceptance scene')


def device_entries(manifest, kind=None):
    rows = list(manifest['devices'].values())
    return [row for row in rows if kind is None or row['kind'] == kind]


def primary_device(manifest, kind):
    return device_entries(manifest, kind)[0]


def sql_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def local_database():
    name = os.environ.get('MQTT_TEST_DB', 'houtaiguanli')
    if not re.fullmatch(r'[a-zA-Z0-9_]{1,63}', name):
        raise ValueError('MQTT_TEST_DB must be a simple local PostgreSQL database name')
    return name


def clone_current_plan(batch, template, start_at, end_at, owner_org_id, district_id,
                       translate_lon=0.0, translate_lat=0.0):
    """Clone route geometry into batch-owned route/plan rows; never mutate frozen fixtures."""
    route_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'mqtt-situation-route:' + batch))
    route_version_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'mqtt-situation-route-version:' + batch))
    plan_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'mqtt-situation-plan:' + batch))
    plan_no = ('MQTT-' + batch).upper()
    template_version_id = template['route']['route_version_id']
    sql = f"""
        INSERT INTO route
          (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)
        VALUES ({sql_literal(route_id)},{sql_literal('MQTT-R-' + batch)},{sql_literal('MQTT 验收航线 ' + batch)},
          TRUE,NULL,'replay',{sql_literal(owner_org_id)},{sql_literal(district_id)},now(),now(),0)
        ON CONFLICT (route_id) DO NOTHING;
        INSERT INTO route_version
          (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,
           altitude_datum,valid_from,valid_to,change_reason,created_at)
        SELECT {sql_literal(route_version_id)},{sql_literal(route_id)},1,
          ST_Translate(centerline,{float(translate_lon)},{float(translate_lat)}),corridor_width_m,
          min_altitude_m,max_altitude_m,altitude_datum,now(),NULL,'MQTT 验收批次复制',now()
        FROM route_version WHERE route_version_id={sql_literal(template_version_id)}
        ON CONFLICT (route_version_id) DO NOTHING;
        INSERT INTO flight_plan
          (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,
           owner_org_id,district_id,created_at,updated_at,version)
        VALUES ({sql_literal(plan_id)},{sql_literal(plan_no)},'EXECUTING',NULL,'replay',NULL,
          to_timestamp({start_at}/1000.0),to_timestamp({end_at}/1000.0),{sql_literal(route_version_id)},
          {sql_literal(owner_org_id)},{sql_literal(district_id)},now(),now(),0)
        ON CONFLICT (plan_id) DO NOTHING;
    """
    result = subprocess.run([
        'docker', 'exec', 'deploy-db-1', 'psql', '-U', 'uav', '-d', local_database(),
        '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql
    ], capture_output=True, encoding='utf-8')
    if result.returncode != 0:
        raise RuntimeError('Could not create current MQTT test plan: ' + result.stderr.strip())
    return plan_id


def millis():
    return int(time.time() * 1000)


def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')


def local_psql(sql):
    result = subprocess.run([
        'docker', 'exec', 'deploy-db-1', 'psql', '-U', 'uav', '-d', local_database(),
        '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql
    ], capture_output=True, encoding='utf-8')
    if result.returncode != 0:
        raise RuntimeError('Local PostgreSQL evidence query failed: ' + result.stderr.strip())
    return result.stdout.strip()


def wait_inbox_settled(batch, timeout=45):
    """Wait until every fusion frame from this batch is terminal, then reject poison frames."""
    literal = sql_literal('%' + batch + '%')
    sql = f"""SELECT json_build_object(
        'total', count(*),
        'pending', count(*) FILTER (WHERE status IN ('RECEIVED','PROCESSING')),
        'failed', count(*) FILTER (WHERE status='FAILED'),
        'failures', COALESCE(json_agg(json_build_object('source',source,'error',last_error))
            FILTER (WHERE status='FAILED'), '[]'::json))
        FROM inbox_message WHERE CAST(payload AS VARCHAR) LIKE {literal};"""
    deadline = time.monotonic() + timeout
    state = {'total': 0, 'pending': 0, 'failed': 0, 'failures': []}
    while time.monotonic() < deadline:
        state = json.loads(local_psql(sql))
        dump(OUT / batch / 'inbox-terminal-state.json', state)
        if state['total'] and not state['pending']:
            if state['failed']:
                raise RuntimeError('Fusion inbox contains failed frames: ' + json.dumps(
                    state['failures'], ensure_ascii=False))
            return state
        time.sleep(1)
    raise RuntimeError('Fusion inbox did not settle in time: ' + json.dumps(state, ensure_ascii=False))


class Api:
    def __init__(self, batch, account='admin1'):
        self.base = os.environ.get('MQTT_TEST_API', 'http://127.0.0.1:8080/api/v1')
        if urllib.parse.urlparse(self.base).hostname not in ('127.0.0.1', 'localhost'):
            raise ValueError('This harness only permits a local API')
        self.batch = batch
        self.token = None
        password = os.environ.get('MQTT_TEST_REVIEWER_PASSWORD' if account == 'reviewer1' else 'MQTT_TEST_PASSWORD', 'changeme')
        status, result = self.call('POST', '/auth/login', {'account': account, 'password': password}, log=False)
        if status != 200 or not result.get('ok'):
            raise RuntimeError('Local login failed: ' + str(result.get('error', status)))
        self.token = result['data']['session_id']

    def call(self, method, path, body=None, key=None, log=True):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if method != 'GET':
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                status, value = r.status, json.load(r)
        except urllib.error.HTTPError as ex:
            status, value = ex.code, json.load(ex)
        if log:
            folder = OUT / self.batch
            folder.mkdir(parents=True, exist_ok=True)
            with (folder / 'http.ndjson').open('a', encoding='utf-8') as f:
                f.write(json.dumps({'at': millis(), 'method': method, 'path': path, 'request': body,
                                    'status': status, 'response': value}, ensure_ascii=False) + '\n')
        return status, value

    def data(self, method, path, body=None, key=None):
        status, value = self.call(method, path, body, key)
        if status >= 400 or not value.get('ok'):
            raise RuntimeError(f'{method} {path}: {status} {value}')
        return value.get('data')


def api_pages(api, path, size=100):
    page = 1
    while True:
        separator = '&' if '?' in path else '?'
        result = api.data('GET', f'{path}{separator}page={page}&size={size}')
        items = result.get('items', [])
        yield from items
        if not items or page * size >= int(result.get('total') or 0):
            return
        page += 1


def is_harness_device(row):
    device_no = str(row.get('device_no') or '')
    name = str(row.get('name') or '')
    return row.get('source_mode') == 'replay' and (
        device_no.startswith('situation-') or name.startswith('MQTT测试 situation-'))


def set_device_enabled(api, row, enabled, reason):
    path = '/devices/' + row['device_id']
    device = api.data('GET', path)['device']
    if bool(device.get('enabled')) == enabled:
        return False
    api.data('PATCH', path + '/enabled', {
        'version': device['version'], 'enabled': enabled, 'reason': reason
    }, f"scene-enabled-{row['device_id']}-{int(enabled)}-{uuid.uuid4().hex[:8]}")
    return True


def isolate_replay_batch(api, batch, manifest=None):
    """Keep one visible MQTT demo batch without touching frozen or non-harness devices."""
    current_prefix = batch + '-'
    disabled = 0
    for row in api_pages(api, '/devices'):
        old_harness = is_harness_device(row) \
            and not str(row.get('device_no') or '').startswith(current_prefix)
        auxiliary_mock = str(row.get('device_id') or '').startswith('seed-vol-device-') \
            and str(row.get('device_type_code') or '').lower() in ('radar', 'eo', '5ga', 'tdoa')
        if old_harness or auxiliary_mock:
            disabled += int(set_device_enabled(api, row, False, '切换融合感知 MQTT 演示批次'))
    if manifest:
        for entry in device_entries(manifest):
            set_device_enabled(api, entry['detail']['device'], True, '启用当前融合感知 MQTT 演示批次')
    if disabled:
        print(f'Disabled {disabled} devices from earlier MQTT situation batches')


def route_midpoint(route):
    coordinates = route.get('centerline', {}).get('coordinates') or []
    if len(coordinates) < 2:
        raise RuntimeError('Selected flight plan route has no drawable centerline')
    lon, lat = coordinates[len(coordinates) // 2]
    return float(lon), float(lat)


def inward_azimuth(location, center_lon, center_lat):
    east = (center_lon - location['longitude']) * math.cos(math.radians(center_lat))
    north = center_lat - location['latitude']
    return round((math.degrees(math.atan2(east, north)) + 360) % 360, 2)


def scene_device_location(kind, index, count, target_lon, target_lat,
                          layout_lon=None, layout_lat=None):
    if index == 1:
        offset_lon, offset_lat = PRIMARY_DEVICE_OFFSETS[kind]
        return {'longitude': target_lon + offset_lon,
                'latitude': target_lat + offset_lat, 'altitude': 10}
    center_lon = layout_lon if layout_lon is not None else target_lon
    center_lat = layout_lat if layout_lat is not None else target_lat
    layout = DEVICE_LAYOUT[kind]
    angle = layout['phase'] + (index - 1) * 2 * math.pi / count
    spread = 0.88 + 0.12 * ((index - 1) % 3)
    return {
        'longitude': center_lon + layout['lon_radius'] * spread * math.cos(angle),
        'latitude': center_lat + layout['lat_radius'] * spread * math.sin(angle),
        'altitude': 10
    }


def refresh_manifest_layout(api, manifest):
    origin = manifest['scenario']['uav']
    layout = manifest['scenario'].setdefault('layout_center', dict(CITY_LAYOUT_CENTER))
    changed = 0
    for entry in device_entries(manifest):
        location = scene_device_location(
            entry['kind'], entry['index'], DEVICE_COUNTS[entry['kind']],
            origin['longitude'], origin['latitude'], layout['longitude'], layout['latitude'])
        path = f"/devices/{entry['detail']['device']['device_id']}"
        current = api.data('GET', path)
        device = current['device']
        if (abs(float(device.get('longitude') or 0) - location['longitude']) < 1e-8
                and abs(float(device.get('latitude') or 0) - location['latitude']) < 1e-8):
            continue
        registration = dict(entry['registration'], version=device['version'],
                            longitude=location['longitude'], latitude=location['latitude'],
                            altitude_m=location['altitude'])
        detail = api.data('PUT', path, registration,
                          f"layout-{manifest['batch']}-{entry['kind']}-{entry['index']}-{uuid.uuid4().hex[:8]}")
        if entry['kind'] == 'eo':
            coverage = detail['device'].get('coverage') or {}
            detail = api.data('PUT', path + '/sensing-profile', {
                'coverage_kind': 'SECTOR', 'range_m': 3000,
                'azimuth_deg': inward_azimuth(location, origin['longitude'], origin['latitude']),
                'fov_deg': 70, 'source_label': 'MQTT 验收场景配置',
                'expected_version': coverage.get('version')
            }, f"layout-coverage-{manifest['batch']}-{entry['index']}-{uuid.uuid4().hex[:8]}")
        entry.update(detail=detail, registration=registration, location=location)
        changed += 1
    if changed:
        print(f'Repositioned {changed} devices across the city-scale demo area')
    return changed


def prepare(batch):
    require_safe_runtime()
    path = OUT / batch / 'manifest.json'
    api = Api(batch)
    if path.exists():
        manifest = json.loads(path.read_text(encoding='utf-8'))
        isolate_replay_batch(api, batch, manifest)
        refresh_manifest_layout(api, manifest)
        dump(path, manifest)
        print('Reusing manifest:', path)
        return
    isolate_replay_batch(api, batch)
    broker = next(b for b in api.data('GET', '/mqtt-brokers') if b['name'] == 'local-lingyun-replay')
    if broker['source_mode'] != 'replay' or broker['host'] not in ('localhost', '127.0.0.1'):
        raise ValueError('Expected local replay broker')
    if not broker['enabled']:
        raise ValueError('Enable local-lingyun-replay in the device page first')
    now = millis()
    day = (now + 8 * 3600_000) // 86_400_000 * 86_400_000 - 8 * 3600_000
    airspaces = api.data('GET', f'/airspaces?page=1&size=100&valid_at={now}')['items']
    prohibited = None
    for row in airspaces:
        detail = api.data('GET', '/airspaces/' + row['airspace_id'])
        if detail.get('current_version', {}).get('kind_code') == 'PROHIBITED':
            prohibited = detail
            break
    if not prohibited:
        raise RuntimeError('Fresh local database has no current prohibited airspace')
    ring = prohibited['current_version']['boundary']['coordinates'][0][0]
    uav_lon = sum(float(point[0]) for point in ring[:-1]) / max(1, len(ring) - 1)
    uav_lat = sum(float(point[1]) for point in ring[:-1]) / max(1, len(ring) - 1)

    historical = api.data('GET', '/flight-plans?page=1&size=100')['items']
    template = next((row for row in historical if row.get('route', {}).get('route_version_id')), None)
    if not template:
        raise RuntimeError('Fresh local database has no flight plan template')
    template_route = api.data('GET', '/route-versions/' + template['route']['route_version_id'])
    template_lon, template_lat = route_midpoint(template_route)
    desired_route_lon, desired_route_lat = uav_lon + 0.055, uav_lat - 0.018
    plan_id = clone_current_plan(
        batch, template, now - 5 * 60_000, now + 24 * 60 * 60_000,
        broker['owner_org_id'], broker['district_id'],
        desired_route_lon - template_lon, desired_route_lat - template_lat)
    plan = api.data('GET', '/flight-plans/' + plan_id)
    route = api.data('GET', '/route-versions/' + plan['route']['route_version_id'])
    route_lon, route_lat = route_midpoint(route)

    manifest = {'batch': batch, 'created_at': now, 'broker_id': broker['broker_id'],
                'host': broker['host'], 'port': broker['port'], 'provider': 'mqtt-test', 'devices': {},
                'scenario': {'uav': {'longitude': uav_lon, 'latitude': uav_lat},
                             'layout_center': dict(CITY_LAYOUT_CENTER),
                             'foreign_object': {'longitude': route_lon + 0.00035, 'latitude': route_lat + 0.00025},
                             'plan_id': plan['plan_id'], 'route_version_id': route['route_version_id'],
                             'airspace_id': prohibited['airspace_id']}}
    keepalive = mqtt_client('prepare-' + uuid.uuid4().hex[:12])
    keepalive.connect(manifest['host'], manifest['port'], 30)
    keepalive.loop_start()

    def publish_prepare_heartbeat(device):
        topic, payload = heartbeat_message(manifest, device, millis())
        info = keepalive.publish(topic, json.dumps(payload, ensure_ascii=False).encode('utf-8'), qos=1)
        info.wait_for_publish(10)
        if not info.is_published():
            raise RuntimeError('MQTT prepare heartbeat timeout')

    # Unique provider/device identities prevent collision with the frozen stage85-lingyun-demo fixture.
    # Different sensor families occupy separate city-scale rings, while the first device of each
    # family remains near the scenario target so the four-source fusion path stays realistic.
    for kind, count in DEVICE_COUNTS.items():
        for index in range(1, count + 1):
            key = f'{kind}-{index:02d}'
            # EO 协议的 external_device_id 上限为 32；批次全名仍保留，后缀压缩到 3 字符。
            kind_mark = {'radar': 'r', 'eo': 'e', '5ga': '5', 'tdoa': 't'}[kind]
            external = f'{batch}-{kind_mark}{index:02d}'
            location = scene_device_location(
                kind, index, count, uav_lon, uav_lat,
                CITY_LAYOUT_CENTER['longitude'], CITY_LAYOUT_CENTER['latitude'])
            body = dict(protocol_code='EO_EDGE_MQTT_20250826' if kind == 'eo' else 'LINGYUN_MQTT_V8_6',
                        broker_id=broker['broker_id'], provider_code='mqtt-test', external_device_id=external,
                        device_type_abbr=kind, source_mode='replay', owner_org_id=broker['owner_org_id'],
                        district_id=broker['district_id'], device_no=external, name=f'MQTT测试 {external}',
                        vendor='本机模拟', model='MQTT-TEST', longitude=location['longitude'],
                        latitude=location['latitude'], altitude_m=location['altitude'])
            if kind == 'eo':
                body['edge_id'] = external + '-edge'
            detail = api.data('POST', '/devices/onboard', body, f'onboard-{batch}-{key}')
            profile = ({'coverage_kind': 'SECTOR', 'range_m': 3000,
                        'azimuth_deg': inward_azimuth(location, uav_lon, uav_lat), 'fov_deg': 70,
                        'source_label': 'MQTT 验收场景配置', 'expected_version': 0}
                       if kind == 'eo' else
                       {'coverage_kind': 'CIRCLE', 'radius_m': 5000 if kind == 'radar' else 1800,
                        'source_label': 'MQTT 验收场景配置', 'expected_version': 0})
            api.data('PUT', f"/devices/{detail['device']['device_id']}/sensing-profile", profile,
                     f'coverage-{batch}-{key}')
            manifest['devices'][key] = {'kind': kind, 'index': index, 'external_id': external,
                                        'detail': detail, 'registration': body, 'location': location}
            # EO 的本地超时阈值很短；设备逐台注册期间持续保活，避免“刚注册尚未开流”被误记成故障。
            for eo_device in device_entries(manifest, 'eo'):
                publish_prepare_heartbeat(eo_device)
            dump(OUT / batch / 'prepare-progress.json', manifest)
    for device in device_entries(manifest):
        publish_prepare_heartbeat(device)
    time.sleep(2)
    keepalive.disconnect()
    keepalive.loop_stop()
    dump(path, manifest)
    print(path)


def static(manifest, device, now):
    kind, d, location = device['kind'], device['external_id'], device['location']
    return {'providerCode': manifest['provider'], 'deviceId': d, 'deviceName': d,
            'deviceType': TYPES[kind], 'workState': 1, 'ptTime': now,
            'deviceLongitude': location['longitude'], 'deviceLatitude': location['latitude'],
            'deviceAltitude': location['altitude']}


def heartbeat_message(manifest, device, now, tracking=False):
    kind, external = device['kind'], device['external_id']
    if kind == 'eo':
        metadata = {'deviceId': external, 'codeStatus': 200, 'workState': 1 if tracking else 0,
                    'cameraStatus': {'hfov': 0.8, 'vfov': 0.4, 'panOrientAngle': 1,
                                     'tiltOrientAngle': 2, 'focalLen': 4.8,
                                     'detectDist': 3000, 'zoomIndex': 32}}
        return ('iot-reporting/cmlc/edge/' + device['registration']['edge_id'],
                {'event': 'HeartBeat', 'edgeId': device['registration']['edge_id'],
                 'timestamp': now, 'metadata': metadata})
    return f"bridge/{manifest['provider']}/device/{kind}/{external}", static(manifest, device, now)


def pulse_all_devices(manifest):
    """Publish one fresh common-time heartbeat set after slower rule evaluation/API evidence work."""
    client = mqtt_client('evidence-' + uuid.uuid4().hex[:12])
    client.connect(manifest['host'], manifest['port'], 30)
    client.loop_start()
    sent_at = millis()
    try:
        for device in device_entries(manifest):
            topic, payload = heartbeat_message(manifest, device, sent_at)
            info = client.publish(topic, json.dumps(payload, ensure_ascii=False).encode('utf-8'), qos=1)
            info.wait_for_publish(10)
            if not info.is_published():
                raise RuntimeError('MQTT evidence heartbeat timeout')
        time.sleep(2)
    finally:
        client.disconnect()
        client.loop_stop()
    dump(OUT / manifest['batch'] / 'evidence-heartbeat.json', {
        'at': sent_at, 'devices': len(manifest['devices'])
    })


def sense(manifest, device, now, seq):
    kind, d = device['kind'], device['external_id']
    origin = manifest['scenario']['uav']
    obj = {'objectId': 90001, 'time': now,
           'longitude': origin['longitude'] + 0.0007 * math.sin(seq / 12),
           'latitude': origin['latitude'] + 0.0005 * math.cos(seq / 12), 'altitude': 120, 'height': 80,
           'extension': {'objectType': 30, 'uavSN': f'{manifest["batch"]}-UAV-001',
                         'speedX': 7.2, 'speedY': 1.4, 'speedZ': 0}}
    if kind == 'tdoa':
        obj['extension'].update(pilotLon=origin['longitude'] - 0.001, pilotLat=origin['latitude'] - 0.001)
    objects = [obj]
    if kind == 'radar':
        for index in range(2, TARGET_COUNT + 1):
            foreign = index > 18
            if index == 19:
                base = manifest['scenario']['foreign_object']
                lon, lat = base['longitude'], base['latitude']
                object_type = 40
            else:
                angle = index * 2.399963229728653 + seq / 80
                radius = 0.025 + 0.015 * (index % 7)
                lon = origin['longitude'] + radius * math.cos(angle)
                lat = origin['latitude'] + radius * 0.72 * math.sin(angle)
                object_type = 40 if foreign and index % 2 else (255 if foreign else 30)
            extension = {'objectType': object_type, 'speedX': 2.4 if foreign else 6.5,
                         'speedY': 0.8, 'speedZ': 0}
            if not foreign:
                extension['uavSN'] = f'{manifest["batch"]}-UAV-{index:03d}'
            objects.append({'objectId': 90000 + index, 'time': now, 'longitude': lon,
                            'latitude': lat, 'altitude': 90 + index % 5 * 12,
                            'height': 60 + index % 4 * 8, 'extension': extension})
    return {'deviceId': d, 'ptTime': now, 'msgCnt': seq, 'objects': objects}


def scenario_targets(api, batch, from_at, to_at):
    page = api.data('GET', f'/targets?page=1&size=100&seen_from={from_at}&seen_to={to_at}')
    matched = []
    for row in page.get('items', []):
        detail = api.data('GET', '/targets/' + row['target_id'])
        links = detail.get('source_links', [])
        if any(batch in (link.get('source_name') or '') for link in links):
            matched.append(detail)
    return matched


def target_by_external(targets, external_id):
    for target in targets:
        if any(str(link.get('external_target_id')) == str(external_id)
               for link in target.get('source_links', [])):
            return target
    return None


def begin_eo_tracking(api, manifest, from_at):
    targets = scenario_targets(api, manifest['batch'], from_at, millis() + 1)
    target = target_by_external(targets, 90001)
    if not target:
        return None
    eo = primary_device(manifest, 'eo')
    body = {'device_id': eo['detail']['device']['device_id'],
            'reason': 'MQTT 验收场景四源融合跟踪'}
    status, result = api.call('POST', f"/targets/{target['target_id']}/eo-tracking-tasks", body,
                              f"eo-track-{manifest['batch']}")
    if status in (200, 202) and result.get('ok'):
        return result['data']
    if status == 409 and result.get('error', {}).get('code') in ('TRACK_ALREADY_OPEN', 'IDEMPOTENCY_REPLAY'):
        return api.data('GET', f"/targets/{target['target_id']}/eo-tracking-tasks")
    raise RuntimeError('EO tracking request failed: ' + str(result))


def evaluate_scene(api, manifest, from_at, to_at):
    targets = scenario_targets(api, manifest['batch'], from_at, to_at)
    primary = target_by_external(targets, 90001)
    foreign = target_by_external(targets, 90019)
    if not primary or not foreign:
        raise RuntimeError(f'Expected primary UAV and route foreign object, found {len(targets)} targets')

    status, result = api.call('POST', '/legality-evaluations', {
        'subject_kind': 'TARGET', 'subject_id': primary['target_id'], 'mode': 'ACTIVE'
    }, f"legality-{manifest['batch']}")
    if 200 <= status < 300 and result.get('ok'):
        legality = result['data']
    elif status == 409 and result.get('error', {}).get('code') == 'IDEMPOTENCY_REPLAY':
        page = api.data('GET', '/legality-evaluations?page=1&size=100'
                        f"&mode=ACTIVE&target_id={primary['target_id']}")
        if not page.get('items'):
            raise RuntimeError('Legality evaluation replay has no readable result')
        replayed = next((row for row in page['items'] if row.get('alarm_id')), page['items'][0])
        legality = {'run_id': replayed.get('run_id'), 'evaluation': replayed}
    else:
        raise RuntimeError('Legality evaluation failed: ' + str(result))
    evaluation = legality['evaluation']
    alarm = None
    if evaluation.get('alarm_id'):
        alarm = {'alarm_id': evaluation['alarm_id'], 'event_id': evaluation.get('event_id'),
                 'evaluation': evaluation}
    elif evaluation.get('legal_status') != 'LEGAL':
        alarm_status, alarm_result = api.call(
            'POST', f"/legality-evaluations/{evaluation['evaluation_id']}/alarms", {
                'note': 'MQTT 验收场景：禁飞空域目标转目标告警',
                'expected_version': evaluation['review']['version']
            }, f"alarm-{manifest['batch']}")
        if 200 <= alarm_status < 300 and alarm_result.get('ok'):
            alarm = alarm_result['data']
        elif alarm_status == 409 and alarm_result.get('error', {}).get('code') in ('IDEMPOTENCY_REPLAY', 'IDEMPOTENCY_KEY_REUSED'):
            current = api.data('GET', f"/legality-evaluations/{evaluation['evaluation_id']}")
            alarm = {'alarm_id': current.get('alarm_id'), 'event_id': current.get('event_id'),
                     'evaluation': current}
        else:
            raise RuntimeError('Alarm escalation failed: ' + str(alarm_result))

    c04_status, c04_result = api.call('POST', '/rule-evaluations', {
        'rule_code': 'C04', 'window_from': from_at, 'window_to': to_at
    }, f"c04-{manifest['batch']}")
    if 200 <= c04_status < 300 and c04_result.get('ok'):
        c04 = c04_result['data']
    elif c04_status == 409 and c04_result.get('error', {}).get('code') in ('IDEMPOTENCY_REPLAY', 'IDEMPOTENCY_KEY_REUSED'):
        c04 = {'status': c04_result['error']['code'], 'rule_code': 'C04'}
    else:
        raise RuntimeError('C04 evaluation failed: ' + str(c04_result))
    return {'primary_target_id': primary['target_id'], 'foreign_target_id': foreign['target_id'],
            'legality': legality, 'alarm': alarm, 'c04': c04}


def api_evidence(api, manifest, from_at, to_at, evaluations=None):
    devices = api.data('GET', f"/devices?page=1&size=100&keyword={urllib.parse.quote(manifest['batch'])}")
    targets = scenario_targets(api, manifest['batch'], from_at, to_at)
    ids = {row['target_id'] for row in targets}
    recent = api.data('GET', f'/tracks/recent?observed_from={from_at}&observed_to={to_at}&points_per_target=24')
    alarms = api.data('GET', f'/alarms?page=1&size=100&occurred_from={from_at}&occurred_to={to_at}')
    risks = api.data('GET', f'/risks?page=1&size=100&occurred_from={from_at}&occurred_to={to_at}')
    evidence = {'batch': manifest['batch'], 'at': millis(), 'targets': targets,
                'recent_tracks': {'as_of': recent.get('as_of'),
                                  'items': [row for row in recent.get('items', []) if row['target_id'] in ids]},
                'alarms': [row for row in alarms.get('items', []) if row.get('target_id') in ids],
                'risks': [row for row in risks.get('items', []) if row.get('target_id') in ids],
                'devices': devices.get('items', []), 'fusion_status': api.data('GET', '/fusion/status'),
                'evaluations': evaluations or {}}
    dump(OUT / manifest['batch'] / 'api-evidence.json', evidence)
    return evidence


def stream(batch, seconds, response_mode='success', sensing=True):
    require_safe_runtime()
    manifest = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    api = Api(batch)
    client = mqtt_client('business-' + uuid.uuid4().hex[:12])
    replies = queue.Queue()
    prefix = f"bridge/{manifest['provider']}"
    allowed = {v['external_id'] for v in manifest['devices'].values()}
    eo = primary_device(manifest, 'eo')
    eo_topic = 'iot-reporting/cmlc/edge/' + eo['registration']['edge_id']
    tracking = None
    tracking_requested = False
    tracking_target_id = None

    def on_connect(c, _u, _f, reason, *_args):
        if reason == 0:
            c.subscribe(f'{prefix}/device_control/+/+', qos=1)
            c.subscribe('iot-dispatcher/cmlc/edge/+', qos=1)

    def on_message(_c, _u, message):
        if message.topic.split('/')[-1] in allowed:
            replies.put((message.topic, bytes(message.payload)))

    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(manifest['host'], manifest['port'], 30)
    client.loop_start()
    # Lingyun msgCnt must keep increasing across process restarts for the same device identity.
    # Epoch seconds remain within the signed 32-bit range through 2038 and give stable motion too.
    start, started_at, seq, frame_count, next_frame = \
        time.monotonic(), millis(), int(time.time()), 0, 0
    logfile = (OUT / batch / 'published.ndjson').open('a', encoding='utf-8')

    def publish(topic, payload, retained=False, qos=1):
        raw = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
        info = client.publish(topic, raw.encode('utf-8'), qos=qos, retain=retained)
        info.wait_for_publish(10)
        if not info.is_published():
            raise RuntimeError('MQTT publish timeout')
        logfile.write(json.dumps({'at': millis(), 'topic': topic, 'payload': raw, 'qos': qos,
                                  'retain': retained, 'payload_hash': hashlib.sha256(raw.encode()).hexdigest()}, ensure_ascii=False) + '\n')
        logfile.flush()

    def publish_heartbeat(device, now):
        active = bool(tracking and device['external_id'] == eo['external_id'])
        topic, payload = heartbeat_message(manifest, device, now, active)
        publish(topic, payload)

    try:
        while time.monotonic() - start < seconds:
            if time.monotonic() >= next_frame:
                now = millis()
                sensing_tick = frame_count % SENSING_INTERVAL_SECONDS == 0
                for ordinal, device in enumerate(device_entries(manifest)):
                    kind, d = device['kind'], device['external_id']
                    # EO 本地协议心跳超时为 3 秒，必须每秒保活；其余设备按 5 路轮转即可。
                    refresh_device = kind == 'eo' or frame_count == 0 or device['index'] == 1 \
                        or (frame_count >= 3 and ordinal % 5 == frame_count % 5)
                    if refresh_device:
                        publish_heartbeat(device, now)
                    if kind == 'eo':
                        continue
                    if sensing and sensing_tick and device['index'] == 1:
                        publish(f'{prefix}/device_data/{kind}/{d}', sense(manifest, device, now, seq))
                if tracking and sensing_tick:
                    report = dict(tracking, timestamp=now)
                    publish(eo_topic, report)
                seq += 1
                frame_count += 1
                next_frame = time.monotonic() + 1
                if sensing and frame_count >= 3 and not tracking_requested:
                    task = begin_eo_tracking(api, manifest, started_at - 1000)
                    tracking_requested = task is not None
                    tracking_target_id = task.get('target_id') if task else None
            while not replies.empty():
                topic, payload = replies.get_nowait()
                with (OUT / batch / 'commands.ndjson').open('a', encoding='utf-8') as log:
                    log.write(json.dumps({'at': millis(), 'topic': topic, 'payload': payload.decode(), 'mode': response_mode}) + '\n')
                if topic.startswith('iot-dispatcher/'):
                    root = json.loads(payload)
                    root['timestamp'] = millis()
                    root['metadata']['codeStatus'] = 200
                    root['metadata']['workState'] = 1 if root['event'] == 'BeginTracking' else 0
                    guided = root['metadata'].get('objectData') or {}
                    root['metadata']['aiStatus'] = {
                        'className': 'drone', 'detectConfidence': 0.9, 'trackConfidence': 0.8,
                        # 光电回报自己的测量位置；只回类别而没有位置会成为独立无位置目标，无法做空间关联。
                        'longitude': guided.get('longitude'), 'latitude': guided.get('latitude'),
                        'altitude': guided.get('altitude')
                    }
                    publish(eo_topic, root)
                    tracking = root if root['event'] == 'BeginTracking' else None
                    continue
                if response_mode != 'none':
                    body = response_bytes(payload)
                    if body:
                        obj = json.loads(body)
                        if response_mode == 'failure':
                            obj['data'].update(code=1, msg='MQTT test intentional failure')
                        publish(reply_topic(topic), obj)
            time.sleep(0.05)
        # 最终全量心跳给设备状态证据一个共同的新鲜时间点；42 条只发一次，不会造成持续消息洪峰。
        final_at = millis()
        for device in device_entries(manifest):
            publish_heartbeat(device, final_at)
        time.sleep(2)
    except KeyboardInterrupt:
        print('Stopped by user')
    finally:
        logfile.close()
        client.disconnect()
        client.loop_stop()
    evaluation = None
    if sensing and frame_count >= 3:
        wait_inbox_settled(batch)
        if not tracking_target_id:
            raise RuntimeError('EO tracking task was not created')
        deadline = time.monotonic() + 30
        source_types = set()
        while time.monotonic() < deadline:
            detail = api.data('GET', '/targets/' + tracking_target_id)
            source_types = {row.get('source_type') for row in detail.get('source_links', [])}
            if {'RADAR', 'TDOA', 'FIVE_G_A', 'EO'}.issubset(source_types):
                break
            time.sleep(1)
        if not {'RADAR', 'TDOA', 'FIVE_G_A', 'EO'}.issubset(source_types):
            raise RuntimeError('Four-source fusion was not observed: ' + ','.join(sorted(source_types)))
        end_at = millis() + 1000
        evidence_from = manifest['created_at'] - 1000
        evaluation = evaluate_scene(api, manifest, evidence_from, end_at)
        pulse_all_devices(manifest)
        evidence = api_evidence(api, manifest, evidence_from, end_at, evaluation)
        if len(evidence['devices']) != sum(DEVICE_COUNTS.values()):
            raise RuntimeError(f"Expected 42 batch devices, found {len(evidence['devices'])}")
        online = sum(1 for row in evidence['devices'] if row.get('connectivity') == 'ONLINE')
        if online != sum(DEVICE_COUNTS.values()):
            raise RuntimeError(f"Expected 42 online batch devices, found {online}")
        if len(evidence['targets']) < TARGET_COUNT:
            raise RuntimeError(f"Expected {TARGET_COUNT} targets, found {len(evidence['targets'])}")
        if not evidence['alarms']:
            raise RuntimeError('Expected a target alarm from the prohibited-airspace UAV')
        if not evidence['risks']:
            raise RuntimeError('Expected a C04 route foreign-object risk')
    print(f'{batch}: frames={frame_count}, response={response_mode}, targets={TARGET_COUNT}, evaluated={bool(evaluation)}')


def negative(batch):
    """Publish transport negative cases only to this batch's replay namespace."""
    manifest = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    d = primary_device(manifest, 'radar')['external_id']
    topic = f"bridge/{manifest['provider']}/device_data/radar/{d}"
    now = millis()
    payload = {'deviceId': d, 'ptTime': now, 'msgCnt': 100000, 'objects': []}
    raw = json.dumps(payload, separators=(',', ':'))
    conflict = json.dumps(dict(payload, extra='same key, changed payload'))
    cases = [('accepted_empty', topic, raw, 1, False), ('duplicate', topic, raw, 1, False),
             ('conflict', topic, conflict, 1, False), ('invalid_json', topic, '{broken', 1, False),
             ('identity_mismatch', topic, json.dumps(dict(payload, deviceId='wrong-id')), 1, False),
             ('qos0', topic, json.dumps(dict(payload, ptTime=now + 1)), 0, False),
             ('retained', topic, json.dumps(dict(payload, ptTime=now + 2)), 1, True),
             ('unregistered', topic.replace(d, d + '-unknown'), json.dumps(dict(payload, deviceId=d + '-unknown')), 1, False),
             ('invalid_topic', topic + '/extra', raw, 1, False)]
    client = mqtt_client('negative-' + uuid.uuid4().hex[:12])
    client.connect(manifest['host'], manifest['port'], 30)
    client.loop_start()
    evidence = []
    try:
        for name, t, body, qos, retain in cases:
            info = client.publish(t, body.encode(), qos=qos, retain=retain)
            info.wait_for_publish(10)
            evidence.append({'name': name, 'at': millis(), 'topic': t, 'payload': body, 'qos': qos,
                             'retain': retain, 'published': info.is_published(),
                             'payload_hash': hashlib.sha256(body.encode()).hexdigest()})
            time.sleep(0.2)
        # Remove only the retained packet that this function just created.
        client.publish(topic, b'', qos=1, retain=True).wait_for_publish(10)
    finally:
        client.disconnect()
        client.loop_stop()
        dump(OUT / batch / 'negative-published.json', evidence)
    print('Published negative cases; verify diagnostics and inbox before declaring PASS.')


def inspect(batch):
    """Read-only SQL evidence from the documented local Compose database."""
    # main validates batch before interpolating it into these fixed SQL templates.
    inbox = f"SELECT inbox_id FROM inbox_message WHERE CAST(payload AS VARCHAR) LIKE '%{batch}%'"
    sources = f'SELECT DISTINCT source_id FROM source_observation WHERE inbox_id IN ({inbox})'
    targets = f'SELECT DISTINCT target_id FROM target_source_link WHERE source_id IN ({sources})'
    queries = {
        'inbox': f'SELECT source,status,count(*) AS count FROM inbox_message WHERE inbox_id IN ({inbox}) GROUP BY source,status ORDER BY source,status',
        'diagnostics': f"SELECT outcome,reason,count(*) AS count FROM mqtt_receive_diagnostic WHERE topic LIKE '%{batch}%' GROUP BY outcome,reason ORDER BY outcome,reason",
        'observations': f'''SELECT source_type,class_code,count(*) AS count,count(location) AS located,
            count(altitude_amsl_m) AS known_altitude,count(pilot_location) AS pilot_positions
            FROM source_observation WHERE inbox_id IN ({inbox}) GROUP BY source_type,class_code ORDER BY source_type,class_code''',
        'targets': f'''SELECT t.target_id,t.target_no,t.object_type_code,t.source_mode,s.classification_confidence,
            s.fusion_confidence,s.observed_at,s.location IS NOT NULL AS located FROM target t
            JOIN target_latest_state s USING(target_id) WHERE t.target_id IN ({targets}) ORDER BY t.target_no''',
        'evaluations': f'''SELECT target_id,legal_status,count(*) AS count FROM rule_evaluation
            WHERE target_id IN ({targets}) GROUP BY target_id,legal_status ORDER BY target_id,legal_status''',
        'alarms': f'''SELECT a.alarm_id,a.target_id,a.source_mode,a.alarm_type,e.event_id,e.state_code
            FROM alarm a JOIN uav_event e USING(alarm_id) WHERE a.target_id IN ({targets}) ORDER BY a.received_at''',
        'risks': f'SELECT risk_id,target_id,risk_type,state_code,source_mode FROM flight_risk WHERE target_id IN ({targets})',
        'devices': f'''SELECT d.device_id,d.device_no,s.connectivity,s.has_alarm,s.health_code,s.last_heartbeat_at
            FROM ops_device d JOIN ops_device_state s USING(device_id) WHERE d.device_no LIKE '{batch}-%' ORDER BY d.device_no''',
        'incidents': f"SELECT i.* FROM device_incident i JOIN ops_device d USING(device_id) WHERE d.device_no LIKE '{batch}-%'",
    }
    evidence = {'batch': batch, 'at': millis()}
    for name, query in queries.items():
        sql = "BEGIN READ ONLY; SELECT COALESCE(json_agg(row_to_json(q)), '[]'::json) FROM (" + query + ') q; COMMIT;'
        try:
            evidence[name] = json.loads(local_psql(sql))
        except RuntimeError as error:
            evidence[name] = {'error': str(error)}
    dump(OUT / batch / 'database-evidence.json', evidence)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


def retained_check(batch):
    """MQTT 3.1.1 marks stored replay as retained only on a new subscription."""
    m = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    d = primary_device(m, 'radar')
    path = '/devices/' + d['detail']['device']['device_id']
    api = Api(batch)
    topic = f"bridge/{m['provider']}/device_data/radar/{d['external_id']}"
    client = mqtt_client('retain-' + uuid.uuid4().hex[:12])
    client.connect(m['host'], m['port'], 30)
    client.loop_start()

    def enable(value):
        device = api.data('GET', path)['device']
        if device['enabled'] != value:
            api.data('PATCH', path + '/enabled', {'version': device['version'], 'enabled': value,
                                                 'reason': 'MQTT retained replay test ' + batch})
    was_enabled = api.data('GET', path)['device']['enabled']
    try:
        enable(False)
        time.sleep(3)
        payload = {'deviceId': d['external_id'], 'ptTime': millis(), 'msgCnt': 200000, 'objects': []}
        client.publish(topic, json.dumps(payload).encode(), qos=1, retain=True).wait_for_publish(10)
        enable(True)
        time.sleep(3)
        dump(OUT / batch / 'retained-replay.json', {'topic': topic, 'payload': payload, 'at': millis()})
    finally:
        client.publish(topic, b'', qos=1, retain=True).wait_for_publish(10)
        enable(was_enabled)
        client.disconnect()
        client.loop_stop()
    print('Retained replay delivered after resubscription; check RETAINED_NOT_REALTIME diagnostic.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['acceptance', 'prepare', 'stream', 'negative', 'inspect', 'retained'])
    p.add_argument('--batch', help='Unique test batch; omitted values are generated for acceptance/prepare')
    p.add_argument('--seconds', type=int, default=300)
    p.add_argument('--response', choices=['success', 'failure', 'none'], default='success')
    p.add_argument('--heartbeat-only', action='store_true')
    a = p.parse_args()
    if not a.batch:
        if a.action not in ('acceptance', 'prepare'):
            p.error('--batch is required for this action')
        a.batch = generated_batch()
    if not re.fullmatch(r'[a-zA-Z0-9-]{3,28}', a.batch):
        p.error('batch must be 3-28 letters, digits or hyphens')
    if not 1 <= a.seconds <= 86400:
        p.error('seconds must be 1-86400')
    if a.action == 'acceptance':
        prepare(a.batch)
        stream(a.batch, a.seconds, a.response, True)
        inspect(a.batch)
    elif a.action == 'prepare':
        prepare(a.batch)
    elif a.action == 'negative':
        negative(a.batch)
    elif a.action == 'inspect':
        inspect(a.batch)
    elif a.action == 'retained':
        retained_check(a.batch)
    else:
        stream(a.batch, a.seconds, a.response, not a.heartbeat_only)


if __name__ == '__main__':
    main()
