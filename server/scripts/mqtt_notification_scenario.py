#!/usr/bin/env python3
"""Single local replay UAV + plan + pilot for real backend notification testing.

Never inserts alarms, evaluations, notification receipts or disposal results.
Only batch-owned prerequisites are seeded; API writes preserve audit/version checks.
The MOCK SMS setting is time limited and its previous value is saved outside the repo.
"""
import argparse
import json
import math
import os
from pathlib import Path
import queue
import re
import tempfile
import time
import uuid
import urllib.request
import urllib.error

from mqtt_business_test import Api, dump, local_psql, millis, sql_literal
from publish_lingyun_ndjson import mqtt_client


class ScenarioApi(Api):
    def call(self, method, path, body=None, key=None, log=False):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if method != 'GET':
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
        request = urllib.request.Request(self.base + path, headers=headers, method=method,
            data=None if body is None else json.dumps(body).encode('utf-8'))
        # All allowed destinations are localhost; do not send local test traffic through a system proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(request, timeout=45) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)


def run(batch, seconds, output):
    if 'prod' in os.environ.get('SPRING_PROFILES_ACTIVE', 'local').lower():
        raise ValueError('Production profiles are forbidden')
    os.environ.setdefault('MQTT_TEST_API', 'http://127.0.0.1:8081/api/v1')
    folder = output / batch
    if (folder / 'manifest.json').exists():
        raise ValueError('Use a new batch; old scenario times and records must stay unchanged')
    api = ScenarioApi(batch)
    broker = next(b for b in api.data('GET', '/mqtt-brokers') if b['name'] == 'local-lingyun-replay')
    if broker['source_mode'] != 'replay' or broker['host'] not in ('127.0.0.1', 'localhost') or not broker['enabled']:
        raise ValueError('Enabled local replay broker required')
    setting_row = local_psql("SELECT channel_type || '|' || version FROM notification_setting WHERE setting_id='advisory-sms'")
    channel_type, version = setting_row.split('|', 1)
    if channel_type not in ('NONE', 'MOCK'):
        raise ValueError('Do not replace a real channel')
    setting = {'setting_id': 'advisory-sms', 'channel_type': channel_type, 'version': int(version)}
    # Do not activate notifications for unrelated currently fresh alarms.
    if int(local_psql("SELECT count(*) FROM alarm WHERE received_at>now()-interval '5 minutes'")):
        raise ValueError('Other fresh alarms exist; do not change shared notification settings')
    ids = {k: str(uuid.uuid5(uuid.NAMESPACE_URL, 'mqtt-notice:' + batch + ':' + k))
           for k in ('route', 'route_version', 'plan', 'district', 'airspace', 'airspace_version')}
    literal = sql_literal
    for table, column, key in [('route','route_id','route'), ('route_version','route_version_id','route_version'), ('flight_plan','plan_id','plan')]:
        if int(local_psql(f'SELECT count(*) FROM {table} WHERE {column}={literal(ids[key])}')):
            raise ValueError('Batch exists in database; choose a new batch')
    dump(folder / 'before.json', {'notification_setting': setting, 'batch_rows_before': 0,
                                 'at': millis(), 'ids': ids})
    now = millis()
    expires = now + (seconds + 120) * 1000
    org, district = broker['owner_org_id'], ids['district']
    sn, external = batch + '-UAV', batch + '-R1'
    pilot = api.data('POST', '/contacts', {
        'org_id': org, 'name': 'MQTT模拟飞手 ' + batch, 'roles': ['PILOT'],
        'phone': '00000000000', 'enabled': True, 'valid_until': expires,
        'verified_at': now, 'verification_basis': '本机模拟占位号码，仅用于MOCK通道；批次 ' + batch
    }, batch + '-pilot')
    sql = f"""BEGIN;
    INSERT INTO app_district(district_id,district_code,name,parent_id,enabled,created_at,updated_at,version)
    VALUES({literal(district)},{literal(batch)},{literal('MQTT隔离模拟区域 '+batch)},{literal(broker['district_id'])},true,{now},{now},0);
    INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
    VALUES({literal(ids['route'])},{literal(batch+'-route')},{literal('MQTT通知测试 '+batch)},true,'replay',{literal(org)},{literal(district)},now(),now(),0);
    INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,change_reason,created_at)
    VALUES({literal(ids['route_version'])},{literal(ids['route'])},1,ST_GeomFromText('LINESTRING(118.000 37.000,118.010 37.000)',4326),200,10,200,'AGL',now(),{literal('独立MQTT通知测试 '+batch)},now());
    INSERT INTO airspace(airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version)
    VALUES({literal(ids['airspace'])},{literal(batch+'-zone')},{literal('MQTT临时禁飞模拟 '+batch)},'replay',{literal(org)},{literal(district)},now(),now(),0);
    INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,valid_to,change_reason,created_at)
    VALUES({literal(ids['airspace_version'])},{literal(ids['airspace'])},1,'PROHIBITED',ST_GeomFromText('POLYGON((118.000 36.999,118.010 36.999,118.010 37.001,118.000 37.001,118.000 36.999))',4326),now(),to_timestamp({expires}/1000.0),{literal('仅本批次隔离区域模拟禁飞规则')},now());
    INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)
    VALUES({literal(ids['plan'])},{literal('MQTT-'+batch)},'EXECUTING','replay',{literal(sn)},now(),to_timestamp({expires}/1000.0),{literal(ids['route_version'])},{literal(org)},{literal(district)},now(),now(),0);
    COMMIT;"""
    dump(folder / 'prerequisites.json', {'ids': ids, 'pilot_contact_id': pilot['contact_id'],
         'source_mode': 'replay', 'expires_at': expires, 'sql': sql})
    local_psql(sql)
    subjects = api.data('GET', '/flight-plans/' + ids['plan'] + '/subjects')
    api.data('PATCH', '/flight-plans/' + ids['plan'] + '/subjects', {
        'operator_org_id': org, 'pilot_contact_id': pilot['contact_id'],
        'expected_version': subjects['version'], 'reason': '配套独立MQTT通知模拟场景 '+batch
    }, batch + '-subjects')
    broker = api.data('POST', '/mqtt-brokers', {
        'name':batch, 'host':broker['host'], 'port':broker['port'], 'tls':False,
        'allowed_cidrs':'127.0.0.1/32', 'source_mode':'replay',
        'owner_org_id':org, 'district_id':district
    }, batch + '-broker')
    broker = api.data('PATCH', '/mqtt-brokers/' + broker['broker_id'] + '/enabled', {
        'version':broker['version'], 'enabled':True
    }, batch + '-broker-enable')
    device = api.data('POST', '/devices/onboard', {
        'protocol_code':'LINGYUN_MQTT_V8_6', 'broker_id':broker['broker_id'],
        'provider_code':'mqtt-test', 'external_device_id':external, 'device_type_abbr':'tdoa',
        'source_mode':'replay', 'owner_org_id':org, 'district_id':district,
        'device_no':external, 'name':'通知流程模拟TDOA '+batch, 'vendor':'本机模拟',
        'model':'MQTT-NOTICE', 'longitude':118.005, 'latitude':37.001, 'altitude_m':10
    }, batch + '-device')
    radar_external = batch + '-R2'
    radar = api.data('POST', '/devices/onboard', {
        'protocol_code':'LINGYUN_MQTT_V8_6', 'broker_id':broker['broker_id'],
        'provider_code':'mqtt-test', 'external_device_id':radar_external, 'device_type_abbr':'radar',
        'source_mode':'replay', 'owner_org_id':org, 'district_id':district,
        'device_no':radar_external, 'name':'通知流程模拟雷达 '+batch, 'vendor':'本机模拟',
        'model':'MQTT-NOTICE', 'longitude':118.005, 'latitude':37.001, 'altitude_m':10
    }, batch + '-radar')
    eo_external = batch + '-E1'
    eo_edge = eo_external + '-edge'
    eo = api.data('POST', '/devices/onboard', {
        'protocol_code':'EO_EDGE_MQTT_20250826', 'broker_id':broker['broker_id'],
        'provider_code':'mqtt-test', 'external_device_id':eo_external, 'device_type_abbr':'eo',
        'edge_id':eo_edge, 'source_mode':'replay', 'owner_org_id':org, 'district_id':district,
        'device_no':eo_external, 'name':'通知流程模拟光电 '+batch, 'vendor':'本机模拟',
        'model':'MQTT-NOTICE', 'longitude':118.005, 'latitude':37.001, 'altitude_m':10
    }, batch + '-eo')
    api.data('PUT', '/devices/' + eo['device']['device_id'] + '/sensing-profile', {
        'coverage_kind':'SECTOR', 'range_m':3000, 'azimuth_deg':180, 'fov_deg':70,
        'source_label':'MQTT 通知场景光电', 'expected_version':0
    }, batch + '-eo-coverage')
    local_psql(
        "UPDATE notification_setting SET channel_type='MOCK', enabled=TRUE, "
        f"valid_until={int(expires)}, version=version+1, updated_at={millis()} "
        "WHERE setting_id='advisory-sms' AND channel_type IN ('NONE','MOCK')"
    )
    manifest = dict(batch=batch, ids=ids, pilot_contact_id=pilot['contact_id'],
                    device_id=device['device']['device_id'], radar_device_id=radar['device']['device_id'],
                    eo_device_id=eo['device']['device_id'], eo_external_id=eo_external, eo_edge_id=eo_edge,
                    broker_id=broker['broker_id'], sn=sn, started_at=millis(),
                    duration_seconds=seconds, expires_at=expires, source_mode='replay', channel='MOCK')
    dump(folder / 'manifest.json', manifest)
    print(json.dumps(manifest, ensure_ascii=False), flush=True)
    stream(manifest, broker, folder, seconds)


def eo_heartbeat(edge_id, device_id, now, tracking):
    return {'event':'HeartBeat', 'edgeId':edge_id, 'timestamp':now, 'metadata':{
        'deviceId':device_id, 'codeStatus':200, 'workState':1 if tracking else 0,
        'cameraStatus':{'hfov':0.8, 'vfov':0.4, 'panOrientAngle':1, 'tiltOrientAngle':2,
                        'focalLen':4.8, 'detectDist':3000, 'zoomIndex':32}}}


def eo_track_report(command):
    """把平台下发的 BeginTracking 回成光电测量。分类置信度只认 aiStatus.detectConfidence。"""
    root = json.loads(command)
    guided = (root.get('metadata') or {}).get('objectData') or {}
    root['timestamp'] = millis()
    metadata = root.setdefault('metadata', {})
    metadata['codeStatus'] = 200
    metadata['workState'] = 1
    metadata['aiStatus'] = {
        'className':'drone', 'detectConfidence':0.9, 'trackConfidence':0.8,
        'longitude':guided.get('longitude', 118.005), 'latitude':guided.get('latitude', 37.0),
        'altitude':guided.get('altitude', 180)
    }
    return root


def scenario_target(api, manifest):
    district = manifest['ids']['district']
    page = api.data('GET', f"/targets?page=1&size=50&seen_from={manifest['started_at'] - 1000}&seen_to={millis() + 5000}")
    for row in page.get('items') or []:
        if row.get('district_id') not in (None, district):
            continue
        detail = api.data('GET', '/targets/' + row['target_id'])
        if detail.get('district_id') != district:
            continue
        links = detail.get('source_links') or []
        if any(str(link.get('external_target_id')) == '90001' for link in links) or detail.get('uav_sn') == manifest['sn']:
            return detail
    return None


def stream(manifest, broker, folder, seconds):
    batch, sn = manifest['batch'], manifest['sn']
    external, radar_external = batch + '-R1', batch + '-R2'
    eo_external, eo_edge = manifest.get('eo_external_id'), manifest.get('eo_edge_id')
    eo_topic = 'iot-reporting/cmlc/edge/' + eo_edge if eo_edge else None
    replies = queue.Queue()
    client = mqtt_client(batch)

    def on_connect(c, _u, _f, reason, *_args):
        if int(getattr(reason, 'value', reason) or 0) == 0 and eo_topic:
            c.subscribe('iot-dispatcher/cmlc/edge/+', qos=1)

    def on_message(_c, _u, message):
        if message.topic.startswith('iot-dispatcher/cmlc/edge/'):
            replies.put(bytes(message.payload))

    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(broker['host'], broker['port'], 30)
    client.loop_start()
    api = ScenarioApi(batch)
    start, seq = time.monotonic(), int(time.time())
    last_sense, last_track_try, tracking = 0, 0, None
    try:
        with (folder / 'published.ndjson').open('a', encoding='utf-8') as log:
            def publish(topic, payload):
                sent = client.publish(topic, json.dumps(payload).encode(), qos=1, retain=False)
                sent.wait_for_publish(10)
                if not sent.is_published():
                    raise RuntimeError('MQTT publish was not acknowledged')
                log.write(json.dumps({'topic':topic, 'payload':payload}) + '\n')
                log.flush()

            while time.monotonic() - start < seconds:
                now = millis()
                if eo_topic:
                    publish(eo_topic, eo_heartbeat(eo_edge, eo_external, now, tracking is not None))
                if now - last_sense >= 2000:
                    elapsed = (now - manifest['started_at']) / 1000
                    angle = elapsed / 40
                    obj = {'objectId':90001, 'time':now, 'longitude':118.005+0.003*math.sin(angle),
                           'latitude':37.0, 'altitude':180, 'height':170,
                           'extension':{'objectType':30, 'probability':0.98, 'uavSN':sn,
                                        'pilotLon':118.005, 'pilotLat':37.0005,
                                        'speedX':6.67*math.cos(angle), 'speedY':0, 'speedZ':0}}
                    for kind, device_type, device_external in [('tdoa',10,external),('radar',1,radar_external)]:
                        heartbeat = dict(deviceId=device_external, providerCode='mqtt-test', deviceName=device_external,
                            deviceType=device_type, workState=1, ptTime=now, deviceLongitude=118.005,
                            deviceLatitude=37.001, deviceAltitude=10)
                        publish(f'bridge/mqtt-test/device/{kind}/{device_external}', heartbeat)
                        publish(f'bridge/mqtt-test/device_data/{kind}/{device_external}',
                                dict(deviceId=device_external, ptTime=now, msgCnt=seq, objects=[obj]))
                    if tracking:
                        report = dict(tracking)
                        report['timestamp'] = now
                        publish(eo_topic, report)
                    last_sense = now
                    seq += 1
                while not replies.empty():
                    payload = replies.get_nowait()
                    root = json.loads(payload)
                    if root.get('edgeId') != eo_edge or root.get('event') != 'BeginTracking':
                        continue
                    tracking = eo_track_report(payload)
                    publish(eo_topic, tracking)
                    print(json.dumps({'eo_tracking':tracking['metadata'].get('taskId'), 'detect_confidence':0.9}, ensure_ascii=False), flush=True)
                if eo_topic and tracking is None and now - last_track_try >= 5000:
                    last_track_try = now
                    try:
                        target = scenario_target(api, manifest)
                        if target:
                            status, result = api.call('POST', '/targets/' + target['target_id'] + '/eo-tracking-tasks',
                                                      {'device_id':manifest['eo_device_id'], 'reason':'MQTT 通知场景光电跟踪'},
                                                      batch + '-eo-track-' + str(int(now / 5000)))
                            if status not in (200, 202) or not result.get('ok'):
                                print(json.dumps({'eo_track_request':status, 'error':result.get('error')}, ensure_ascii=False), flush=True)
                    except RuntimeError as failed:
                        print(json.dumps({'eo_track_request':'error', 'message':str(failed)}, ensure_ascii=False), flush=True)
                time.sleep(1)
    finally:
        client.disconnect()
        client.loop_stop()
        dump(folder / 'stopped.json', {'at':millis(), 'reason':'finite stream stopped; this is not evidence of departure',
                                        'eo_tracking':tracking is not None})


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--batch', default=time.strftime('notice-%m%d-%H%M%S'))
    p.add_argument('--seconds', type=int, default=900)
    p.add_argument('--resume', action='store_true', help='Resume only an unexpired prepared batch; never renew old event or configuration times')
    p.add_argument('--output',type=Path,default=Path(tempfile.gettempdir())/'uav-notification-scenarios')
    args=p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9-]{3,24}',args.batch) or not 30<=args.seconds<=1800:
        p.error('batch: 3-24 alphanumeric/hyphen characters; seconds: 30-1800')
    if args.resume:
        if 'prod' in os.environ.get('SPRING_PROFILES_ACTIVE', 'local').lower():
            p.error('Production profiles are forbidden')
        os.environ.setdefault('MQTT_TEST_API', 'http://127.0.0.1:8081/api/v1')
        manifest = json.loads((args.output / args.batch / 'manifest.json').read_text(encoding='utf-8'))
        remaining = min(args.seconds, int((manifest['expires_at'] - millis()) / 1000) - 120)
        if remaining <= 0 or manifest.get('channel') != 'MOCK' or manifest.get('source_mode') != 'replay':
            p.error('Only an unexpired local MOCK replay batch may resume')
        broker = ScenarioApi(args.batch).data('GET', '/mqtt-brokers/' + manifest['broker_id'])
        if broker['host'] not in ('127.0.0.1','localhost') or not broker['enabled'] or broker['source_mode'] != 'replay':
            p.error('An enabled local replay broker is required')
        dump(args.output / args.batch / ('resume-' + str(millis()) + '.json'),
             {'at':millis(),'remaining_seconds':remaining,'original_expires_at':manifest['expires_at']})
        stream(manifest, broker, args.output / args.batch, remaining)
    else:
        run(args.batch,args.seconds,args.output)
