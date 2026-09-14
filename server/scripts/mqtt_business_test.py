#!/usr/bin/env python3
"""Local replay-only MQTT business test harness. No production data/SQL writes.

prepare creates dedicated replay devices via the existing HTTP API.
stream publishes fresh samples and answers protocol B on those devices only.
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
TYPES = {'radar': 1, 'tdoa': 10, 'aoa': 9, 'rid': 102, 'ifr': 6, 'dec': 5, 'bsc': 12}


def millis():
    return int(time.time() * 1000)


def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')


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


def prepare(batch):
    path = OUT / batch / 'manifest.json'
    if path.exists():
        print('Reusing manifest:', path)
        return
    api = Api(batch)
    broker = next(b for b in api.data('GET', '/mqtt-brokers') if b['name'] == 'local-lingyun-replay')
    if broker['source_mode'] != 'replay' or broker['host'] not in ('localhost', '127.0.0.1'):
        raise ValueError('Expected local replay broker')
    if not broker['enabled']:
        raise ValueError('Enable local-lingyun-replay in the device page first')
    manifest = {'batch': batch, 'created_at': millis(), 'broker_id': broker['broker_id'],
                'host': broker['host'], 'port': broker['port'], 'provider': 'mqtt-test', 'devices': {}}
    # Unique provider/device identities prevent collision with frozen S85 fixtures.
    for kind in [*TYPES, 'eo']:
        external = f'{batch}-{kind}'
        body = dict(protocol_code='EO_EDGE_MQTT_20250826' if kind == 'eo' else 'LINGYUN_MQTT_V8_6',
                    broker_id=broker['broker_id'], provider_code='mqtt-test', external_device_id=external,
                    device_type_abbr=kind, source_mode='replay', owner_org_id=broker['owner_org_id'],
                    district_id=broker['district_id'], device_no=external, name=f'MQTT测试 {external}',
                    vendor='本机模拟', model='MQTT-TEST')
        if kind == 'eo':
            body['edge_id'] = external + '-edge'
        detail = api.data('POST', '/devices/onboard', body, f'onboard-{batch}-{kind}')
        manifest['devices'][kind] = {'external_id': external, 'detail': detail, 'registration': body}
        dump(OUT / batch / 'prepare-progress.json', manifest)
    dump(path, manifest)
    print(path)


def static(manifest, kind, now):
    d = manifest['devices'][kind]['external_id']
    return {'providerCode': manifest['provider'], 'deviceId': d, 'deviceName': d,
            'deviceType': TYPES[kind], 'workState': 1, 'ptTime': now,
            'deviceLongitude': 118.60, 'deviceLatitude': 37.45, 'deviceAltitude': 10}


def sense(manifest, kind, now, seq):
    d = manifest['devices'][kind]['external_id']
    obj = {'objectId': 90001, 'time': now, 'longitude': 118.60 + 0.0001 * math.sin(seq / 20),
           'latitude': 37.45 + 0.0001 * math.cos(seq / 20), 'altitude': 120, 'height': 80,
           'extension': {'objectType': 30, 'speedX': 1, 'speedY': 0, 'speedZ': 0}}
    if kind == 'tdoa':
        obj['extension'].update(pilotLon=118.599, pilotLat=37.449)
    if kind == 'aoa':
        obj['extension']['direction'] = 60
    objects = [obj]
    if kind == 'radar':
        unknown = json.loads(json.dumps(obj))
        unknown.update(objectId=90002, longitude=118.61, latitude=37.46)
        unknown['extension']['objectType'] = 255
        bird = json.loads(json.dumps(obj))
        bird.update(objectId=90003, longitude=118.62, latitude=37.47)
        bird['extension']['objectType'] = 40
        objects += [unknown, bird]
    return {'deviceId': d, 'ptTime': now, 'msgCnt': seq, 'objects': objects}


def stream(batch, seconds, response_mode='success', sensing=True):
    manifest = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    client = mqtt_client('business-' + uuid.uuid4().hex[:12])
    replies = queue.Queue()
    prefix = f"bridge/{manifest['provider']}"
    allowed = {v['external_id'] for v in manifest['devices'].values()}
    eo = manifest['devices']['eo']
    eo_topic = 'iot-reporting/cmlc/edge/' + eo['registration']['edge_id']
    tracking = None

    def on_connect(c, _u, _f, reason, *_args):
        if reason == 0:
            c.subscribe(f'{prefix}/device_control/+/+', qos=1)
            c.subscribe('iot-dispatcher/cmlc/edge/' + eo['external_id'], qos=1)

    def on_message(_c, _u, message):
        if message.topic.split('/')[-1] in allowed:
            replies.put((message.topic, bytes(message.payload)))

    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(manifest['host'], manifest['port'], 30)
    client.loop_start()
    start, seq, next_frame = time.monotonic(), 0, 0
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

    try:
        while time.monotonic() - start < seconds:
            if time.monotonic() >= next_frame:
                now = millis()
                for kind in TYPES:
                    d = manifest['devices'][kind]['external_id']
                    publish(f'{prefix}/device/{kind}/{d}', static(manifest, kind, now))
                    if sensing and kind in ('radar', 'tdoa', 'aoa', 'rid'):
                        publish(f'{prefix}/device_data/{kind}/{d}', sense(manifest, kind, now, seq))
                metadata = {'deviceId': eo['external_id'], 'codeStatus': 200, 'workState': 1 if tracking else 0,
                            'cameraStatus': {'hfov': 0.8, 'vfov': 0.4, 'panOrientAngle': 1, 'tiltOrientAngle': 2,
                                             'focalLen': 4.8, 'detectDist': 100, 'zoomIndex': 32}}
                publish(eo_topic, {'event': 'HeartBeat', 'edgeId': eo['registration']['edge_id'],
                                   'timestamp': now, 'metadata': metadata})
                if tracking:
                    report = dict(tracking, timestamp=now)
                    publish(eo_topic, report)
                seq += 1
                next_frame = time.monotonic() + 1
            while not replies.empty():
                topic, payload = replies.get_nowait()
                with (OUT / batch / 'commands.ndjson').open('a', encoding='utf-8') as log:
                    log.write(json.dumps({'at': millis(), 'topic': topic, 'payload': payload.decode(), 'mode': response_mode}) + '\n')
                if topic.startswith('iot-dispatcher/'):
                    root = json.loads(payload)
                    root['timestamp'] = millis()
                    root['metadata']['codeStatus'] = 200
                    root['metadata']['workState'] = 1 if root['event'] == 'BeginTracking' else 0
                    root['metadata']['aiStatus'] = {'className': 'drone', 'detectConfidence': 0.9, 'trackConfidence': 0.8}
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
    except KeyboardInterrupt:
        print('Stopped by user')
    finally:
        logfile.close()
        client.disconnect()
        client.loop_stop()
    print(f'{batch}: frames={seq}, response={response_mode}, stopped')


def negative(batch):
    """Publish transport negative cases only to this batch's replay namespace."""
    manifest = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    d = manifest['devices']['radar']['external_id']
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
        result = subprocess.run(['docker', 'exec', 'deploy-db-1', 'psql', '-U', 'uav', '-d', 'uav', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql], capture_output=True, encoding='utf-8')
        evidence[name] = json.loads(result.stdout) if result.returncode == 0 else {'error': result.stderr.strip()}
    dump(OUT / batch / 'database-evidence.json', evidence)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


def retained_check(batch):
    """MQTT 3.1.1 marks stored replay as retained only on a new subscription."""
    m = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    d = m['devices']['radar']
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
    p.add_argument('action', choices=['prepare', 'stream', 'negative', 'inspect', 'retained'])
    p.add_argument('--batch', required=True, help='Unique test batch, e.g. manual-0910a')
    p.add_argument('--seconds', type=int, default=300)
    p.add_argument('--response', choices=['success', 'failure', 'none'], default='success')
    p.add_argument('--heartbeat-only', action='store_true')
    a = p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9-]{3,28}', a.batch):
        p.error('batch must be 3-28 letters, digits or hyphens')
    if not 1 <= a.seconds <= 86400:
        p.error('seconds must be 1-86400')
    if a.action == 'prepare':
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
