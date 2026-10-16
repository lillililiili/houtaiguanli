"""Generate and install the seven acceptance-flow scenes.

The confirmation document describes seven operator flows, while the device
simulator accepts one version-1 scene per run.  This tool turns those flows
into seven importable scene JSON files.  It never writes the business
database, starts a simulator task, enables a dev seed, or invents filing IDs.

Typical use from the repository root::

    python scripts/prepare_acceptance_flows_1_7.py --install \
      --simulator-root E:/rwurenji/_rong/tools/device-simulator

Import one generated JSON in the simulator's "导入场景" action, run it, then
stop it before importing the next flow.  A prepared-materials JSON may be
provided with ``--filing``; otherwise the scenes intentionally keep filing
links empty and the simulator will show the existing notification gate.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import math
import os
import sys
import uuid
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SIMULATOR_ROOTS = (
    Path(r"E:\rwurenji\_rong\tools\device-simulator"),
    Path(r"D:\沉积岩\demo-ronghe\tools\device-simulator"),
)
OUTPUT_NAME = "acceptance-flows-1-7-20261008"
ALLOWED_FILING_KEYS = {
    "source_id",
    "source_binding_id",
    "operator_org_id",
    "pilot_contact_id",
}


def _filing(path: Path | None) -> dict[str, str]:
    """Read only the four non-sensitive filing IDs used by FullChain."""

    if path is None:
        return {}
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(value, dict) and isinstance(value.get("filing"), dict):
        value = value["filing"]
    if not isinstance(value, dict):
        raise ValueError("--filing 必须是对象，或包含 filing 对象的准备结果")
    unknown = set(value) - ALLOWED_FILING_KEYS
    if unknown:
        raise ValueError("--filing 含不支持的字段：" + "、".join(sorted(unknown)))
    result: dict[str, str] = {}
    for key, item in value.items():
        if item in (None, ""):
            continue
        if not isinstance(item, str) or len(item) > 36:
            raise ValueError(f"--filing.{key} 必须是最多 36 个字符的字符串")
        result[key] = item
    if "source_id" in result and result["source_id"] != "local-flight-plan-simulator":
        raise ValueError("--filing.source_id 必须是 local-flight-plan-simulator")
    return result


def _device_site(include_countermeasure: bool = True, weather: bool = False) -> list[dict[str, Any]]:
    devices: list[dict[str, Any]] = [
        {
            "id": "acceptance-radar",
            "name": "流程验收雷达",
            "kind": "radar",
            "health": "正常",
            "heartbeat": "持续上报",
            "interval": 1,
        },
        {
            "id": "acceptance-5ga",
            "name": "流程验收 5G-A",
            "kind": "5ga",
            "health": "正常",
            "heartbeat": "持续上报",
            "interval": 1,
        },
        {
            "id": "acceptance-eo",
            "name": "流程验收光电",
            "kind": "eo",
            "health": "正常",
            "heartbeat": "持续上报",
            "interval": 1,
        },
    ]
    if include_countermeasure:
        devices.append(
            {
                "id": "acceptance-ifr",
                "name": "流程验收干扰设备",
                "kind": "ifr",
                "health": "正常",
                "heartbeat": "持续上报",
                "interval": 1,
                "protocolB": {"enabled": True, "response": "success"},
            }
        )
    if weather:
        devices.append(
            {
                "id": "acceptance-weather",
                "name": "流程验收气象设备",
                "kind": "weather",
                "health": "正常",
                "heartbeat": "持续上报",
                "interval": 5,
            }
        )
    return [{"id": "acceptance-site", "name": "流程 1-7 模拟设备组", "x": 450, "y": 300, "devices": devices}]


def _plan(plan_id: str, name: str, points: list[list[float]], width: float = 100) -> dict[str, Any]:
    return {
        "id": plan_id,
        "name": name,
        "points": points,
        "min": 0,
        "max": 150,
        "width": width,
        "start": "00:00",
        "end": "23:59",
        "timeMode": "current",
        "altitudeDatum": "AMSL",
    }


def _prohibited_zone(zone_id: str = "acceptance-prohibited") -> dict[str, Any]:
    return {
        "id": zone_id,
        "name": "流程验收禁飞区",
        "kindCode": "PROHIBITED",
        "points": [[500, 290], [535, 290], [535, 310], [500, 310]],
        "min": 0,
        "max": 150,
        "altitudeDatum": "AMSL",
        "start": "00:00",
        "end": "23:59",
    }


def _uav(
    target_id: str,
    name: str,
    path: list[list[float]],
    *,
    plan_id: str = "",
    behavior: str = "none",
    departure_path: list[list[float]] | None = None,
    include_pilot_point: bool = True,
) -> dict[str, Any]:
    target: dict[str, Any] = {
        "id": target_id,
        "name": name,
        "kind": "uav",
        "deviceId": "acceptance-radar",
        "secondaryDeviceId": "acceptance-5ga",
        "path": path,
        "height": 100,
        "heightAgl": 60,
        "altitudeDatum": "AMSL",
        "speed": 3,
        "probability": 0.99,
        "count": 1,
        "motionMode": "once",
        # Protocol A radar/5G-A cannot assert SN or an AMSL datum. Use the
        # existing explicitly simulated observation contract for these facts.
        "transport": "normalized",
        "secondaryTransport": "normalized",
        "uavSn": f"SIM-ACC-20261008-{target_id.upper()}",
        "planId": plan_id,
        "notificationBehavior": behavior,
    }
    if include_pilot_point:
        target["pilotPoint"] = [path[0][0] - 2, path[0][1]]
    if departure_path is not None:
        target["departurePath"] = departure_path
        target["departureSpeed"] = 15
    return target


def _scene(
    flow: int,
    name: str,
    *,
    sites: list[dict[str, Any]],
    targets: list[dict[str, Any]],
    plans: list[dict[str, Any]],
    zones: list[dict[str, Any]],
    risks: list[dict[str, Any]],
    filing: dict[str, str],
    categories: list[str] | None = None,
    duration: int = 5,
    observation_sources: int | None = None,
    weather: dict[str, Any] | None = None,
    operator_setup: dict[str, Any] | None = None,
    expected: list[str] | None = None,
) -> dict[str, Any]:
    # A changed acceptance route represents a new simulated aircraft/filing pair.
    # Reusing the old serial with overlapping historical plans makes an otherwise
    # valid current plan ambiguous. Keep unchanged scenarios repeatable and retain
    # every previous plan; names and input order do not define business identity.
    targets = copy.deepcopy(targets)
    plans_by_id = {plan['id']: plan for plan in plans}
    for target in targets:
        plan = plans_by_id.get(target.get('planId'))
        if target.get('uavSn') and plan:
            facts = {key: plan.get(key) for key in (
                'points', 'width', 'min', 'max', 'altitudeDatum', 'start', 'end', 'timeMode')}
            suffix = hashlib.sha256(json.dumps(facts, sort_keys=True).encode()).hexdigest()[:12]
            target['uavSn'] += '-' + suffix.upper()
    fullchain: dict[str, Any] = {
        "enabled": True,
        "categories": categories or [],
        "filing": copy.deepcopy(filing),
        "airspaceLifecycle": False,
        "acceptanceFlow": flow,
        "operatorSetup": operator_setup or {},
        "expectedChecks": expected or [],
    }
    if observation_sources is not None:
        fullchain["observationSourceCount"] = observation_sources
    if weather is not None:
        fullchain["weather"] = weather
    return {
        "version": 1,
        "name": name,
        "duration": duration,
        "sites": sites,
        "targets": targets,
        "plans": plans,
        "zones": zones,
        "risks": risks,
        "fullchain": fullchain,
    }


def build_scenes(filing: dict[str, str] | None = None) -> dict[str, dict[str, Any]]:
    """Build one importable scene for each flow in the confirmation document."""

    filing = dict(filing or {})
    plan_zone = _prohibited_zone()
    planned_route = _plan("flow1-plan", "流程 1-2 报备航线", [[490, 300], [520, 300]])
    flow1_target = _uav(
        "flow1-uav",
        "流程 1 违规无人机",
        [[490, 300], [503, 300]],
        plan_id=planned_route["id"],
        behavior="hold",
    )
    flow2_target = _uav(
        "flow2-uav",
        "流程 2 短信后飞离无人机",
        [[490, 300], [500.5, 300]],
        plan_id=planned_route["id"],
        behavior="after_sms",
        departure_path=[[495, 300], [490, 300]],
    )
    # 给操作员留出先核对计划、视频和位置，再观察入区告警的时间。
    flow1_target["dwellSeconds"] = [60, 0]
    flow2_target["dwellSeconds"] = [60, 0]
    flow3_target = _uav(
        "flow3-uav",
        "流程 3 无报备无人机",
        [[510, 300], [520, 300]],
        include_pilot_point=False,
    )
    flow4_target = _uav(
        "flow4-uav",
        "流程 4 待人工核实无人机",
        [[510, 300], [520, 300]],
    )
    manual_route = _plan("flow5-plan", "流程 5 低中风险报备航线", [[470, 340], [500, 340]], width=120)
    flow5_target = _uav(
        "flow5-uav",
        "流程 5 偏航无人机",
        [[470, 340], [480, 347], [500, 347]],
        plan_id=manual_route["id"],
        behavior="hold",
    )

    scenes = {
        "flow-1-auto-disposal.json": _scene(
            1,
            "流程 1 违规无人机通知后人工发起反制",
            sites=_device_site(include_countermeasure=False),
            targets=[flow1_target],
            plans=[planned_route],
            zones=[plan_zone],
            risks=[],
            filing=filing,
            expected=[
                "计划无人机进入禁飞区并持续停留",
                "两路规范化模拟观测同时上报，设备状态继续通过 MQTT 上报",
                "飞手通知后保持在禁飞区内；规则通过只评估资格，仍由有权限的操作员人工发起反制",
                "反制使用实时收发启动的四通道 TCP 模拟设备，按启动、全关回执验证停止，不用协议 B 启动成功替代停机",
            ],
        ),
        "flow-2-after-sms-departure.json": _scene(
            2,
            "流程 2 收到短信后飞离",
            sites=_device_site(include_countermeasure=False),
            targets=[flow2_target],
            plans=[planned_route],
            zones=[plan_zone],
            risks=[],
            filing=filing,
            expected=[
                "与流程 1 相同的双源违规输入",
                "短信送达后沿 departurePath 离开原禁飞区",
                "离开事实由平台当前观测判定，不由脚本写入飞离结果",
            ],
        ),
        "flow-3-no-filing-direct-disposal.json": _scene(
            3,
            "流程 3 没有报备计划的无人机",
            sites=_device_site(include_countermeasure=False),
            targets=[flow3_target],
            plans=[],
            zones=[plan_zone],
            risks=[],
            filing={},
            expected=[
                "目标没有 planId，仍由两路规范化模拟观测上报",
                "目标位于禁飞区内，飞手通知资格由平台按无计划事实阻断",
            ],
        ),
        "flow-4-operator-false-alarm.json": _scene(
            4,
            "流程 4 值班员核实为误报",
            sites=_device_site(include_countermeasure=False),
            targets=[flow4_target],
            plans=[],
            zones=[plan_zone],
            risks=[],
            filing={},
            operator_setup={"autoVerificationEnabled": False, "restoreAfterRun": True},
            expected=[
                "先在规则管理停用自动核实，再启动本场景",
                "告警保留为待人工核实，由值班员在业务页面核实为误报",
                "脚本不写误报、通知、反制或移送结果",
            ],
        ),
        "flow-5-manual-disposal.json": _scene(
            5,
            "流程 5 中低风险人工申请反制",
            sites=_device_site(include_countermeasure=False),
            targets=[flow5_target],
            plans=[manual_route],
            zones=[],
            risks=[
                {
                    "id": "flow5-deviation",
                    "name": "流程 5 偏航输入",
                    "type": "deviation",
                    "enabled": True,
                    "targetId": flow5_target["id"],
                    "planId": manual_route["id"],
                    "deviceId": "acceptance-radar",
                }
            ],
            filing=filing,
            expected=[
                "有计划目标偏离航线，提供中低风险人工处置输入",
                "目标通知后继续停留，供值班员申请和另一账号审批",
                "实际风险等级、授权、审批和设备回执由平台页面流程决定",
            ],
        ),
        "flow-6-weather-risk.json": _scene(
            6,
            "流程 6 雷雨天气风险",
            sites=_device_site(include_countermeasure=False, weather=True),
            targets=[],
            plans=[_plan("flow6-weather-plan", "流程 6 天气风险关联计划", [[470, 360], [500, 360]])],
            zones=[],
            risks=[],
            filing=filing,
            categories=["weather"],
            duration=2,
            weather={
                "area_name": "东营区",
                "summary": "雷雨",
                "wind_speed_ms": 12,
                "gust_ms": 20,
                "wind_from_degrees": 90,
                "humidity_percent": 88,
                "visibility_m": 5000,
            },
            expected=[
                "天气预报区域和计划时段由 FullChain 同批提交",
                "预报只提供雷雨事实，风险仍由平台规则生成待核验记录",
                "核验与通知上级在业务页面完成，脚本不写核验或通知结果",
            ],
        ),
        "flow-7-bird-flock.json": _scene(
            7,
            "流程 7 航线附近 25 只鸟群",
            sites=[
                {
                    "id": "acceptance-risk-site",
                    "name": "流程 7 鸟情模拟设备组",
                    "x": 450,
                    "y": 300,
                    "devices": [
                        {
                            "id": "acceptance-risk-radar",
                            "name": "流程验收鸟情雷达",
                            "kind": "radar",
                            "health": "正常",
                            "heartbeat": "持续上报",
                            "interval": 1,
                        },
                        {
                            "id": "acceptance-eo",
                            "name": "流程验收光电",
                            "kind": "eo",
                            "health": "正常",
                            "heartbeat": "持续上报",
                            "interval": 1,
                        },
                    ],
                }
            ],
            targets=[
                {
                    "id": "flow7-birds",
                    "name": "流程 7 航线附近鸟群",
                    "kind": "bird",
                    "deviceId": "acceptance-risk-radar",
                    "path": [[470, 365], [500, 365]],
                    "height": 100,
                    "heightAgl": 60,
                    "altitudeDatum": "AMSL",
                    "speed": 2,
                    "probability": 0.99,
                    "count": 25,
                    "motionMode": "pingpong",
                    "transport": "normalized",
                    "planId": "flow7-bird-plan",
                    "notificationBehavior": "none",
                }
            ],
            plans=[_plan("flow7-bird-plan", "流程 7 鸟群关联计划", [[470, 360], [500, 360]])],
            zones=[],
            risks=[
                {
                    "id": "flow7-bird-risk",
                    "name": "流程 7 航线附近鸟群",
                    "type": "bird",
                    "enabled": True,
                    "targetId": "flow7-birds",
                    "planId": "flow7-bird-plan",
                    "deviceId": "acceptance-risk-radar",
                }
            ],
            filing=filing,
            categories=["bird"],
            duration=2,
            observation_sources=3,
            expected=[
                "25 只鸟群在航线约 5 个模拟坐标单位内持续飞过",
                "目标不携带无人机 SN，使用三路标准化模拟观测源",
                "核验与通知上级在全部风险事件页面完成",
            ],
        ),
    }
    # Generating a bundle creates new logical devices. Retired platform identities
    # remain immutable; retries of an imported scene keep these saved IDs, and all
    # seven scenes in this bundle share the same device mapping.
    generation = uuid.uuid4().hex
    identities = {device['id']: 'acc-' + generation + '-' + device['id']
                  for scene in scenes.values() for site in scene['sites'] for device in site['devices']}
    for scene in scenes.values():
        scene['fullchain']['deviceGeneration'] = generation
        for site in scene['sites']:
            for device in site['devices']:
                device['id'] = identities[device['id']]
        for item in scene['targets'] + scene['risks']:
            for field in ('deviceId', 'secondaryDeviceId'):
                if item.get(field):
                    item[field] = identities[item[field]]
    return scenes


def _simulator_engine(simulator_root: Path) -> Any | None:
    engine_path = simulator_root / "engine.py"
    if not engine_path.exists():
        return None
    protocol_path = simulator_root / "protocol_a.py"
    if not protocol_path.exists():
        return None
    old_path = list(sys.path)
    try:
        sys.path.insert(0, str(simulator_root))
        spec = importlib.util.spec_from_file_location("acceptance_simulator_engine", engine_path)
        if spec is None or spec.loader is None:
            return None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path[:] = old_path


def _distance_units_to_meters(a: list[float], b: list[float]) -> float:
    # engine.coordinates uses approximately 13.4 metres per simulator unit.
    return math.hypot(a[0] - b[0], a[1] - b[1]) * 14.0


def validate_scene(scene: dict[str, Any], *, compile_scene: Callable[[dict[str, Any]], Any] | None = None) -> None:
    """Validate the importer contract and the flow-specific invariants."""

    if scene.get("version") != 1:
        raise ValueError("场景 version 必须为 1")
    for key in ("sites", "targets", "plans", "zones", "risks"):
        if not isinstance(scene.get(key), list):
            raise ValueError(f"场景 {key} 必须是数组")
        ids = [item.get("id") for item in scene[key]]
        if any(not isinstance(item_id, str) or not item_id for item_id in ids) or len(set(ids)) != len(ids):
            raise ValueError(f"场景 {key} 的 id 必须存在且唯一")
    if not isinstance(scene.get("duration"), (int, float)) or not 0 <= scene["duration"] <= 20:
        raise ValueError("场景 duration 必须在 0 到 20 分钟")
    fullchain = scene.get("fullchain") or {}
    flow = fullchain.get("acceptanceFlow")
    if flow not in range(1, 8):
        raise ValueError("场景缺少流程 1-7 标识")
    filing = fullchain.get("filing") or {}
    if set(filing) - ALLOWED_FILING_KEYS:
        raise ValueError("场景 filing 含未知字段")
    if flow in (1, 2, 5):
        if len(scene["targets"]) != 1 or not scene["targets"][0].get("planId"):
            raise ValueError(f"流程 {flow} 必须有一架关联计划的无人机")
        target = scene["targets"][0]
        devices = {device['id']: device for site in scene['sites'] for device in site['devices']}
        if devices.get(target.get("secondaryDeviceId"), {}).get('kind') != '5ga':
            raise ValueError(f"流程 {flow} 必须保留 5G-A 辅助来源")
    if flow in (1, 2, 3, 4):
        if not any(zone.get("kindCode") == "PROHIBITED" for zone in scene["zones"]):
            raise ValueError(f"流程 {flow} 必须包含禁飞区")
    if flow == 1 and scene["targets"][0].get("notificationBehavior") != "hold":
        raise ValueError("流程 1 必须让目标在通知后继续停留")
    if flow == 2:
        target = scene["targets"][0]
        if target.get("notificationBehavior") != "after_sms" or len(target.get("departurePath", [])) < 1:
            raise ValueError("流程 2 必须配置短信后撤离")
    if flow == 3 and scene["targets"][0].get("planId"):
        raise ValueError("流程 3 不能携带报备计划")
    if flow == 3 and scene["targets"][0].get("pilotPoint"):
        raise ValueError("流程 3 不能携带飞手位置")
    if flow == 4 and fullchain.get("operatorSetup", {}).get("autoVerificationEnabled") is not False:
        raise ValueError("流程 4 必须标记先停用自动核实")
    if flow == 5:
        if not any(risk.get("type") == "deviation" for risk in scene["risks"]):
            raise ValueError("流程 5 必须使用偏航输入")
        if scene["targets"][0].get("notificationBehavior") != "hold":
            raise ValueError("流程 5 目标必须保持在场，供人工处置")
    if flow == 6:
        weather = fullchain.get("weather") or {}
        if "weather" not in fullchain.get("categories", []) or "雷雨" not in weather.get("summary", ""):
            raise ValueError("流程 6 必须提交雷雨天气预报事实")
    if flow == 7:
        birds = [target for target in scene["targets"] if target.get("kind") == "bird"]
        if len(birds) != 1 or birds[0].get("count") != 25:
            raise ValueError("流程 7 必须是一群 25 只鸟")
        if "bird" not in fullchain.get("categories", []):
            raise ValueError("流程 7 必须启用 bird 风险类别")
        route = scene["plans"][0]["points"]
        if min(_distance_units_to_meters(point, route[0]) for point in birds[0]["path"]) >= 300:
            raise ValueError("流程 7 鸟群必须在航线 300 米内")
    if compile_scene is not None:
        compile_scene(copy.deepcopy(scene))


def _safe_child(parent: Path, child: Path) -> Path:
    parent = parent.resolve()
    child = child.resolve()
    try:
        child.relative_to(parent)
    except ValueError as error:
        raise ValueError("输出目录必须位于模拟器目录内") from error
    return child


def write_bundle(output_dir: Path, scenes: dict[str, dict[str, Any]], *, force: bool = False) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "version": 1,
        "name": "规则与做法确认书·流程 1-7",
        "source": "规则与做法确认书（含验收方法）-2026-10-08",
        "status": "SCENES_READY_FOR_IMPORT",
        "flows": [
            {"flow": scene["fullchain"]["acceptanceFlow"], "file": name, "name": scene["name"]}
            for name, scene in scenes.items()
        ],
        "usage": "在数据模拟器中每次只导入并运行一个 JSON，完成后停止再导入下一个。",
    }
    files = {**scenes, "manifest.json": manifest}
    for name, value in files.items():
        path = output_dir / name
        if path.exists() and not force:
            raise FileExistsError(f"文件已存在，为避免覆盖请使用 --force：{path}")
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    return output_dir


def _default_simulator_root() -> Path | None:
    configured = os.environ.get("DEVICE_SIMULATOR_ROOT")
    if configured:
        return Path(configured)
    for candidate in DEFAULT_SIMULATOR_ROOTS:
        if candidate.exists():
            return candidate
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator-root", type=Path, help="数据模拟器根目录")
    parser.add_argument("--output", type=Path, help="输出目录；--install 时必须位于模拟器根目录内")
    parser.add_argument("--filing", type=Path, help="准备结果 JSON 或直接 filing JSON，不保存口令和会话")
    parser.add_argument("--install", action="store_true", help="把场景写入模拟器目录的 scenarios 子目录")
    parser.add_argument("--force", action="store_true", help="允许覆盖同名场景文件")
    parser.add_argument("--check-only", action="store_true", help="只生成内存场景并校验，不写文件")
    args = parser.parse_args(argv)

    simulator_root = (args.simulator_root or _default_simulator_root())
    if args.install and simulator_root is None:
        parser.error("--install 需要 --simulator-root，或设置 DEVICE_SIMULATOR_ROOT")
    if simulator_root is not None:
        simulator_root = simulator_root.resolve()
    output = args.output
    if output is None:
        output = (simulator_root / "scenarios" / OUTPUT_NAME) if args.install and simulator_root else ROOT / "server" / "target" / OUTPUT_NAME
    output = output.resolve()
    if args.install and simulator_root is not None:
        output = _safe_child(simulator_root, output)
    if not args.install and output.exists() and not args.force and not args.check_only:
        # Keep the default generated package reproducible without touching a
        # previous operator result.  The explicit --force is required to reuse it.
        raise SystemExit(f"输出目录已存在，请改用 --output 或 --force：{output}")

    filing = _filing(args.filing)
    scenes = build_scenes(filing)
    engine = _simulator_engine(simulator_root) if simulator_root else None
    for scene in scenes.values():
        validate_scene(scene, compile_scene=getattr(engine, "compile_scene", None) if engine else None)
    if args.check_only:
        print(json.dumps({"flows": len(scenes), "validated": True, "filing_fields": sorted(filing)}, ensure_ascii=False))
        return 0
    written = write_bundle(output, scenes, force=args.force)
    print(json.dumps({"flows": len(scenes), "validated": True, "output": str(written), "filing_fields": sorted(filing)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
