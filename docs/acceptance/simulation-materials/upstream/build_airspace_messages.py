"""Generate local simulated upstream airspace messages; never sends a request."""
import argparse
import copy
import json
import re
import time
import uuid
from pathlib import Path


def build(org_id, district_id, batch, now):
    if not org_id or not district_id or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', batch):
        raise ValueError('需要真实回读的单位/区域 ID，批次须为 1—32 位英文数字或横线')
    base = {'message_id': batch + '-create', 'revision': 1, 'action': 'UPSERT',
            'airspace_no': 'SIM-AS-' + batch, 'name': '验收模拟空域同步-' + batch,
            'kind_code': 'PERMITTED', 'owner_org_id': org_id, 'district_id': district_id,
            'boundary': {'type': 'Polygon', 'coordinates': [[[118.68,37.50],[118.69,37.50],
                         [118.69,37.51],[118.68,37.51],[118.68,37.50]]]},
            'min_altitude_m': 0, 'max_altitude_m': 150, 'altitude_datum': 'AMSL',
            'valid_from': now - 60000, 'valid_to': now + 1800000,
            'change_reason': '仅模拟上级空域下发，独立编号，不更新历史空域'}
    revision = copy.deepcopy(base)
    revision.update(message_id=batch+'-update', revision=2, valid_from=now-30000,
                    max_altitude_m=120, change_reason='模拟上级修订高度上限；名称和归属保持原值')
    stale = copy.deepcopy(base)
    stale.update(message_id=batch+'-old-revision', change_reason='异常测试：修订后提交旧版本应被拒绝')
    conflict = copy.deepcopy(base)
    conflict.update(max_altitude_m=999, change_reason='异常测试：相同消息编号但内容改变应被拒绝')
    withdrawal = {'message_id': batch+'-withdraw', 'revision': 3, 'action': 'WITHDRAW',
                  'airspace_no': base['airspace_no'], 'effective_at': now,
                  'change_reason': '仅撤销本批次模拟空域；历史版本保留'}
    return {'01-create.json': base, '02-exact-repeat.json': copy.deepcopy(base),
            '03-update.json': revision, '04-stale-rejected.json': stale,
            '05-message-conflict-rejected.json': conflict, '06-withdraw.json': withdrawal}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', type=Path, required=True, help='准备工具实际回读的 prepared-materials.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--batch', default='acc23-' + uuid.uuid4().hex[:12])
    args = parser.parse_args()
    prepared = json.loads(args.prepared.read_text(encoding='utf-8'))
    scope = prepared['scope']
    if prepared.get('marker') != 'SIM-ACC23-V1':
        raise ValueError('不是本轮已回读的模拟资料')
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('输出目录已有报文，不覆盖；重放请原样使用已生成报文')
    messages = build(scope['org_id'], scope['district_id'], args.batch, int(time.time()*1000))
    args.output.mkdir(parents=True, exist_ok=True)
    for name, body in messages.items():
        (args.output/name).write_text(json.dumps(body, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'batch': args.batch, 'messages': len(messages), 'status': 'GENERATED_NOT_SENT'}))
