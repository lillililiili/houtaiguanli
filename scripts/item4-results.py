"""Archive only Surefire suites actually reported by this command, including failures/skips."""
import argparse
import json
import re
import shutil
from pathlib import Path
import xml.etree.ElementTree as ET


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / 'server' / 'target'
    log = args.log.resolve()
    if not log.is_relative_to(root):
        parser.error('Results must be under server/target')
    names = re.findall(r'-- in ([\w.$]+)', log.read_text(encoding='utf-8', errors='replace'))
    destination = log.with_suffix('')
    destination.mkdir(exist_ok=True)
    suites = []
    for name in dict.fromkeys(names):
        path = root/'surefire-reports'/('TEST-'+name+'.xml')
        tree = ET.parse(path).getroot()
        # The log is authoritative for suite membership. Preserve every individual outcome.
        suite = {'name':name, **{k:int(tree.get(k,'0')) for k in ('tests','failures','errors','skipped')},
                 'cases':[{'name':case.get('name'), 'result': next((tag for tag in ('failure','error','skipped')
                         if case.find(tag) is not None), 'passed')} for case in tree.findall('testcase')]}
        suites.append(suite)
        shutil.copy2(path, destination/path.name)
    summary = {'log':str(log), 'build_success':'BUILD SUCCESS' in log.read_text(encoding='utf-8',errors='replace'),
               **{k:sum(s[k] for s in suites) for k in ('tests','failures','errors','skipped')}, 'suites':suites}
    (destination/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k!='suites'}))
