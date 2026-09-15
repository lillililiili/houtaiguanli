#!/usr/bin/env python3
"""Keep the local replay devices online by republishing 工参 / HeartBeat like a real device.

Why this exists: 协议 A v8.6 says the platform treats a device as offline when no 工参
arrives for 30 s, and tells devices to report 工参 every 5–10 s. The frozen demo datasets
carry exactly one 工参 per device with a fixed ptTime, and the platform ignores a 工参 whose
ptTime is not newer than the last one (STALE_STATIC). So a one-shot replay puts every device
online for 30 s and then leaves it offline forever, which blocks 协议 B 控制 and 处置执行
(both require ONLINE) and, since the 光电 idle lookup also requires ONLINE, 手点跟踪 too.

This script does what the device itself would do: reissue the same 工参 with a current
timestamp on the cadence the protocol asks for. It only rewrites the time field, so every
other byte of the frozen payload is published unchanged.

Not a substitute for the one-shot publisher: run publish_lingyun_ndjson.py for the actual
scenario data, and run this alongside it to hold the devices online.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DATASETS = REPO_ROOT / "docs" / "直连接入计划"
# 协议 A 工参：阶段 2 的六条覆盖 radar/tdoa/aoa/5ga/dcd/rid，阶段 3 的三条覆盖 dec/ifr/bsc。
DEFAULT_STATIC_FILES = [
    DATASETS / "stage2-lingyun-static-dcd-rid.mqtt.ndjson",
    DATASETS / "stage3-lingyun-control-static.mqtt.ndjson",
]
# 协议 C 心跳模板取自 8.5 数据集；光电的离线判定是 3 秒，比协议 A 紧得多。
DEFAULT_EO_FILE = DATASETS / "stage85-lingyun-demo.mqtt.ndjson"

PT_TIME = re.compile(rb'"ptTime"\s*:\s*\d+')
TIMESTAMP = re.compile(rb'"timestamp"\s*:\s*\d+')
WORK_STATE = re.compile(rb'"workState"\s*:\s*\d+')

if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from publish_lingyun_ndjson import mqtt_client  # noqa: E402


def load(path: Path, keep) -> list[tuple[str, bytes]]:
    rows = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, start=1):
            line = raw.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: 不是合法 JSON：{exc}") from exc
            topic, payload = row.get("topic"), row.get("payload")
            if not isinstance(topic, str) or not isinstance(payload, str):
                raise SystemExit(f"{path}:{line_no}: topic/payload 字段不合法")
            if keep(topic, payload):
                rows.append((topic, payload.encode("utf-8")))
    return rows


def is_static(topic: str, _payload: str) -> bool:
    """协议 A 工参走 bridge/{provider}/device/...，探测数据走 device_data，别把探测数据也保活。"""
    parts = topic.split("/")
    return len(parts) == 5 and parts[0] == "bridge" and parts[2] == "device"


def is_eo_heartbeat(topic: str, payload: str) -> bool:
    return topic.startswith("iot-reporting/") and '"HeartBeat"' in payload


def stamped(payload: bytes, pattern: re.Pattern[bytes], field: bytes, now_ms: int) -> bytes:
    """只换时间字段，其余字节原样发布——键序和空白一变，平台算出的 payload_hash 就对不上了。"""
    replacement = b'"' + field + b'":' + str(now_ms).encode("ascii")
    stamped_payload, count = pattern.subn(replacement, payload, count=1)
    if count != 1:
        raise SystemExit(f"报文里没有找到唯一的 {field.decode()} 字段，无法保活：{payload[:120]!r}")
    return stamped_payload


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按协议建议的节奏补发工参/心跳，让本机回放设备保持在线（Ctrl-C 结束）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--interval", type=float, default=5.0,
                        help="协议 A 工参间隔秒，默认 5（协议建议 5–10 秒，平台 30 秒判离线）")
    parser.add_argument("--eo-interval", type=float, default=1.0,
                        help="协议 C 心跳间隔秒，默认 1（光电默认 3 秒判离线）")
    parser.add_argument("--static-file", action="append", default=None,
                        help="工参 NDJSON，可重复；默认阶段 2 + 阶段 3 两个文件")
    parser.add_argument("--eo-file", default=str(DEFAULT_EO_FILE), help="取光电心跳模板的 NDJSON")
    parser.add_argument("--no-eo", action="store_true", help="只保活协议 A，不发光电心跳")
    parser.add_argument("--eo-work-state", type=int, default=0, choices=[0, 1, 2],
                        help="心跳里报的 workState，默认 0（空闲）。数据集模板写的是 1（工作中），照发会让平台认为光电正忙、手点跟踪永远挑不到它")
    parser.add_argument("--once", action="store_true", help="只发一轮就退出，便于脚本里做前置准备")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.port < 1 or args.port > 65535:
        print("端口无效", file=sys.stderr)
        return 2
    if args.interval <= 0 or args.eo_interval <= 0:
        print("间隔必须为正数", file=sys.stderr)
        return 2

    static_paths = [Path(p) for p in (args.static_file or [str(p) for p in DEFAULT_STATIC_FILES])]
    statics: list[tuple[str, bytes]] = []
    for path in static_paths:
        if not path.is_file():
            print(f"找不到工参 NDJSON: {path}", file=sys.stderr)
            return 2
        statics.extend(load(path, is_static))
    heartbeats: list[tuple[str, bytes]] = []
    if not args.no_eo:
        eo_path = Path(args.eo_file)
        if not eo_path.is_file():
            print(f"找不到光电 NDJSON: {eo_path}", file=sys.stderr)
            return 2
        # 同一台光电的心跳内容一样，留一条当模板即可。
        seen = set()
        work_state = str(args.eo_work_state).encode("ascii")
        for topic, payload in load(eo_path, is_eo_heartbeat):
            if topic in seen:
                continue
            seen.add(topic)
            # 保活心跳代表的是一台待命的光电；模板里的 workState=1 是录制时正在跟踪的状态，不能照搬。
            heartbeats.append((topic, WORK_STATE.sub(b'"workState":' + work_state, payload, count=1)))
    if not statics and not heartbeats:
        print("没有可保活的报文", file=sys.stderr)
        return 2

    client = mqtt_client("lingyun-static-keepalive")
    try:
        client.connect(args.host, args.port, keepalive=30)
    except Exception as exc:
        print(f"无法连接 {args.host}:{args.port}（{exc}）。请先在 deploy/ 执行: docker compose up -d mosquitto",
              file=sys.stderr)
        return 1
    client.loop_start()
    print(f"keepalive: 工参 {len(statics)} 条/{args.interval}s，光电心跳 {len(heartbeats)} 条/{args.eo_interval}s")

    def send(rows, pattern, field):
        now_ms = int(time.time() * 1000)
        for topic, payload in rows:
            client.publish(topic, stamped(payload, pattern, field, now_ms), qos=1, retain=False)

    try:
        next_static, next_eo = 0.0, 0.0
        rounds = 0
        while True:
            now = time.monotonic()
            if statics and now >= next_static:
                send(statics, PT_TIME, b"ptTime")
                next_static = now + args.interval
            if heartbeats and now >= next_eo:
                send(heartbeats, TIMESTAMP, b"timestamp")
                next_eo = now + args.eo_interval
            rounds += 1
            if args.once and rounds >= 1:
                time.sleep(0.3)
                return 0
            time.sleep(0.1)
    except KeyboardInterrupt:
        print("已中断", file=sys.stderr)
        return 130
    finally:
        client.loop_stop()
        try:
            client.disconnect()
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
