"""Explicit local acceptance preparation through existing authenticated APIs only.

No database access, seeded data, fabricated business results, or password persistence.
The optional loopback UI lets the operator submit existing credentials themselves.
"""
import argparse
import copy
import datetime as dt
import hashlib
import http.server
import json
import math
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import wave
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/acceptance/simulation-materials'
STATE = ROOT / 'server/target/simulation-materials'
API = 'http://127.0.0.1:8081/api/v1'
PREFIX = '/local-interface-simulator'
MARKER = 'SIM-ACC23-V1'
ORG_NAME = '验收模拟单位'
DISTRICT_NAME = '验收模拟区域'
TRANSCRIPT = '这是低空平台验收模拟通知。测试无人机仍位于模拟告警空域，请按演示安排驶离测试区域。本消息仅用于本机模拟，不是真实飞行指令。'
CONTACTS = {
    'pilot': ('验收模拟飞手01', ['PILOT'], '00000000000'),
    'liaison': ('验收模拟报送联系人', ['PLAN_LIAISON'], '00000000001'),
    'penalty': ('验收模拟处罚接收联系人', ['UNIT_LIAISON'], '00000000002'),
    'maintenance': ('验收模拟运维联系人', ['MAINTENANCE'], '00000000003'),
}
READ_ACTIONS = ['device:read', 'target:read', 'alarm:read', 'flight:read', 'route:read',
                'airspace:read', 'assessment:read', 'risk:read', 'handoff:read', 'disposal:read']
ROLE_SPECS = {
    'applicant': {'name': '验收模拟申请执行角色', 'account': 'sim_acc23_operator',
                  'user_name': '验收模拟申请执行员',
                  'operation_modules': ['devices'],
                  'actions': READ_ACTIONS + ['alarm:verify', 'risk:verify', 'handoff:create',
                                             'disposal:request', 'disposal:execute', 'disposal:stop']},
    'approver': {'name': '验收模拟审批角色', 'account': 'sim_acc23_approver',
                 'user_name': '验收模拟审批员', 'operation_modules': [],
                 'actions': READ_ACTIONS + ['disposal:approve']},
}
READ_MODULES = {'dashboard', 'sensing', 'flights', 'legality', 'alarms', 'risk',
                'airspace', 'punishment', 'devices', 'monitoring'}


def public_payload(value):
    """Never persist credentials, including nested credentials in future inputs."""
    if isinstance(value, dict):
        return {k: public_payload(v) for k, v in value.items()
                if not any(s in k.lower() for s in ('password', 'token', 'secret', 'authorization', 'session_id'))}
    if isinstance(value, list):
        return [public_payload(v) for v in value]
    return value


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    tmp.replace(path)


def unique(rows, predicate, label, required=False):
    found = [r for r in rows if predicate(r)]
    if len(found) > 1 or (required and not found):
        raise ValueError(label + '不存在或不唯一，停止配置')
    return found[0] if found else None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('本机资料工具不跟随重定向')


class ApiRejected(ValueError):
    pass


class Platform:
    def __init__(self):
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None, key=None):
        if not path.startswith('/') or path.startswith('//') or '..' in path:
            raise ValueError('非法平台路径')
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(API + path, method=method, headers=headers,
                    data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(request, timeout=25) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            try:
                detail = json.load(error).get('error', {})
                message = str(detail.get('message', '接口拒绝'))
                code = str(detail.get('code', 'HTTP_ERROR'))
            except (ValueError, AttributeError):
                message, code = '接口未返回可读错误', 'HTTP_ERROR'
            exception = ApiRejected if 400 <= error.code < 500 else ValueError
            raise exception(f'{path} HTTP {error.code} {code}: {message}') from None
        if not result.get('ok'):
            raise ValueError(path + ': 平台未受理')
        return result.get('data')

    def login(self, account, password):
        result = self.call('POST', '/auth/login', {'account': account, 'password': password})
        self.token = result['session_id']
        if result.get('must_change_password'):
            raise ValueError('现有管理账号需要先在系统原页面完成改密')

    def close(self):
        if self.token:
            try:
                self.call('POST', '/auth/logout', {})
            except Exception:
                pass
        self.token = None


class Preparation:
    def __init__(self, api, directory=STATE, progress=lambda text: None):
        self.api, self.directory, self.progress = api, directory, progress
        self.file = directory / 'journal.json'
        self.journal = json.loads(self.file.read_text(encoding='utf-8')) if self.file.exists() else {'marker': MARKER, 'requests': {}}
        if self.journal.get('marker') != MARKER:
            raise ValueError('资料日志不属于本轮')
        self.result = {'marker': MARKER, 'status': 'PREPARING', 'contacts': {}, 'accounts': {}, 'rules': {}, 'warnings': []}

    def get(self, path):
        return self.api.call('GET', path)

    def items(self, path):
        rows, page = [], 1
        while True:
            part = self.get(path + ('&' if '?' in path else '?') + f'page={page}&size=100')
            if isinstance(part, list):
                return part
            rows.extend(part['items'])
            if len(rows) >= part['total']:
                return rows
            page += 1
            if page > 100:
                raise ValueError('目录分页异常')

    def write(self, label, method, path, body):
        # Passwords and tokens must never appear in journals or exports.
        visible = public_payload(body)
        for old in self.journal['requests'].values():
            if (old['label'], old['method'], old['path']) == (label, method, path) and old['state'] in ('PENDING', 'UNKNOWN'):
                raise ValueError(label + '上次结果未知；当前回读未确认，停止重复提交。原幂等键：' + old['key'])
        key = 'acc23-' + digest([method, path, visible])[:48]
        entry = {'label': label, 'method': method, 'path': path, 'body': visible, 'key': key, 'state': 'PENDING'}
        self.journal['requests'][key] = entry
        save(self.file, self.journal)
        try:
            result = self.api.call(method, path, body, key)
        except Exception as error:
            entry['state'] = 'REJECTED' if isinstance(error, ApiRejected) else 'UNKNOWN'
            save(self.file, self.journal)
            raise
        entry['state'] = 'ACCEPTED'
        # Persist only references needed to recover a successful response after interruption.
        entry['result_refs'] = {k: v for k, v in (result or {}).items()
                                if k.endswith('_id') or k == 'role_code'}
        save(self.file, self.journal)
        self.progress(label + '：已受理，继续回读')
        return result

    def contact(self, kind, org_id):
        name, roles, phone = CONTACTS[kind]
        row = unique(self.items('/contacts?org_id=' + org_id), lambda r: r['name'] == name, name)
        if not row:
            body = {'org_id': org_id, 'name': name, 'roles': roles, 'phone': phone, 'enabled': True,
                    'verified_at': int(time.time() * 1000),
                    'verification_basis': MARKER + '；仅模拟上级资料中的核验结果；全零测试号码只用于本机接收器；不代表真实联系方式、实名或真实核验。'}
            created = self.write('创建' + name, 'POST', '/contacts', body)
            row = self.get('/contacts/' + created['contact_id'])
        if row['org_id'] != org_id or row['name'] != name or sorted(row['roles']) != sorted(roles) or row.get('phone') != phone or not row['enabled']:
            raise ValueError(name + '资料回读不匹配，不覆盖')
        if not str(row.get('verification_basis', '')).startswith(MARKER):
            raise ValueError(name + '缺少本轮模拟资料标识，不自动接管')
        if row.get('valid_until') is not None and row['valid_until'] <= int(time.time() * 1000):
            raise ValueError(name + '已过期，不自动延长')
        if not row.get('verified_at') or row['verified_at'] > int(time.time() * 1000):
            raise ValueError(name + '模拟核验时间无效')
        return row

    def setup_role(self, kind, catalog, available_actions):
        spec = ROLE_SPECS[kind]
        if not set(spec['actions']) <= available_actions:
            raise ValueError('平台缺少所需动作权限，不补造权限目录')
        matrix = []
        for p in catalog:
            code = p['permission_code']
            level = 'READ' if code in READ_MODULES else 'NONE'
            if code in spec['operation_modules']:
                level = 'OP'
            matrix.append({'permission_code': code, 'level': level, 'menu_enabled': bool(p.get('route_key')) and level != 'NONE'})
        actions = [{'permission_code': c, 'level': 'READ' if c.endswith(':read') else 'OP'} for c in spec['actions']]
        row = unique(self.get('/roles'), lambda r: r['name'] == spec['name'], spec['name'])
        if not row:
            row = self.write('创建' + spec['name'], 'POST', '/roles',
                       {'name': spec['name'], 'description': MARKER + '；验收专用；不含直接反制和用户/角色管理',
                        'permissions': matrix, 'reason': '用户授权准备完整模拟验收资料'})
            self.journal.setdefault('owned_roles', []).append(row['role_code'])
            save(self.file, self.journal)
        row = self.get('/roles/' + row['role_code'])
        actual_matrix = [{k: p[k] for k in ('permission_code', 'level', 'menu_enabled')} for p in row['permissions']]
        ordered = lambda rows: sorted(rows, key=lambda p: p['permission_code'])
        if row.get('builtin') or not row.get('description', '').startswith(MARKER) or ordered(actual_matrix) != ordered(matrix):
            raise ValueError(spec['name'] + '现有配置与资料包不一致，不覆盖')
        if sorted(row['actions'], key=lambda x: x['permission_code']) != sorted(actions, key=lambda x: x['permission_code']):
            created_here = row['role_code'] in self.journal.get('owned_roles', []) or any(
                r.get('result_refs', {}).get('role_code') == row['role_code'] and r['path'] == '/roles'
                and r['state'] == 'ACCEPTED' for r in self.journal['requests'].values())
            if not created_here or row['user_count'] != 0:
                raise ValueError(spec['name'] + '已有权限不一致，不扩大现有账号权限')
            self.write('配置' + spec['name'], 'PUT', '/roles/' + row['role_code'] + '/permissions',
                       {'permissions': matrix, 'actions': actions, 'expected_version': row['version'], 'reason': MARKER + ' 模拟普通申请/异人审批分权'})
        final = self.get('/roles/' + row['role_code'])
        if sorted(final['actions'], key=lambda x: x['permission_code']) != sorted(actions, key=lambda x: x['permission_code']):
            raise ValueError('动作权限回读不一致')
        return final

    def account(self, kind, role, org_id, password):
        spec = ROLE_SPECS[kind]
        row = unique(self.items('/users?keyword=' + spec['account']), lambda r: r['account'] == spec['account'], spec['account'])
        if row:
            if row['role_code'] != role['role_code'] or row['org_id'] != org_id or row['name'] != spec['user_name'] or row['status'] != 'ACTIVE':
                raise ValueError(spec['account'] + '已存在且不匹配，不改权限或重置密码')
        else:
            if not password or not 6 <= len(password) <= 32 or not all((any(c.isupper() for c in password), any(c.islower() for c in password), any(c.isdigit() for c in password), any(not c.isalnum() for c in password))):
                raise ValueError('请为两个测试账号分别填写符合现有规则的临时密码')
            row = self.write('创建测试账号 ' + spec['account'], 'POST', '/users',
                      {'account': spec['account'], 'name': spec['user_name'], 'org_id': org_id,
                       'role_code': role['role_code'], 'temporary_password': password, 'reason': MARKER})
        checked = self.get('/users/' + row['user_id'])
        if any(checked.get(k) != v for k, v in {'account': spec['account'], 'name': spec['user_name'],
               'org_id': org_id, 'role_code': role['role_code'], 'status': 'ACTIVE'}.items()):
            raise ValueError('测试账号回读不一致')
        return checked

    def bootstrap_plan(self, org, district, filing):
        date = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
        start = int(dt.datetime.combine(date, dt.time(), dt.timezone(dt.timedelta(hours=8))).timestamp() * 1000)
        end = start + 86400000 - 60000
        body = {'message_id': 'acc23-material-plan-' + date.strftime('%Y%m%d'),
                'uav_sn': 'SIM-ACC23-MATERIAL-ONLY', 'start_at': start, 'end_at': end,
                'source_mode': 'mock',
                'route': {'name': '验收模拟资料配套航线', 'geometry': {'type': 'LineString', 'coordinates': [[118.6236,37.464],[118.62408,37.464]]},
                          'corridor_width_m': 100, 'min_altitude_m': 0, 'max_altitude_m': 150, 'altitude_datum': 'AMSL',
                          'owner_org_id': org, 'district_id': district},
                'filing': {**filing, 'operator_name': ORG_NAME, 'pilot_name': CONTACTS['pilot'][0],
                           'takeoff_site_name': '验收模拟起飞点', 'landing_site_name': '验收模拟降落点',
                           'takeoff_longitude': 118.6236, 'takeoff_latitude': 37.464,
                           'landing_longitude': 118.62408, 'landing_latitude': 37.464}}
        context = self.get(PREFIX + '/context')
        prior = unique(context['messages'], lambda m: m.get('payload', {}).get('message_id') == body['message_id'], '本日模拟资料计划')
        if prior:
            # Re-read existing plan, never change historical filing or plan windows.
            plan_id = prior['subject_id']
        else:
            plan_id = self.write('录入模拟上级计划及飞手资料 ' + body['message_id'], 'POST', PREFIX + '/plans', body)['subject_id']
        plan = self.get('/flight-plans/' + plan_id)
        subjects = self.get('/flight-plans/' + plan_id + '/subjects')
        if plan['uav_sn'] != body['uav_sn'] or plan['start_at'] != start or plan['end_at'] != end or subjects['association_status'] != 'LINKED' or plan['source_mode'] != 'mock':
            raise ValueError('计划身份、时段或关联回读不一致')
        for k in ('source_binding_id', 'operator_org_id', 'pilot_contact_id'):
            if subjects.get(k) != filing[k]:
                raise ValueError('计划关联不是本批次资料：' + k)
        route = self.get('/route-versions/' + plan['route']['route_version_id'])
        if (route['altitude_datum'] != 'AMSL' or route['min_altitude_m'] != 0
                or route['max_altitude_m'] != 150 or route['corridor_width_m'] != 100
                or route['centerline']['coordinates'] != body['route']['geometry']['coordinates']
                or route['valid_from'] > start or (route.get('valid_to') is not None and route['valid_to'] < end)):
            raise ValueError('配套航线版本、范围或高度基准回读不一致')
        return {'plan_id': plan_id, 'plan_no': plan['plan_no'], 'start_at': start, 'end_at': end,
                'uav_sn': body['uav_sn'], 'route_version_id': route['route_version_id'],
                'altitude_datum': route['altitude_datum'], 'association_status': subjects['association_status']}

    def notifications(self, plan_id, contacts):
        # Previously configured API channels cannot be identified safely by type alone.
        settings = self.get(PREFIX + '/notification-settings')
        specs = [('RISK_NOTICE', None), ('ADVISORY_SMS', None), ('ADVISORY_VOICE', None),
                 ('PLAN_FEEDBACK', contacts['liaison']['contact_id']), ('UAV_PUNISHMENT', contacts['penalty']['contact_id'])]
        old_settings = {}
        owned = self.journal.get('notification_settings', {})
        for purpose, contact in specs:
            candidates = [s for s in settings if s['purpose'] == purpose]
            if len(candidates) > 1:
                raise ValueError(purpose + '有多个配置，不自动选择或覆盖')
            old = candidates[0] if candidates else None
            if old and old['channel_type'] == 'API' and old['enabled']:
                if old != owned.get(purpose):
                    raise ValueError(purpose + '已有非本工具回读确认的 API 通道，不自动接管')
            elif old and old['channel_type'] not in ('NONE', 'MOCK'):
                raise ValueError(purpose + '已有其他通知配置，不覆盖')
            old_settings[purpose] = old
        for purpose, contact in specs:
            old = old_settings[purpose]
            if old and old['channel_type'] == 'API' and old['enabled']:
                self.progress(purpose + '：已有通道保留，等待独立接收器上线')
                continue
            body = {'purpose': purpose, 'plan_id': plan_id, 'expected_version': old['version'] if old else 0}
            if contact:
                body['contact_id'] = contact
            self.write('准备本机通知通道 ' + purpose, 'POST', PREFIX + '/notification-settings', body)
        # Existing authenticated activation converts only explicitly prepared QA endpoints.
        if any(not s or s['channel_type'] != 'API' for s in old_settings.values()):
            self.write('注册本机独立通知接收器', 'POST', PREFIX + '/bindings',
                       {'source_kind': 'NOTIFICATION_CHANNEL', 'source_id': 'receiver', 'enabled': True})
        checked = self.get(PREFIX + '/notification-settings')
        for purpose, _ in specs:
            row = unique(checked, lambda r: r['purpose'] == purpose, purpose, True)
            if row['channel_type'] != 'API' or not row['enabled'] or row.get('valid_until'):
                raise ValueError(purpose + '本机通道激活回读不一致')
            owned[purpose] = row
        self.journal['notification_settings'] = owned
        save(self.file, self.journal)
        return list(owned.values())

    def rule(self):
        row = unique(self.items('/rule-sets'), lambda r: r['rule_set_code'] == 'SPACE-RISK-DEMO', '空间风险规则', True)
        versions = self.items('/rule-sets/SPACE-RISK-DEMO/versions')
        if row.get('active_version_id'):
            active = self.get('/rule-set-versions/' + row['active_version_id'])
            if not active['is_active'] or active['source_mode'] not in ('mock', 'replay'):
                raise ValueError('当前空间风险版本不属于模拟范围，停止配置')
            return {'version_id': row['active_version_id'], 'param_status': active['param_status'], 'action': 'PRESERVED'}
        now = int(time.time() * 1000)
        usable = [v for v in versions if v['status_code'] == 'PUBLISHED' and v['source_mode'] in ('mock','replay')
                  and v['valid_from'] <= now and (not v.get('valid_to') or v['valid_to'] > now)]
        if len(usable) != 1:
            raise ValueError('可用的已发布模拟空间风险版本不唯一，请指定版本；不生成或修改参数')
        v = usable[0]
        self.write('启用既有模拟空间风险版本，保留 ' + v['param_status'], 'POST', '/rule-sets/SPACE-RISK-DEMO/activate',
                   {'rule_set_version_id': v['rule_set_version_id'], 'expected_version': row['version'],
                    'note': MARKER + ' 用户授权模拟验收；不改变参数或确认状态'})
        checked = self.get('/rule-set-versions/' + v['rule_set_version_id'])
        if not checked['is_active'] or checked['param_status'] != v['param_status']:
            raise ValueError('空间规则激活回读不一致')
        return {'version_id': v['rule_set_version_id'], 'param_status': v['param_status'], 'action': 'ACTIVATED_FOR_SIMULATION'}

    def run(self, passwords):
        me = self.get('/auth/me')
        if me.get('role_code') != 'ROLE-ADMIN' or me.get('scope_mode') != 'ALL':
            raise ValueError('准备账号和全局模拟通道需要现有超级管理员；不自动提权')
        # Fail before creating data when the opt-in endpoint is unavailable.
        self.get(PREFIX + '/notification-settings')
        org = unique(self.get('/organizations'), lambda r: r['name'] == ORG_NAME and r['enabled'], ORG_NAME, True)
        district = unique(self.get('/districts'), lambda r: r['name'] == DISTRICT_NAME and r['enabled'], DISTRICT_NAME, True)
        org_id, district_id = org['org_id'], district['district_id']
        self.result['scope'] = {'org_id': org_id, 'org_name': ORG_NAME, 'district_id': district_id, 'district_name': DISTRICT_NAME}
        options = self.get(PREFIX + '/plan-options')
        unique(options['plan_sources'], lambda r: r['source_id'] == 'local-flight-plan-simulator' and r['source_mode'] == 'mock', '模拟计划来源', True)
        contacts = {kind: self.contact(kind, org_id) for kind in CONTACTS}
        self.result['contacts'] = {kind: {k: r.get(k) for k in ('contact_id','name','roles','verified_at','verification_basis')} for kind, r in contacts.items()}
        bindings = self.items('/plan-source-bindings?source_id=local-flight-plan-simulator')
        binding = unique(bindings, lambda r: r['external_org_code'] == MARKER, '模拟报送单位关联')
        if not binding:
            self.write('绑定模拟上级报送单位', 'POST', '/plan-source-bindings',
                       {'source_id': 'local-flight-plan-simulator', 'external_org_code': MARKER, 'org_id': org_id, 'enabled': True})
            binding = unique(self.items('/plan-source-bindings?source_id=local-flight-plan-simulator'), lambda r: r['external_org_code'] == MARKER, '模拟报送单位关联', True)
        if binding['org_id'] != org_id or not binding['enabled']:
            raise ValueError('模拟报送关联不匹配，不覆盖')
        filing = {'source_id': 'local-flight-plan-simulator', 'source_binding_id': binding['binding_id'],
                  'operator_org_id': org_id, 'pilot_contact_id': contacts['pilot']['contact_id']}
        self.result['filing'] = filing
        plan = self.bootstrap_plan(org_id, district_id, filing)
        self.result['plan'] = plan
        self.result['notification_settings'] = self.notifications(plan['plan_id'], contacts)
        self.result['rules']['space'] = self.rule()
        catalog = self.get('/permissions/catalog')
        action_codes = {a['permission_code'] for m in self.get('/permissions/actions') for a in m['actions']}
        for kind in ROLE_SPECS:
            role = self.setup_role(kind, catalog, action_codes)
            user = self.account(kind, role, org_id, passwords.get(kind))
            self.result['accounts'][kind] = {k: user.get(k) for k in ('user_id','account','name','role_code','status','must_change_password')}
        self.result['warnings'] = ['测试账号沿用系统默认 ALL 数据范围，未声称具备按模拟来源隔离的权限；不授予 disposal:direct、用户或角色管理权限。',
                                   '测试账号首次登录必须在原系统完成改密，本工具不跳过。',
                                   '资料准备不代表通知已送达、审批已完成、设备已执行或恢复核验 PASS。',
                                   '处置策略、自动通知规则和恢复健康输入仍需后续闭环验证。']
        self.result['status'] = 'MATERIALS_PREPARED_PASSWORD_CHANGE_PENDING'
        self.result['prepared_at'] = int(time.time() * 1000)
        save(STATE / 'prepared-materials.json', self.result)
        export_scenes(filing)
        package()
        return self.result


def recording():
    path = OUT / 'audio/pilot-warning-simulation.wav'
    with wave.open(str(path), 'rb') as wav:
        frames, rate = wav.getnframes(), wav.getframerate()
        if frames <= 0 or rate <= 0 or not any(wav.readframes(frames)):
            raise ValueError('测试 WAV 无有效声音')
        result = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                  'duration_seconds': round(frames / rate, 3), 'sample_rate': rate,
                  'channels': wav.getnchannels(), 'transcript': TRANSCRIPT,
                  'recording_id': 'SIM-ACC23-VOICE-V1', 'name': '验收模拟飞手通知（中文）'}
    save(OUT / 'audio/recording.json', result)
    return result


def export_scenes(filing=None):
    sources = list((ROOT / 'docs/acceptance/item2-2026-10-06/risk-scenes').glob('*.json'))
    sources += list(Path('D:/沉积岩/demo-ronghe/tools/device-simulator/scenarios/item2').glob('*.json'))
    destination = STATE / 'ready-scenes' if filing else OUT / 'scene-templates'
    if len(sources) != 17:
        raise ValueError('既有第二条及风险场景不完整，应为 17 个；未生成部分包')
    for path in sources:
        scene = json.loads(path.read_text(encoding='utf-8-sig'))
        scene['name'] = '完整模拟资料·' + scene['name']
        if filing:
            scene['fullchain']['filing'] = dict(filing)
        save(destination / path.name, scene)
    return len(sources)


def export_notifications(audio):
    source = Path('D:/沉积岩/demo-ronghe/tools/device-simulator/scenarios/item2/notification-configs')
    for path in source.glob('*.json'):
        config = json.loads(path.read_text(encoding='utf-8'))
        config['play_seconds'] = max(config['play_seconds'], math.ceil(audio['duration_seconds']))
        save(OUT / 'notification-configs' / path.name, config)
    normal = json.loads((OUT / 'notification-configs/normal.json').read_text(encoding='utf-8'))
    normal['outcomes'].update(RISK_NOTICE='success', PLAN_FEEDBACK='success')
    save(OUT / 'notification-configs/normal.json', normal)
    for mode, filename in [('failed', 'risk-failed.json'), ('no_receipt', 'risk-no-receipt.json')]:
        value = copy.deepcopy(normal)
        value.update(mode='abnormal', outcomes={'RISK_NOTICE': mode})
        save(OUT / 'notification-configs' / filename, value)


def export_definition(audio):
    catalog = []
    for path in sorted((OUT / 'scene-templates').glob('*.json')):
        scene = json.loads(path.read_text(encoding='utf-8'))
        catalog.append({'file': path.name, 'name': scene['name'],
                        'devices': [{'id': d['id'], 'kind': d['kind']} for s in scene['sites'] for d in s['devices']],
                        'plans': [{'id': p['id'], 'time_mode': p.get('timeMode'), 'altitude_datum': p.get('altitudeDatum')}
                                  for p in scene['plans']],
                        'observation_transports': sorted({t.get('transport', 'mqtt') for t in scene['targets']})})
    save(OUT / 'material-definition.json', {'marker': MARKER, 'status': 'DEFINITION_NOT_PROOF_OF_APPLIED_DATA',
         'org_name': ORG_NAME, 'district_name': DISTRICT_NAME,
         'plan_source_id': 'local-flight-plan-simulator', 'plan_source_mode': 'mock',
         'contacts': {k: {'name': name, 'roles': roles, 'simulated_phone': phone,
                        'verification_basis': '仅模拟上级资料核验字段，不代表真实核验'} for k,(name,roles,phone) in CONTACTS.items()},
         'accounts': ROLE_SPECS, 'new_account_scope_mode': 'ALL', 'first_login_password_change': True,
         'default_read_modules': sorted(READ_MODULES), 'direct_disposal_granted': False,
         'notification_purposes': ['RISK_NOTICE','ADVISORY_SMS','ADVISORY_VOICE','PLAN_FEEDBACK','UAV_PUNISHMENT'],
         'sms_observation_seconds': 3, 'post_voice_playback_observation_seconds': 10,
         'voice': audio, 'scenarios': catalog,
         'remaining_runtime_gates': ['首次改密及异人审批','自动通知规则和处置策略资格','独立通知接收器在线',
                                     '运维需独立QA健康输入及原恢复核验；工参在线不是 GOOD']})


def package():
    STATE.mkdir(parents=True, exist_ok=True)
    archive = STATE / '完整模拟资料包.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(OUT.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix in {'.json','.md','.py','.wav'}:
                target.write(path, str(path.relative_to(OUT)))
        prepared = STATE / 'prepared-materials.json'
        if prepared.exists():
            target.write(prepared, prepared.name)
            for path in sorted((STATE / 'ready-scenes').glob('*.json')):
                target.write(path, 'ready-scenes/' + path.name)
        for path in sorted((STATE / 'airspace-messages').glob('*.json')):
            target.write(path, 'airspace-messages/' + path.name)
    return archive


def serve(port):
    csrf = secrets.token_urlsafe(32)
    state = {'phase': 'READY', 'messages': [], 'error': None}
    lock = threading.Lock()
    origin = f'http://127.0.0.1:{port}'

    def work(data):
        api = Platform()
        try:
            preparation = Preparation(api, progress=lambda text: state['messages'].append(text))
            api.login(data['account'], data['password'])
            data['password'] = ''
            result = preparation.run({'applicant': data['applicant_password'], 'approver': data['approver_password']})
            state['phase'] = 'COMPLETE'
            state['summary'] = result
            save(STATE / 'preparation-status.json', {'phase': 'COMPLETE', 'prepared_at': result['prepared_at']})
        except Exception as error:
            state['phase'] = 'PARTIAL'
            state['error'] = str(error)
            save(STATE / 'preparation-status.json', {'phase': 'PARTIAL', 'error': str(error), 'at': int(time.time()*1000)})
        finally:
            data.clear()
            api.close()
            lock.release()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, value, status=200, html=False):
            data = value.encode() if html else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'text/html; charset=utf-8' if html else 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(data)

        def trusted(self):
            return self.headers.get('Host') == f'127.0.0.1:{port}' and self.client_address[0] == '127.0.0.1'

        def do_GET(self):
            if not self.trusted():
                return self.reply({'error': '仅限本机'}, 403)
            if self.path == '/status':
                return self.reply(state)
            if self.path != '/':
                return self.reply({'error': 'not found'}, 404)
            page = (ROOT / 'scripts/simulation_materials_setup.html').read_text(encoding='utf-8').replace('__CSRF__', csrf)
            self.reply(page, html=True)

        def do_POST(self):
            if not self.trusted() or self.headers.get('Origin') != origin or self.headers.get('X-Setup-CSRF') != csrf:
                return self.reply({'error': '请求来源校验失败'}, 403)
            if self.path != '/prepare':
                return self.reply({'error': 'not found'}, 404)
            try:
                length = int(self.headers.get('Content-Length', '0'))
            except ValueError:
                return self.reply({'error': '请求大小无效'}, 400)
            if length < 1 or length > 4096:
                return self.reply({'error': '请求大小无效'}, 400)
            try:
                data = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeError):
                return self.reply({'error': '请求格式无效'}, 400)
            required = ('account','password','applicant_password','approver_password')
            if not isinstance(data, dict) or data.get('confirmed') is not True or any(not isinstance(data.get(k), str) or not data[k] for k in required):
                return self.reply({'error': '请完整填写并确认执行范围'}, 400)
            if not lock.acquire(blocking=False):
                return self.reply({'error': '已有准备任务正在运行'}, 409)
            state.update(phase='RUNNING', messages=[], error=None)
            threading.Thread(target=work, args=(data,), daemon=True).start()
            self.reply({'phase': 'RUNNING'}, 202)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
    print('Local preparation page: ' + origin, flush=True)
    server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serve', action='store_true')
    parser.add_argument('--port', type=int, default=8767)
    args = parser.parse_args()
    audio = recording()
    count = export_scenes()
    export_notifications(audio)
    export_definition(audio)
    package()
    if args.serve:
        serve(args.port)
    else:
        status = 'FILES_READY_EXISTING_PREPARATION_RECORD' if (STATE / 'prepared-materials.json').exists() else 'FILES_READY_NOT_APPLIED'
        print(json.dumps({'templates': count, 'audio': audio, 'status': status}, ensure_ascii=False))
