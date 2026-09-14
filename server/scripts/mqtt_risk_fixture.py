#!/usr/bin/env python3
"""Create one additive local replay plan/route for MQTT C04 testing, never a risk.

The current project has no flight-plan creation API. This explicit local fixture
uses only the documented uav Compose DB. Existing plans and records are untouched.
Run after mqtt_business_test.py prepare; use a new batch when its window expires.
"""
import argparse
import json
import re
import subprocess
import uuid
from mqtt_business_test import OUT, dump


def prepare(batch):
    if not re.fullmatch(r'[a-zA-Z0-9-]{3,28}', batch):
        raise ValueError('invalid batch')
    manifest = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    registration = manifest['devices']['radar']['registration']
    if manifest['host'] not in ('localhost', '127.0.0.1') or registration['source_mode'] != 'replay':
        raise ValueError('Only local replay devices are permitted')
    org = str(uuid.UUID(registration['owner_org_id']))
    district = str(uuid.UUID(registration['district_id']))
    ids = {kind: str(uuid.uuid5(uuid.NAMESPACE_URL, 'mqtt-local-fixture/' + batch + '/' + kind))
           for kind in ('route', 'route_version', 'plan')}
    sql = f"""BEGIN;
    INSERT INTO route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
      VALUES ('{ids['route']}','MQTT-R-{batch}','MQTT replay test {batch}',true,'replay','{org}','{district}',now(),now(),0)
      ON CONFLICT (route_id) DO NOTHING;
    INSERT INTO route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)
      VALUES ('{ids['route_version']}','{ids['route']}',1,ST_GeomFromEWKT('SRID=4326;LINESTRING(118.619 37.469,118.621 37.471)'),200,20,200,'AMSL',now(),now())
      ON CONFLICT (route_version_id) DO NOTHING;
    INSERT INTO flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)
      VALUES ('{ids['plan']}','MQTT-P-{batch}','APPROVED','replay',now()-interval '1 minute',now()+interval '2 hours',
              '{ids['route_version']}','{org}','{district}',now(),now(),0)
      ON CONFLICT (plan_id) DO NOTHING;
    SELECT row_to_json(q) FROM (SELECT plan_id,plan_no,start_at,end_at,source_mode,route_version_id FROM flight_plan WHERE plan_id='{ids['plan']}') q;
    COMMIT;"""
    result = subprocess.run(['docker', 'exec', '-i', 'deploy-db-1', 'psql', '-U', 'uav', '-d', 'uav', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, capture_output=True, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stderr)
    fixture = json.loads(result.stdout)
    fixture['origin'] = 'Explicit local replay plan fixture; risk must come from MQTT plus C04'
    dump(OUT / batch / 'risk-fixture.json', fixture)
    print(json.dumps(fixture, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch', required=True)
    prepare(parser.parse_args().batch)
