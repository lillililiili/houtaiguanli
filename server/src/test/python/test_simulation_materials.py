"""Regression tests for the offline acceptance preparation tool, with no live writes."""
import copy
import importlib.util
import json
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('materials', ROOT / 'scripts/prepare_simulation_materials.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class FakeApi:
    def __init__(self, responses=None, failure=None):
        self.responses, self.failure, self.calls = responses or {}, failure, []

    def call(self, method, path, body=None, key=None):
        self.calls.append((method, path, copy.deepcopy(body), key))
        if self.failure:
            raise self.failure
        response = self.responses[(method, path)]
        return copy.deepcopy(response)


class MaterialsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def prep(self, api):
        return m.Preparation(api, self.directory)

    def test_nested_passwords_sessions_and_tokens_are_not_journaled(self):
        api = FakeApi({('POST', '/users'): {'user_id': 'test-user'}})
        p = self.prep(api)
        p.write('create', 'POST', '/users', {'name': '模拟', 'temporary_password': 'TopSecret!1',
                'metadata': {'session_id': 'session-secret', 'token': 'token-secret'},
                'items': [{'Authorization': 'Bearer secret', 'ordinary': 2}]})
        journal = p.file.read_text(encoding='utf-8')
        self.assertNotIn('TopSecret', journal)
        self.assertNotIn('secret', journal)
        self.assertIn('ordinary', journal)
        self.assertEqual('test-user', next(iter(p.journal['requests'].values()))['result_refs']['user_id'])

    def test_timeout_blocks_blind_retry_even_after_timestamp_changes(self):
        api = FakeApi(failure=TimeoutError('unknown response'))
        p = self.prep(api)
        with self.assertRaises(TimeoutError):
            p.write('contact', 'POST', '/contacts', {'verified_at': 10})
        with self.assertRaisesRegex(ValueError, '停止重复提交'):
            self.prep(api).write('contact', 'POST', '/contacts', {'verified_at': 11})
        self.assertEqual(1, len(api.calls))

    def test_definitive_rejection_keeps_same_key_for_same_payload(self):
        api = FakeApi(failure=m.ApiRejected('validation rejected'))
        p = self.prep(api)
        for _ in range(2):
            with self.assertRaises(m.ApiRejected):
                p.write('x', 'POST', '/contacts', {'name': 'x'})
        self.assertEqual(api.calls[0][3], api.calls[1][3])
        self.assertEqual('REJECTED', next(iter(p.journal['requests'].values()))['state'])

    def test_existing_contact_is_read_back_without_write_or_new_verification(self):
        for org_id, contact_id in [('org-a', 'contact-a'), ('org-b', 'contact-b')]:
            with self.subTest(org_id=org_id):
                name, roles, phone = m.CONTACTS['pilot']
                row = {'contact_id': contact_id, 'org_id': org_id, 'name': name, 'roles': roles,
                       'phone': phone, 'enabled': True, 'verified_at': 12345, 'verification_basis': m.MARKER + '模拟'}
                api = FakeApi({('GET', '/contacts?org_id=' + org_id + '&page=1&size=100'): {'items': [row], 'total': 1}})
                checked = self.prep(api).contact('pilot', org_id)
                self.assertEqual(12345, checked['verified_at'])
                self.assertTrue(all(call[0] == 'GET' for call in api.calls))

    def test_same_name_real_contact_cannot_be_taken_over(self):
        name, roles, phone = m.CONTACTS['pilot']
        row = {'org_id': 'org-x', 'name': name, 'roles': roles, 'phone': phone,
               'enabled': True, 'verified_at': 12345, 'verification_basis': 'external real verification'}
        api = FakeApi({('GET', '/contacts?org_id=org-x&page=1&size=100'): {'items': [row], 'total': 1}})
        with self.assertRaisesRegex(ValueError, '不自动接管'):
            self.prep(api).contact('pilot', 'org-x')
        self.assertTrue(all(call[0] == 'GET' for call in api.calls))

    def test_existing_api_channel_is_rejected_before_any_notification_mutation(self):
        api = FakeApi({('GET', m.PREFIX + '/notification-settings'): [
            {'purpose': 'RISK_NOTICE', 'channel_type': 'NONE', 'enabled': False, 'version': 0},
            {'purpose': 'ADVISORY_SMS', 'channel_type': 'API', 'enabled': True, 'version': 3}]})
        with self.assertRaisesRegex(ValueError, '不自动接管'):
            self.prep(api).notifications('plan', {'liaison': {'contact_id': 'a'}, 'penalty': {'contact_id': 'b'}})
        self.assertEqual(1, len(api.calls))

    def test_existing_user_is_not_reset_or_granted_another_role(self):
        spec = m.ROLE_SPECS['applicant']
        row = {'user_id': 'u', 'account': spec['account'], 'name': spec['user_name'],
               'org_id': 'org', 'role_code': 'role-original', 'status': 'ACTIVE'}
        path = '/users?keyword=' + spec['account'] + '&page=1&size=100'
        api = FakeApi({('GET', path): {'items': [row], 'total': 1}})
        with self.assertRaisesRegex(ValueError, '不改权限或重置密码'):
            self.prep(api).account('applicant', {'role_code': 'role-new'}, 'org', 'Unused!123')
        self.assertEqual(1, len(api.calls))

    def test_existing_matching_account_can_be_reused_without_password(self):
        spec = m.ROLE_SPECS['approver']
        row = {'user_id': 'u', 'account': spec['account'], 'name': spec['user_name'],
               'org_id': 'org', 'role_code': 'role', 'status': 'ACTIVE'}
        path = '/users?keyword=' + spec['account'] + '&page=1&size=100'
        api = FakeApi({('GET', path): {'items': [row], 'total': 1}, ('GET', '/users/u'): row})
        self.assertEqual(row, self.prep(api).account('approver', {'role_code': 'role'}, 'org', None))
        self.assertTrue(all(c[0] == 'GET' for c in api.calls))

    def test_role_account_permissions_are_separated(self):
        applicant, approver = [set(m.ROLE_SPECS[k]['actions']) for k in ('applicant', 'approver')]
        self.assertIn('disposal:request', applicant)
        self.assertIn('disposal:execute', applicant)
        self.assertNotIn('disposal:approve', applicant)
        self.assertIn('disposal:approve', approver)
        self.assertNotIn('disposal:request', approver)
        self.assertNotIn('disposal:execute', approver)
        self.assertNotIn('disposal:direct', applicant | approver)
        self.assertFalse({'users', 'roles', 'countermeasure'} & m.READ_MODULES)

    def test_ambiguous_catalog_records_stop_instead_of_using_first(self):
        with self.assertRaises(ValueError):
            m.unique([{'id': 'a'}, {'id': 'b'}], lambda row: True, '重复')

    def test_redirect_cannot_forward_authentication(self):
        with self.assertRaisesRegex(ValueError, '不跟随重定向'):
            m.NoRedirect().redirect_request(None, None, None, None, None, 'https://example.invalid')

    def test_invalid_platform_path_does_not_send(self):
        for path in ('//example.invalid', '/../users', 'https://example.invalid'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                m.Platform().call('GET', path)

    def test_existing_role_reuse_is_independent_of_catalog_order(self):
        spec = m.ROLE_SPECS['approver']
        catalog = [{'permission_code': 'roles', 'route_key': 'roles'},
                   {'permission_code': 'flights', 'route_key': 'flights'}]
        matrix = [{'permission_code': 'flights', 'level': 'READ', 'menu_enabled': True},
                  {'permission_code': 'roles', 'level': 'NONE', 'menu_enabled': False}]
        row = {'role_code': 'role-q', 'name': spec['name'], 'description': m.MARKER,
               'builtin': False, 'user_count': 1, 'permissions': matrix,
               'actions': [{'permission_code': p, 'level': 'READ' if p.endswith(':read') else 'OP'} for p in spec['actions']]}
        api = FakeApi({('GET', '/roles'): [row], ('GET', '/roles/role-q'): row})
        self.assertEqual('role-q', self.prep(api).setup_role('approver', catalog, set(spec['actions']))['role_code'])
        self.assertTrue(all(c[0] == 'GET' for c in api.calls))

    def test_applicant_requires_device_operation_but_existing_read_role_is_not_expanded(self):
        spec = m.ROLE_SPECS['applicant']
        catalog = [{'permission_code': 'devices', 'route_key': 'devices'}]
        for level in ('OP', 'READ'):
            row = {'role_code': 'role-applicant', 'name': spec['name'], 'description': m.MARKER,
                   'builtin': False, 'user_count': 1,
                   'permissions': [{'permission_code': 'devices', 'level': level, 'menu_enabled': True}],
                   'actions': [{'permission_code': p, 'level': 'READ' if p.endswith(':read') else 'OP'}
                               for p in spec['actions']]}
            api = FakeApi({('GET', '/roles'): [row], ('GET', '/roles/role-applicant'): row})
            with self.subTest(level=level):
                if level == 'OP':
                    self.assertEqual('role-applicant', self.prep(api).setup_role('applicant', catalog, set(spec['actions']))['role_code'])
                else:
                    with self.assertRaisesRegex(ValueError, '不覆盖'):
                        self.prep(api).setup_role('applicant', catalog, set(spec['actions']))
                self.assertTrue(all(c[0] == 'GET' for c in api.calls))

    def test_future_or_expired_contact_never_gets_silent_extension(self):
        for overrides in ({'verified_at': int(time.time()*1000)+60000}, {'valid_until': 1}):
            name, roles, phone = m.CONTACTS['pilot']
            row = {'org_id': 'org-x', 'name': name, 'roles': roles, 'phone': phone, 'enabled': True,
                   'verified_at': 12345, 'verification_basis': m.MARKER, **overrides}
            api = FakeApi({('GET', '/contacts?org_id=org-x&page=1&size=100'): {'items': [row], 'total': 1}})
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                self.prep(api).contact('pilot', 'org-x')
            self.assertTrue(all(c[0] == 'GET' for c in api.calls))

    def test_airspace_cases_keep_identity_and_increasing_effective_times(self):
        module_spec = importlib.util.spec_from_file_location('airspaces', ROOT/'docs/acceptance/simulation-materials/upstream/build_airspace_messages.py')
        builder = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(builder)
        for suffix, now in [('a', 1800000000000), ('b', 1900000000000)]:
            messages = builder.build('org-'+suffix, 'district-'+suffix, 'batch-'+suffix, now)
            create, repeat, update, stale, conflict, withdraw = messages.values()
            self.assertEqual(create, repeat)
            for key in ('name','airspace_no','owner_org_id','district_id'):
                self.assertEqual(create[key], update[key])
            self.assertLess(create['valid_from'], update['valid_from'])
            self.assertLess(update['valid_from'], withdraw['effective_at'])
            self.assertLess(withdraw['effective_at'], update['valid_to'])
            self.assertEqual([1,2,3], [create['revision'],update['revision'],withdraw['revision']])
            self.assertEqual(create['message_id'], conflict['message_id'])
            self.assertNotEqual(create, conflict)
            self.assertNotEqual(create['message_id'], stale['message_id'])
            self.assertLess(stale['revision'], update['revision'])

    def test_missing_scope_and_invalid_batch_cannot_make_upstream_packet(self):
        module_spec = importlib.util.spec_from_file_location('airspaces', ROOT/'docs/acceptance/simulation-materials/upstream/build_airspace_messages.py')
        builder = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(builder)
        for org, district, batch in [('', 'd', 'b'), ('o', '', 'b'), ('o', 'd', 'b/../../x')]:
            with self.subTest(batch=batch), self.assertRaises(ValueError):
                builder.build(org, district, batch, 1800000000000)

    def test_rehearsal_separates_normalized_identity_without_changing_protocol_or_bindings(self):
        module_spec = importlib.util.spec_from_file_location('rehearsal', ROOT/'docs/acceptance/simulation-materials/build_rehearsal_scene.py')
        builder = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(builder)
        original = {'sites': [{'devices': [{'id': 'stable-device'}]}], 'plans': [{'id': 'plan-x'}],
                    'targets': [{'id': 'uav-a', 'kind': 'uav', 'transport': 'normalized', 'planId': 'plan-x'},
                                {'id': 'uav-b', 'kind': 'uav', 'transport': 'normalized', 'planId': 'plan-x'},
                                {'id': 'radar-uav', 'kind': 'uav', 'transport': 'mqtt'},
                                {'id': 'bird', 'kind': 'bird', 'transport': 'normalized'}]}
        before = copy.deepcopy(original)
        a, b = [builder.build(original, batch) for batch in ('run-a', 'run-b')]
        self.assertEqual(before, original)
        self.assertEqual(original['sites'], a['sites'])
        self.assertEqual(original['plans'], a['plans'])
        self.assertEqual(original['targets'][2:], a['targets'][2:])
        serials = [t['uavSn'] for result in (a, b) for t in result['targets'][:2]]
        self.assertEqual(4, len(set(serials)))
        self.assertEqual('plan-x', a['targets'][0]['planId'])
        self.assertEqual(a, builder.build(original, 'run-a'))
        for bad in ('', '../run', 'a' * 33):
            with self.subTest(batch=bad), self.assertRaises(ValueError):
                builder.build(original, bad)
        with self.assertRaises(ValueError):
            builder.build({'targets': [original['targets'][0], original['targets'][0]]}, 'run-a')


class DepartureTemplateTest(unittest.TestCase):
    def test_departure_leaves_every_origin_airspace_and_keeps_pilot_nearby(self):
        # All shipped zones are rectangles. The platform observes all origin
        # airspaces, so leaving only the inner prohibited area is insufficient.
        def inside(point, zone):
            xs, ys = zip(*zone['points'])
            return min(xs) <= point[0] <= max(xs) and min(ys) <= point[1] <= max(ys)

        folder = ROOT/'docs/acceptance/simulation-materials/scene-templates'
        for name in ('03-after-sms', '04-after-voice'):
            with self.subTest(scene=name):
                scene = json.loads((folder/(name+'.json')).read_text(encoding='utf-8'))
                target = scene['targets'][0]
                origin_zones = [z for z in scene['zones'] if inside(target['path'][0], z)]
                self.assertTrue(any(z['kindCode'] == 'PROHIBITED' for z in origin_zones))
                for destination in target['departurePath']:
                    self.assertFalse(any(inside(destination, z) for z in origin_zones))
                for point in target['path']:
                    # Scene units represent about 10-13 m here. Keep the pilot
                    # within 100 m rather than accidentally testing BVLOS.
                    self.assertLess(sum((a-b)**2 for a,b in zip(point, target['pilotPoint']))**.5 * 14, 100)


if __name__ == '__main__':
    unittest.main()
