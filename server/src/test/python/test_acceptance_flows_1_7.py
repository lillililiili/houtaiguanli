"""Regression checks for the document-driven flow scene generator."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location(
    "acceptance_flows", ROOT / "scripts" / "prepare_acceptance_flows_1_7.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class AcceptanceFlowsTest(unittest.TestCase):
    def test_generated_bundles_have_distinct_devices_but_preserve_all_references(self):
        bundles = [MODULE.build_scenes() for _ in range(2)]
        identities = []
        for scenes in bundles:
            ids = set()
            shared = []
            for scene in scenes.values():
                devices = {device['id']: device for site in scene['sites'] for device in site['devices']}
                ids.update(devices)
                shared.append(next(identity for identity, device in devices.items() if device['kind'] == 'eo'))
                for item in scene['targets'] + scene['risks']:
                    for field in ('deviceId', 'secondaryDeviceId'):
                        if item.get(field):
                            self.assertIn(item[field], devices)
                MODULE.validate_scene(scene)
            self.assertEqual(1, len(set(shared)), 'one generated bundle must reuse the same EO device')
            identities.append(ids)
        self.assertFalse(identities[0] & identities[1], 'a new bundle must not revive retired logical devices')

    def test_validation_uses_device_kind_instead_of_a_fixed_fixture_id(self):
        scene = MODULE.build_scenes()['flow-1-auto-disposal.json']
        target = scene['targets'][0]
        target['secondaryDeviceId'] = target['deviceId']
        with self.assertRaisesRegex(ValueError, '5G-A'):
            MODULE.validate_scene(scene)

    def test_identity_and_datum_use_supported_transport(self):
        for scene in MODULE.build_scenes().values():
            for target in scene['targets']:
                if target.get('uavSn') or target.get('altitudeDatum'):
                    self.assertEqual('normalized', target['transport'])
                if target.get('uavSn'):
                    self.assertEqual('normalized', target['secondaryTransport'])

    def test_deviation_exceeds_corridor_but_still_matches_plan(self):
        scene = MODULE.build_scenes()['flow-5-manual-disposal.json']
        route, target = scene['plans'][0], scene['targets'][0]
        offset = MODULE._distance_units_to_meters(target['path'][-1], route['points'][-1])
        self.assertGreater(offset, route['width'] / 2 + 20)
        self.assertLess(offset, route['width'] / 2 + 100)

    def test_departure_can_cross_boundary_within_sms_observation(self):
        scene = MODULE.build_scenes()['flow-2-after-sms-departure.json']
        target = scene['targets'][0]
        boundary = scene['zones'][0]['points'][0][0]
        exit_distance = MODULE._distance_units_to_meters(target['path'][-1], [boundary, target['path'][-1][1]])
        self.assertLess(exit_distance / target['departureSpeed'], 1)
        route = scene['plans'][0]['points']
        for x, y in target['departurePath']:
            self.assertGreaterEqual(x, min(point[0] for point in route))
            self.assertLessEqual(x, max(point[0] for point in route))
            self.assertEqual(route[0][1], y)

    def test_changed_plan_geometry_uses_a_distinct_simulated_aircraft(self):
        for plan_id, x in [('route-alpha', 310), ('route-beta', 690)]:
            plan = MODULE._plan(plan_id, '模拟任务', [[x, 300], [x + 20, 300]])
            target = MODULE._uav('uav-' + plan_id, '模拟无人机', [[x, 300]], plan_id=plan_id)
            def scene(current_plan):
                return MODULE._scene(1, '场景', sites=[], targets=[target], plans=[current_plan],
                    zones=[], risks=[], filing={})['targets'][0]['uavSn']
            serial = scene(plan)
            self.assertEqual(serial, scene(dict(plan, name='仅修改显示名称')))
            self.assertNotEqual(serial, scene(dict(plan, points=[[x, 320], [x + 20, 320]])))
            self.assertEqual(serial, scene(plan))

    def test_builds_all_seven_document_flows(self):
        scenes = MODULE.build_scenes()
        self.assertEqual(7, len(scenes))
        self.assertEqual(set(range(1, 8)), {
            scene["fullchain"]["acceptanceFlow"] for scene in scenes.values()
        })
        for scene in scenes.values():
            MODULE.validate_scene(scene)
        self.assertNotIn("pilotPoint", scenes["flow-3-no-filing-direct-disposal.json"]["targets"][0])

    def test_filing_is_optional_and_only_contains_ids(self):
        filing = {
            "source_id": "local-flight-plan-simulator",
            "source_binding_id": "binding-1",
            "operator_org_id": "org-1",
            "pilot_contact_id": "contact-1",
        }
        scenes = MODULE.build_scenes(filing)
        self.assertEqual(filing, scenes["flow-1-auto-disposal.json"]["fullchain"]["filing"])
        self.assertEqual({}, MODULE.build_scenes()["flow-1-auto-disposal.json"]["fullchain"]["filing"])

    def test_flow_7_preserves_bird_count_and_distance(self):
        scene = MODULE.build_scenes()["flow-7-bird-flock.json"]
        bird = scene["targets"][0]
        self.assertEqual(25, bird["count"])
        self.assertEqual("bird", scene["risks"][0]["type"])
        self.assertLess(
            min(MODULE._distance_units_to_meters(point, scene["plans"][0]["points"][0]) for point in bird["path"]),
            300,
        )

    def test_bundle_write_is_non_destructive_without_force(self):
        scenes = MODULE.build_scenes()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "scenes"
            MODULE.write_bundle(output, scenes)
            with self.assertRaises(FileExistsError):
                MODULE.write_bundle(output, scenes)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(7, len(manifest["flows"]))


if __name__ == "__main__":
    unittest.main()
