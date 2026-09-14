#!/usr/bin/env python3
"""Protocol B negative/positive checks against a prepared local replay batch.

Stop mqtt_business_test.py stream for this batch first: this script is the sole
device responder. It creates test command records, never changes business code.
"""
import argparse
import json
import queue
import time
import uuid

from mqtt_business_test import Api, OUT, dump, millis, static
from publish_lingyun_ndjson import mqtt_client
from reply_lingyun_control import reply_topic, response_bytes


def run(batch):
    m = json.loads((OUT / batch / 'manifest.json').read_text(encoding='utf-8'))
    d = m['devices']['ifr']
    device_id = d['detail']['device']['device_id']
    if d['registration']['source_mode'] != 'replay' or m['host'] != '127.0.0.1':
        raise ValueError('Only local replay is supported')
    api, incoming = Api(batch), queue.Queue()
    client = mqtt_client('checks-' + uuid.uuid4().hex[:12])
    topic = f"bridge/{m['provider']}/device_control/ifr/{d['external_id']}"
    client.on_connect = lambda c, *_: c.subscribe(topic, qos=1)
    client.on_message = lambda _c, _u, msg: incoming.put((msg.topic, bytes(msg.payload)))
    client.connect(m['host'], m['port'], 30)
    client.loop_start()
    results = []

    def pub(t, body):
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        client.publish(t, raw, qos=1, retain=False).wait_for_publish(10)

    def heartbeat():
        pub(topic.replace('/device_control/', '/device/'), static(m, 'ifr', millis()))

    try:
        heartbeat()
        time.sleep(1)
        for mode, expected in [('success', 'SUCCEEDED'), ('failure', 'FAILED'), ('wrong-then-success', 'SUCCEEDED'), ('none', 'TIMED_OUT')]:
            key = str(uuid.uuid4())
            body = {'authorization_id': 'MQTT-' + batch, 'operation_type': 1, 'operation_cmd': 60003,
                    'operation_params': {'duration': 10}, 'reason': f'MQTT {batch} intentional {mode}'}
            cmd = api.data('POST', f'/devices/{device_id}/commands/lingyun-control', body, key)
            command_id = cmd['command_id']
            rec = {'case': mode, 'command_id': command_id, 'command_no': cmd['command_no'], 'expected': expected}
            deadline = time.monotonic() + 18
            next_heartbeat = 0
            last = cmd
            while time.monotonic() < deadline:
                if time.monotonic() >= next_heartbeat:
                    heartbeat()
                    next_heartbeat = time.monotonic() + 5
                try:
                    t, payload = incoming.get(timeout=0.3)
                    root = json.loads(payload)
                    if root['head']['msgNo'] != cmd['command_no']:
                        continue
                    rec['mqtt_command'] = root
                    reply = json.loads(response_bytes(payload))
                    if mode == 'wrong-then-success':
                        wrong = json.loads(json.dumps(reply))
                        wrong['head']['msgNo'] += '-wrong'
                        pub(reply_topic(t), wrong)
                        time.sleep(0.4)
                        rec['after_wrong_reply'] = api.data('GET', '/device-commands/' + command_id)['status']
                    if mode != 'none':
                        if mode == 'failure':
                            reply['data'].update(code=1, msg='intentional MQTT test failure')
                        pub(reply_topic(t), reply)
                        pub(reply_topic(t), reply)  # exact duplicate must not add another receipt
                        rec['mqtt_reply'] = reply
                except queue.Empty:
                    pass
                last = api.data('GET', '/device-commands/' + command_id)
                if last['status'] in ('SUCCEEDED', 'FAILED', 'TIMED_OUT'):
                    break
            rec['actual'] = last['status']
            rec['receipt_count'] = len(last['receipts'])
            replay = api.data('POST', f'/devices/{device_id}/commands/lingyun-control', body, key)
            rec['same_idempotency_command'] = replay['command_id'] == command_id
            rec['pass'] = (last['status'] == expected and rec['same_idempotency_command']
                           and len(last['receipts']) == (0 if mode == 'none' else 1)
                           and (mode != 'wrong-then-success' or rec.get('after_wrong_reply') in ('QUEUED', 'SENT')))
            results.append(rec)
            dump(OUT / batch / 'control-checks.json', results)
            print(mode, rec['actual'], 'PASS' if rec['pass'] else 'FAIL', flush=True)
    finally:
        client.disconnect()
        client.loop_stop()
    return 0 if all(r['pass'] for r in results) else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch', required=True)
    args = parser.parse_args()
    # Resolve through the same batch-name restriction as the generator.
    import re
    if not re.fullmatch(r'[a-zA-Z0-9-]{3,28}', args.batch):
        parser.error('invalid batch')
    raise SystemExit(run(args.batch))
