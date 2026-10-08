"""Read-only acceptance material checks. Requires an existing simulator login.

Run from repository root with simulator modules/dependencies on PYTHONPATH.
No contacts, permissions, business results or requests are written by this check.
"""
import json
import urllib.request
from pathlib import Path
from engine import compile_scene
from realtime_control import validate_config

ROOT = Path(__file__).resolve().parents[4]
STATE = ROOT / 'server/target/simulation-materials'
OUT = ROOT / 'docs/acceptance/simulation-materials'


def read(path):
    request = urllib.request.Request('http://127.0.0.1:8766/api/external/request',
        data=json.dumps({'method': 'GET', 'path': path}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def main():
    prepared = json.loads((STATE / 'prepared-materials.json').read_text(encoding='utf-8'))
    options = read('/local-interface-simulator/plan-options')
    assert not options['unavailable_sections'], options['unavailable_sections']
    filing = prepared['filing']
    assert sum(p['contact_id'] == filing['pilot_contact_id'] for p in options['pilots']) == 1
    assert sum(b['binding_id'] == filing['source_binding_id'] and b['enabled']
               and b['org_id'] == filing['operator_org_id'] for b in options['source_bindings']) == 1
    detail = read('/local-interface-simulator/plans/' + prepared['plan']['plan_id'] + '/filing')
    assert detail['subjects']['association_status'] == 'LINKED'
    for key in ('source_binding_id', 'operator_org_id', 'pilot_contact_id'):
        assert detail['subjects'][key] == filing[key]
    assert detail['plan']['source_mode'] == 'mock'
    assert detail['plan']['uav_sn'] == prepared['plan']['uav_sn']
    for key in ('start_at', 'end_at'):
        assert detail['plan'][key] == prepared['plan'][key]
    assert detail['plan']['route']['route_version_id'] == prepared['plan']['route_version_id']

    counts = {}
    for folder in (OUT / 'scene-templates', STATE / 'ready-scenes'):
        paths = sorted(folder.glob('*.json'))
        assert len(paths) == 17
        for path in paths:
            value = json.loads(path.read_text(encoding='utf-8'))
            compile_scene(value)
            if folder.name == 'ready-scenes':
                assert value['fullchain']['filing'] == filing, path
            else:
                assert not value['fullchain']['filing'], path
        counts[folder.name] = len(paths)
    configs = list((OUT / 'notification-configs').glob('*.json'))
    assert len(configs) == 8
    for path in configs:
        config = validate_config(json.loads(path.read_text(encoding='utf-8')))
        assert config['play_seconds'] >= 16.029 and not config['countermeasure_enabled']

    trace = json.loads((STATE / 'airspace-validation.json').read_text(encoding='utf-8'))
    assert len(trace) == 6
    for item in trace[:3] + trace[5:]:
        assert item['http_status'] == 200 and item['response']['state'] == 'ACCEPTED'
        assert item['response']['source_mode'] == 'mock'
    for item, expected in zip(trace[3:5], ('下发版本必须大于已接收版本', '同一消息编号的内容已变化')):
        assert item['http_status'] == 400
        assert '409' in item['response']['error'] and expected in item['response']['error']
    assert trace[0]['response'] == trace[1]['response']
    assert len({item['response']['airspace_id'] for item in trace[:3] + trace[5:]}) == 1
    assert trace[0]['response']['airspace_version_id'] != trace[2]['response']['airspace_version_id']
    assert trace[2]['response']['airspace_version_id'] == trace[5]['response']['airspace_version_id']
    context = read('/local-interface-simulator/airspaces/context')
    current = [r for r in context['items'] if r['airspace_id'] == trace[5]['response']['airspace_id']]
    # The existing context contract returns the latest delivery for each airspace.
    assert len(current) == 1
    assert current[0]['revision'] == 3 and current[0]['action'] == 'WITHDRAW'

    journal = json.loads((STATE / 'journal.json').read_text(encoding='utf-8'))
    states = {s: sum(r['state'] == s for r in journal['requests'].values())
              for s in ('ACCEPTED', 'REJECTED', 'UNKNOWN', 'PENDING')}
    assert states['UNKNOWN'] == states['PENDING'] == 0
    result = {'status': 'PASS', 'files_checked': counts, 'notification_configs': len(configs),
              'airspace_http_cases': 6, 'pilot_and_filing_available': True, 'plan_linked': True,
              'write_journal_states': states, 'business_closed_loop_verified': False}
    (STATE / 'material-validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
