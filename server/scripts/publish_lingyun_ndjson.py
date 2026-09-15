#!/usr/bin/env python3
"""Publish frozen Lingyun NDJSON to a local MQTT broker as raw payload bytes.

Do not json.dumps the payload field again. The file already stores the device
text; re-serializing it changes key order/whitespace and breaks payload_hash.

Two opt-in rewrites exist because the frozen datasets do not match how the platform
actually behaves (both off by default so the plain run still reconciles by hash):

--eo-task-id   协议 C BeginTracking 里的 taskId 是平台手点跟踪时生成的 UUID，设备只是回显；
               冻结文件里写死的 E-T1 / E-L / T-EO-TRACK 永远对不上，35 条全部 TRACK_NOT_OPEN。
               传入当前 OPEN 任务号后，所有光电跟踪上报改用它（只替换这一个字段）。
--renumber-msgcnt
               协议 A v8.6 规定 msgCnt 是每台设备各自连续编号；冻结文件用的是跨设备的全局计数器，
               回放后设备页会显示几十次"疑似缺报"。开启后按设备从 0 重新连续编号。
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
    parser.add_argument(
        "--eo-task-id",
        default=None,
        help="把协议 C 跟踪上报里的 taskId 换成这个平台任务号（手点跟踪接口返回的 task_id）；改了字节，哈希对账对光电记录不再成立",
    )
    parser.add_argument(
        "--eo-task-id-for",
        default="E-T1",
        help="--eo-task-id 只替换原来等于这个值的 taskId，默认 E-T1（主跟踪场景 24 条）。数据集里 E-L / T-EO-TRACK 是另外两段跟踪，平台同一时刻只允许一台光电有一个进行中任务，不能一起替换成同一个号，否则三段会被当成同一条航迹",
    )
    parser.add_argument(
        "--renumber-msgcnt",
        action="store_true",
        help="协议 A 探测报文按设备重新连续编号 msgCnt（冻结文件是全局计数器，不符合协议）；改了字节，哈希对账不再成立",
    )
    return parser.parse_args(argv)


MSG_CNT = re.compile(r'"msgCnt"\s*:\s*\d+')
DEVICE_ID = re.compile(r'"deviceId"\s*:\s*"([^"]*)"')


def rewrite(records: list[dict], eo_task_id: str | None, renumber_msgcnt: bool, eo_task_id_for: str = "E-T1") -> int:
    """只做正则级的单字段替换，其余字节原样；返回改动条数。两处改写都是给回放用的，不在默认路径上。"""
    changed = 0
    counters: dict[str, int] = {}
    eo_pattern = re.compile(r'"taskId"\s*:\s*"' + re.escape(eo_task_id_for) + r'"') if eo_task_id else None
    for row in records:
        payload = row["payload"]
        if eo_pattern and row["topic"].startswith("iot-reporting/") and '"BeginTracking"' in payload:
            payload, count = eo_pattern.subn(f'"taskId":"{eo_task_id}"', payload, count=1)
            if count:
                changed += 1
        if renumber_msgcnt and "/device_data/" in row["topic"]:
            match = DEVICE_ID.search(payload)
            if match and MSG_CNT.search(payload):
                device = match.group(1)
                seq = counters.get(device, 0)
                counters[device] = seq + 1
                payload = MSG_CNT.sub(f'"msgCnt":{seq}', payload, count=1)
                changed += 1
        row["payload"] = payload
    return changed


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
    if args.eo_task_id or args.renumber_msgcnt:
        changed = rewrite(records, args.eo_task_id, args.renumber_msgcnt, args.eo_task_id_for)
        print(f"rewrote {changed} payloads (eo_task_id={'yes' if args.eo_task_id else 'no'}, "
              f"renumber_msgcnt={'yes' if args.renumber_msgcnt else 'no'}); payload_hash 对账对这些记录不再成立")
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
