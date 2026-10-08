"""Capture a secret-free, read-only source baseline before fault acceptance."""
import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def git(root, *args):
    # Read only this explicitly selected workspace; never change global Git trust.
    return subprocess.check_output(['git', '-c', 'safe.directory=' + root.as_posix(), '-C', str(root), *args])


def snapshot(root):
    names = set(git(root, 'diff', '--name-only', '-z', 'HEAD').split(b'\0'))
    names.update(git(root, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0'))
    files = {}
    for raw in sorted(names):
        if not raw:
            continue
        name = raw.decode('utf-8')
        path = root / name
        files[name] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else 'deleted'
    return {'root': str(root), 'head': git(root, 'rev-parse', 'HEAD').decode().strip(),
            'changes': files}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frontend', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    backend = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if not output.is_relative_to(backend / 'server' / 'target'):
        parser.error('Baseline output must stay in ignored server/target')
    output.parent.mkdir(parents=True, exist_ok=True)
    baseline = {'captured_at': datetime.now(timezone.utc).isoformat(),
                'repositories': [snapshot(backend), snapshot(args.frontend.resolve())]}
    with output.open('x', encoding='utf-8') as stream:
        json.dump(baseline, stream, ensure_ascii=False, indent=2)
    print(output)
