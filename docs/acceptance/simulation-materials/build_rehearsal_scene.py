"""Make one offline rehearsal snapshot; no platform writes or business conclusions."""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path


def build(scene, batch):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,31}', batch):
        raise ValueError('batch must contain 1–32 letters, numbers, underscores or hyphens')
    result = copy.deepcopy(scene)
    targets = result.get('targets', [])
    ids = [t.get('id') for t in targets]
    if any(not isinstance(key, str) or not key for key in ids) or len(set(ids)) != len(ids):
        raise ValueError('target IDs must be present and unique')
    for target in targets:
        # Protocol A identity fields keep their original device-specific semantics.
        if target.get('kind') == 'uav' and target.get('transport') == 'normalized':
            suffix = hashlib.sha256(target['id'].encode()).hexdigest()[:12]
            target['uavSn'] = f'SIM-{batch}-{suffix}'
    result['name'] = f'{scene.get("name", "模拟场景")} · {batch}'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scene', required=True, type=Path)
    parser.add_argument('--batch', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = build(json.loads(args.scene.read_text(encoding='utf-8-sig')), args.batch)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Never replace a snapshot that may already have been submitted.
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(args.output)


if __name__ == '__main__':
    main()
