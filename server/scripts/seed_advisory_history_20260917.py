#!/usr/bin/env python3
"""Two bounded mock history fixtures. No provider calls, approvals or device commands."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid
from datetime import datetime, timezone

BATCH = 'advisory-history-20260917-v1'
ACTOR = '7c5d304f-9ca2-3151-a1d9-4f333553e4d1'
SCENARIOS = [
    dict(event='99f556ed-1743-4fe7-bd1f-7f4c2fdf673f', alarm='adbb2261-e63e-4284-9cf1-6d9778023d00', version=1,
         at='2026-09-17T03:52:31Z', no='AUTH-DEMO-20260917-01', status='REQUESTED', urgent=True),
    dict(event='2f17591b-94d0-472e-b006-c11743f74982', alarm='a5107d50-99bc-4c2a-9f23-dafa59785639', version=2,
         at='2026-09-17T03:57:07Z', no='AUTH-DEMO-20260917-02', status='CANCELLED', urgent=False),
]
OWN_TABLES = ['uav_event_advisory','disposal_authorization','disposal_authorization_event']

def identity(role):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, BATCH + '/' + role))

def q(value):
    if value is None: return 'NULL'
    if isinstance(value, bool): return 'TRUE' if value else 'FALSE'
    if isinstance(value, int): return str(value)
    return "'" + str(value).replace("'", "''") + "'"

def insert(table, row):
    return f"INSERT INTO {table} ({','.join(row)}) VALUES ({','.join(q(v) for v in row.values())});"

def run(sql):
    proc = subprocess.run(['docker','exec','-i','deploy-db-1','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','uav','-d','houtaiguanli'],
                          input=f'SET search_path TO {args.schema};\n'+sql, text=True, capture_output=True)
    if proc.returncode: raise RuntimeError(proc.stderr.strip())
    return proc.stdout.strip()

def data(sql): return json.loads(run(sql))
def rows(table, where='TRUE'):
    return data(f"SELECT coalesce(json_agg(t ORDER BY row_to_json(t)::text),'[]'::json) FROM (SELECT * FROM {table} WHERE {where}) t;")
def digest(value): return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()

def capture():
    events=','.join(q(s['event']) for s in SCENARIOS)
    alarms=','.join(q(s['alarm']) for s in SCENARIOS)
    return {
        'events':rows('uav_event',f'event_id IN ({events})'),
        'observations':rows('uav_event_advisory',f'event_id IN ({events})'),
        'authorizations':rows('disposal_authorization',f'subject_id IN ({events})'),
        'authorization_events':rows('disposal_authorization_event',f'authorization_id IN (SELECT authorization_id FROM disposal_authorization WHERE subject_id IN ({events}))'),
        'protected': {table:digest(rows(table,where)) for table,where in [
            ('alarm',f'alarm_id IN ({alarms})'),('uav_event_verification',f'event_id IN ({events})'),
            ('uav_auto_sms_task',f'event_id IN ({events})'),('uav_auto_voice_task',f'event_id IN ({events})'),
            ('handoff','TRUE'),('handoff_material_snapshot','TRUE')]}
    }

def plan(imported):
    statements=[]; owned={t:[] for t in OWN_TABLES}; expected={}
    for i,s in enumerate(SCENARIOS,1):
        stamp=datetime.fromisoformat(s['at'].replace('Z','+00:00')); at=int(stamp.timestamp()*1000)
        marker=f"模拟历史补录；批次 {BATCH}；场景时间见记录；实际补录 {imported}。合成演练情节，非真实现场事实、非当时系统自动执行。"
        obs=dict(record_id=identity(f'{i}/observation'),event_id=s['event'],event_version=s['version']+1,
                 kind='OBSERVATION',created_at=at,actor_id=ACTOR,actor_label='演练记录员（模拟历史补录）',outcome='STILL_INSIDE',danger='HIGH',
                 note=marker+('演练设定：目标仍在限制区域内接近重要设施，危险度高；短信尚未发送，按紧急情节提出申请，未获批准。' if s['urgent'] else '演练设定：既有模拟短信之后仍未离开限制区域，危险度高，提出反制申请。'),
                 urgent=s['urgent'],simulated=False,trigger_mode='MANUAL')
        statements.append(insert('uav_event_advisory',obs));owned['uav_event_advisory'].append(obs['record_id'])
        auth_id=identity(f'{i}/authorization');owned['disposal_authorization'].append(auth_id)
        auth_time=datetime.fromtimestamp((at+30000)/1000,timezone.utc).isoformat()
        final_time=datetime.fromtimestamp((at+(150000 if s['status']=='CANCELLED' else 30000))/1000,timezone.utc).isoformat()
        statements.append(f"INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at,result_code,result_detail) SELECT {q(auth_id)},{q(s['no'])},'COUNTERMEASURE','UAV_EVENT',e.event_id,a.target_id,'MANUAL',{q(marker+'按演练观察申请，仅用于展示；没有真实批准或设备指令。')},{q(ACTOR)},{q(auth_time)}::timestamptz,{q(s['status'])},'demo-v1',e.owner_org_id,e.district_id,'mock',{1 if s['status']=='CANCELLED' else 0},{q(imported)}::timestamptz,{q(imported)}::timestamptz,{q('CANCELLED_BY_REQUESTER' if s['status']=='CANCELLED' else None)},{q(marker+'后续位置不明，撤回演练申请；不认定已飞离。' if s['status']=='CANCELLED' else None)} FROM uav_event e JOIN alarm a USING(alarm_id) WHERE e.event_id={q(s['event'])};")
        stages=[('REQUEST',auth_time,'REQUESTED')]
        if s['status']=='CANCELLED':
            later=dict(obs, record_id=identity(f'{i}/unknown'),event_version=s['version']+2,created_at=at+120000,
                       outcome='UNKNOWN',danger='UNKNOWN',urgent=False,note=marker+'演练后续：无法确认目标最新位置与危险度，不能推断已飞离；撤回反制申请，等待有效依据。')
            statements.append(insert('uav_event_advisory',later));owned['uav_event_advisory'].append(later['record_id'])
            stages.append(('CANCEL',final_time,'CANCELLED'))
        for kind,when,status in stages:
            event_id=identity(f'{i}/{kind}');owned['disposal_authorization_event'].append(event_id)
            statements.append(insert('disposal_authorization_event',dict(event_id=event_id,authorization_id=auth_id,event_kind=kind,
                 actor_id=ACTOR,note=marker+('演练申请待审批，未批准、未执行。' if kind=='REQUEST' else '演练目标情况不明，撤回申请。'),
                 snapshot=json.dumps(dict(status=status,simulated=True,batch=BATCH,imported_at=imported,channel='MANUAL'),ensure_ascii=False),occurred_at=when)))
        count=2 if s['status']=='CANCELLED' else 1;expected[s['event']]=s['version']+count
        statements.append(f"UPDATE uav_event SET version=version+{count},updated_at={q(imported)}::timestamptz WHERE event_id={q(s['event'])};")
        statements.append(insert('audit_log',dict(audit_id=identity(f'{i}/audit'),user_id=ACTOR,account='admin1',action='demo_advisory_history_imported',
             object_type='uav_event',object_id=s['event'],module_code='alarm',role_code=None,result='SUCCESS',
             occurred_at=int(datetime.fromisoformat(imported).timestamp()*1000),detail=marker+f' old_version={s["version"]}; new_version={expected[s["event"]]}; authorization={auth_id}',ip='',user_agent='controlled-history-fixture')))
    return statements,owned,expected

def guards():
    checks=["IF current_database()<>'houtaiguanli' THEN RAISE EXCEPTION 'Wrong database'; END IF;",
            f"IF NOT EXISTS(SELECT 1 FROM app_user WHERE user_id={q(ACTOR)} AND account='admin1') THEN RAISE EXCEPTION 'Missing local actor'; END IF;",
            "IF NOT EXISTS(SELECT 1 FROM disposal_policy WHERE policy_code='demo-v1' AND schema_status='DEMO') THEN RAISE EXCEPTION 'Missing demo policy'; END IF;"]
    for s in SCENARIOS:
        checks += [f"PERFORM 1 FROM uav_event WHERE event_id={q(s['event'])} FOR UPDATE;",
          f"IF NOT EXISTS(SELECT 1 FROM uav_event e JOIN alarm a USING(alarm_id) WHERE e.event_id={q(s['event'])} AND e.version={s['version']} AND e.state_code='CONFIRMED' AND a.alarm_id={q(s['alarm'])} AND a.source_mode='mock' AND a.target_id IS NOT NULL) THEN RAISE EXCEPTION 'Event changed or nonmock'; END IF;",
          f"IF EXISTS(SELECT 1 FROM uav_event_advisory WHERE event_id={q(s['event'])} AND kind<>'SMS_SIMULATED') OR EXISTS(SELECT 1 FROM disposal_authorization WHERE subject_id={q(s['event'])}) OR EXISTS(SELECT 1 FROM handoff WHERE source_id={q(s['event'])}) THEN RAISE EXCEPTION 'Existing handling must be preserved'; END IF;",
          f"IF EXISTS(SELECT 1 FROM uav_event_verification WHERE event_id={q(s['event'])} AND created_at>={q(s['at'])}::timestamptz) THEN RAISE EXCEPTION 'Observation before confirmation'; END IF;"]
    return 'DO $guard$ BEGIN '+ '\n'.join(checks)+' END $guard$;'

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('mode',choices=['preview','apply','verify','rollback'])
parser.add_argument('--schema',choices=['public','advisory_fixture_20260917'],default='public')
parser.add_argument('--manifest',type=Path,required=True)
parser.add_argument('--fail-before-commit',action='store_true')
args=parser.parse_args()
if args.fail_before_commit and args.schema=='public': raise SystemExit('Failure injection is isolated-fixture only')
current=capture()
if args.mode=='preview':
    run('BEGIN;'+guards()+'ROLLBACK;')
    print(json.dumps(dict(batch=BATCH,scenarios=SCENARIOS,planned_observations=3,planned_authorizations=2,device_commands=0),ensure_ascii=False,indent=2))
elif args.mode=='apply':
    if args.manifest.exists():
        manifest=json.loads(args.manifest.read_text())
        if 'after' in manifest:
            assert current==manifest['after'], 'Batch changed; preserve later work and inspect manually'
            print('Already applied; zero new rows');raise SystemExit(0)
        assert current==manifest['before'], 'State changed after preparation'
    else:
        manifest=dict(batch=BATCH,imported_at=datetime.now(timezone.utc).isoformat(),before=current)
        args.manifest.parent.mkdir(parents=True,exist_ok=True)
        args.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    statements,owned,expected=plan(manifest['imported_at']);manifest.update(owned=owned,expected=expected)
    args.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    run('BEGIN;'+guards()+'\n'.join(statements)+( "SELECT 1/0;" if args.fail_before_commit else '')+'COMMIT;')
    after=capture();assert after['protected']==current['protected'], 'Protected facts changed'
    for event in after['events']: assert event['version']==expected[event['event_id']]
    assert len(after['observations'])==len(current['observations'])+3
    assert len(after['authorizations'])==2 and len(after['authorization_events'])==3
    manifest['after']=after;args.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    print('Applied: 3 observations, 2 authorizations, 3 authorization events; protected facts unchanged')
elif args.mode=='verify':
    manifest=json.loads(args.manifest.read_text());assert current==manifest['after'], 'Batch or protected facts changed'
    assert all(a['device_id'] is None and a['approved_by'] is None and a['execution_command_id'] is None for a in current['authorizations'])
    print('Verified exact rows, versions, protected facts and no approval/device execution')
else:
    manifest=json.loads(args.manifest.read_text());assert current==manifest['after'], 'Later changes exist; automatic rollback refused'
    sql=['BEGIN;']
    for e in current['events']:
        sql.append(f"SELECT 1 FROM uav_event WHERE event_id={q(e['event_id'])} FOR UPDATE;")
        sql.append(f"DO $g$ BEGIN IF NOT EXISTS(SELECT 1 FROM uav_event WHERE event_id={q(e['event_id'])} AND version={e['version']}) THEN RAISE EXCEPTION 'Concurrent update'; END IF; END $g$;")
    for row in current['authorizations']:
        sql.append(f"SELECT 1 FROM disposal_authorization WHERE authorization_id={q(row['authorization_id'])} FOR UPDATE;")
    for table,key,records in [('uav_event','event_id',current['events']),('uav_event_advisory','record_id',current['observations']),('disposal_authorization','authorization_id',current['authorizations']),('disposal_authorization_event','event_id',current['authorization_events'])]:
        for row in records:
            sql.append(f"DO $g$ BEGIN IF NOT EXISTS(SELECT 1 FROM {table} t WHERE {key}={q(row[key])} AND to_jsonb(t)={q(json.dumps(row,ensure_ascii=False))}::jsonb) THEN RAISE EXCEPTION 'Owned record changed'; END IF; END $g$;")
    for scenario in SCENARIOS:
        sql.append(f"DO $g$ BEGIN IF EXISTS(SELECT 1 FROM handoff WHERE source_id={q(scenario['event'])}) THEN RAISE EXCEPTION 'Frozen handoff created'; END IF; END $g$;")
    for table,key in [('disposal_authorization_event','event_id'),('disposal_authorization','authorization_id'),('uav_event_advisory','record_id')]:
        sql.append(f"DELETE FROM {table} WHERE {key} IN ({','.join(q(v) for v in manifest['owned'][table])});")
    for e in manifest['before']['events']:
        sql.append(f"UPDATE uav_event SET version={e['version']},updated_at={q(e['updated_at'])}::timestamptz WHERE event_id={q(e['event_id'])};")
    sql.append(insert('audit_log',dict(audit_id=identity('rollback'),action='demo_advisory_history_rolled_back',object_type='demo_batch',object_id=BATCH,detail='Controlled rollback; original import audit retained',occurred_at=int(datetime.now(timezone.utc).timestamp()*1000),result='SUCCESS')))
    sql.append('COMMIT;');run('\n'.join(sql));assert capture()==manifest['before']
    print('Rolled back owned records; original import audit retained')
