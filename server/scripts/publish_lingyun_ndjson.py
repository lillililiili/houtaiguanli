#!/usr/bin/env python3
"""Publish frozen Lingyun NDJSON to a local MQTT broker as raw payload bytes.

Do not json.dumps the payload field again. The file already stores the device
text; re-serializing it changes key order/whitespace and breaks payload_hash.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_FILE = REPO_ROOT / "docs" / "直连接入计划" / "stage85-lingyun-demo.mqtt.ndjson"


def mqtt_client(client_id: str):
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        print("缺少 paho-mqtt，请执行: pip install paho-mqtt", file=sys.stderr)
        sys.exit(1)
    try:
        return mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=client_id,
            protocol=mqtt.MQTTv311,
        )
    except (AttributeError, TypeError):
        return mqtt.Client(client_id=client_id, protocol=mqtt.MQTTv311)


def load_records(path: Path) -> list[dict]:
    records = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, start=1):
            line = raw.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: 不是合法 JSON：{exc}") from exc
            topic = row.get("topic")
            payload = row.get("payload")
            record_no = row.get("record_no")
            if not isinstance(topic, str) or not topic:
                raise SystemExit(f"{path}:{line_no}: topic 必须是非空字符串")
            if not isinstance(payload, str):
                raise SystemExit(
                    f"{path}:{line_no}: payload 必须是字符串，禁止把对象再序列化后发送"
                )
            if not isinstance(record_no, int):
                raise SystemExit(f"{path}:{line_no}: record_no 必须是整数")
            records.append(
                {
                    "topic": topic,
                    "payload": payload,
                    "record_no": record_no,
                    "line_no": line_no,
                }
            )
    records.sort(key=lambda item: (item["record_no"], item["line_no"]))
    return records


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按 record_no 把凌云 NDJSON 的 payload 原样发布到 MQTT（QoS 1）"
    )
    parser.add_argument("--host", default="127.0.0.1", help="broker 主机，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=1883, help="broker 端口，默认 1883")
    parser.add_argument(
        "--file",
        default=str(DEFAULT_FILE),
        help="NDJSON 路径，默认仓库内 stage85-lingyun-demo.mqtt.ndjson",
    )
    parser.add_argument("--limit", type=int, default=0, help="只发排序后的前 N 条；0 表示全部")
    parser.add_argument("--sleep-ms", type=int, default=0, help="两条之间的间隔毫秒，默认 0")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只校验并打印将要发布的主题，不连接 broker",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.port < 1 or args.port > 65535:
        print("端口无效", file=sys.stderr)
        return 2
    if args.limit < 0:
        print("--limit 不能为负数", file=sys.stderr)
        return 2
    if args.sleep_ms < 0:
        print("--sleep-ms 不能为负数", file=sys.stderr)
        return 2

    path = Path(args.file)
    if not path.is_file():
        print(f"找不到 NDJSON 文件: {path}", file=sys.stderr)
        return 2

    records = load_records(path)
    if args.limit:
        records = records[: args.limit]
    print(f"loaded {len(records)} records from {path}")
    if args.dry_run:
        for row in records:
            print(f"{row['record_no']}\t{row['topic']}\t{len(row['payload'].encode('utf-8'))} bytes")
        return 0

    client = mqtt_client("lingyun-ndjson-pub")
    try:
        client.connect(args.host, args.port, keepalive=30)
    except Exception as exc:
        print(
            f"无法连接 {args.host}:{args.port}（{exc}）。请先在 deploy/ 执行: docker compose up -d mosquitto",
            file=sys.stderr,
        )
        return 1
    client.loop_start()
    published = 0
    try:
        for index, row in enumerate(records):
            body = row["payload"].encode("utf-8")
            info = client.publish(row["topic"], body, qos=1, retain=False)
            info.wait_for_publish(timeout=10)
            if not info.is_published():
                print(f"发布失败 record_no={row['record_no']} topic={row['topic']}", file=sys.stderr)
                return 1
            published += 1
            if args.sleep_ms and index + 1 < len(records):
                time.sleep(args.sleep_ms / 1000.0)
    finally:
        client.loop_stop()
        try:
            client.disconnect()
        except Exception:
            pass
    print(f"published {published} qos=1 retained=false")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        print("已中断", file=sys.stderr)
        sys.exit(130)
