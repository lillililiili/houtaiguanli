#!/usr/bin/env python3
"""独立航线设备演示：仅向本机 broker 发工参，不修改现有 S85 回放。

雷达持续报 workState=2，TDOA 每 90 秒报一次（30 秒后由后台判离线），
其余设备每 5 秒报正常。无探测目标，避免将未匹配计划变成已匹配。
"""
import json
import time
from publish_lingyun_ndjson import mqtt_client

DEVICES = [
    ('radar', 'FP-CHECK-R1', '航线附近雷达·故障模拟', 1, 118.015, 37.015, 2, 5),
    ('tdoa', 'FP-CHECK-T1', '航线附近TDOA·离线模拟', 10, 118.016, 37.016, 1, 90),
    ('rid', 'FP-CHECK-I1', '航线附近RemoteID·正常模拟', 102, 118.014, 37.014, 1, 5),
    ('radar', 'FP-CHECK-R2', '跨区航线雷达·正常模拟', 1, 119.005, 38.005, 1, 5),
]

def main():
    client = mqtt_client('flight-device-check-simulator')
    client.connect('127.0.0.1', 1883, keepalive=30)
    client.loop_start()
    due = {}
    print('航线设备 MQTT 模拟已启动：1 台故障、1 台间歇离线、2 台正常。', flush=True)
    try:
        while True:
            now = time.monotonic()
            for kind, device_id, name, device_type, lon, lat, state, interval in DEVICES:
                if now < due.get(device_id, 0):
                    continue
                payload = {'deviceId': device_id, 'providerCode': 'fpcheck', 'deviceName': name,
                    'deviceLongitude': lon, 'deviceLatitude': lat, 'deviceAltitude': 12.5,
                    'deviceType': device_type, 'workState': state, 'ptTime': int(time.time() * 1000), 'extension': {}}
                sent = client.publish('bridge/fpcheck/device/' + kind + '/' + device_id,
                    json.dumps(payload, ensure_ascii=False), qos=1, retain=False)
                sent.wait_for_publish(timeout=5)
                due[device_id] = now + interval
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass
    finally:
        client.disconnect()
        client.loop_stop()

if __name__ == '__main__':
    main()
