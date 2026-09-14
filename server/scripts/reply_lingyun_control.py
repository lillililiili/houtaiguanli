#!/usr/bin/env python3
"""Echo protocol B control_resp for local replay.

Subscribes to bridge/+/device_control/+/+ and publishes QoS 1
bridge/{provider}/device_control_resp/{type}/{deviceId} with code=0.
msgNo is copied as-is (platform uses command_no as a JSON string).
Does not invent a success in Java; this is a MQTT loopback helper.
"""
from __future__ import annotations

import argparse
import json
import sys
import time

from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from publish_lingyun_ndjson import mqtt_client  # noqa: E402


CONTROL_FILTER = "bridge/+/device_control/+/+"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="本机回放：对协议 B 控制指令回 code=0")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1883)
    return parser.parse_args(argv)


def reply_topic(control_topic: str) -> str | None:
    parts = control_topic.split("/")
    if len(parts) != 5 or parts[0] != "bridge" or parts[2] != "device_control":
        return None
    return f"bridge/{parts[1]}/device_control_resp/{parts[3]}/{parts[4]}"


def response_bytes(payload: bytes) -> bytes | None:
    try:
        root = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError):
        return None
    if not isinstance(root, dict):
        return None
    head = root.get("head")
    data = root.get("data")
    if not isinstance(head, dict):
        return None
    msg_no = head.get("msgNo")
    device_id = head.get("deviceId")
    if msg_no is None or device_id is None:
        return None
    operation_type = None
    operation_cmd = None
    if isinstance(data, dict):
        operation_type = data.get("operationType")
        operation_cmd = data.get("operationCmd")
    out_data = {"code": 0, "msg": "ok"}
    if operation_type is not None:
        out_data["operationType"] = operation_type
    if operation_cmd is not None:
        out_data["operationCmd"] = operation_cmd
    body = {
        "head": {
            "msgNo": msg_no,
            "deviceId": device_id,
            "time": int(time.time() * 1000),
        },
        "data": out_data,
    }
    return json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.port < 1 or args.port > 65535:
        print("端口无效", file=sys.stderr)
        return 2
    client = mqtt_client("lingyun-control-reply")

    def on_message(_client, _userdata, message):
        topic = reply_topic(message.topic)
        body = response_bytes(message.payload)
        if topic is None or body is None:
            print(f"忽略无法回执的报文 topic={message.topic}", file=sys.stderr)
            return
        info = client.publish(topic, body, qos=1, retain=False)
        info.wait_for_publish(timeout=10)
        print(f"replied {message.topic} -> {topic} {len(body)} bytes")

    client.on_message = on_message
    try:
        client.connect(args.host, args.port, keepalive=30)
    except Exception as exc:
        print(
            f"无法连接 {args.host}:{args.port}（{exc}）。请先在 deploy/ 执行: docker compose up -d mosquitto",
            file=sys.stderr,
        )
        return 1
    client.subscribe(CONTROL_FILTER, qos=1)
    print(f"listening {CONTROL_FILTER} on {args.host}:{args.port}")
    try:
        client.loop_forever()
    except KeyboardInterrupt:
        print("已中断", file=sys.stderr)
        return 130
    finally:
        try:
            client.disconnect()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
