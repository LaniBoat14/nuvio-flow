#!/usr/bin/env python3
"""Apply an official release delta with three-way merges; fail closed on conflicts."""
import json, os, re, subprocess, tempfile, urllib.request
from pathlib import Path

def git(*args):
    return subprocess.check_output(['git', *args])
def version(tag):
    m = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?', tag)
    return tuple(map(int, m.groups()[:3])) + ((int(m[4]) if m[4] else 10**9),) if m else (-1,)
def blob(ref, path):
    r = subprocess.run(['git', 'show', f'{ref}:{path}'], capture_output=True)
    return r.stdout if r.returncode == 0 else None

def normalize_owned_fields(name, data, reference):
    """Keep Flow identity while still merging all unrelated upstream build/resources changes."""
    if data is None or reference is None: return data
    patterns = []
    if name == 'app/build.gradle.kts':
        patterns = [rb'versionCode\s*=\s*\d+', rb'versionName\s*=\s*"[^"]+"']
    elif name.startswith('app/src/main/res/values') and name.endswith('/strings.xml'):
        patterns = [rb'<string name="app_name">[^<]*</string>']
    for pattern in patterns:
        value = re.search(pattern, reference)
        if value: data = re.sub(pattern, lambda _: value[0], data)
    return data

def main():
    baseline = Path('.flow/upstream-tag').read_text().strip()
    req = urllib.request.Request('https://api.github.com/repos/NuvioMedia/NuvioTV/releases?per_page=100', headers={'Accept':'application/vnd.github+json'})
    releases = json.load(urllib.request.urlopen(req, timeout=30))
    eligible = [r for r in releases if not r['draft'] and version(r['tag_name']) > version(baseline)]
    target = max(eligible, key=lambda r:version(r['tag_name']))['tag_name'] if eligible else baseline
    print(f'Official baseline: {baseline}; target: {target}')
    changed = target != baseline
    if changed:
        for tag in (baseline, target):
            subprocess.run(['git','fetch','--depth=1','https://github.com/NuvioMedia/NuvioTV.git',f'refs/tags/{tag}:refs/tags/flow-upstream-{tag}'],check=True)
        old, new = 'flow-upstream-'+baseline, 'flow-upstream-'+target
        paths = git('diff','--name-only','--no-renames',old,new).decode().splitlines()
        conflicts=[]
        for name in paths:
            # Never execute or import upstream release automation into our signed pipeline.
            if name.startswith('.github/') or name in ('.gitignore','gradle.properties',
                'app/src/main/res/drawable-nodpi/app_logo_mark.png',
                'app/src/main/res/drawable-nodpi/app_logo_wordmark.png'): continue
            p=Path(name); base=blob(old,name); remote=blob(new,name); local=p.read_bytes() if p.is_file() else None
            base = normalize_owned_fields(name, base, local)
            remote = normalize_owned_fields(name, remote, local)
            if local == remote: continue
            if local == base: result=remote
            elif remote == base: continue
            elif base is None or remote is None or local is None or b'\0' in base+remote+local:
                conflicts.append(name);continue
            else:
                with tempfile.TemporaryDirectory() as d:
                    files=[Path(d)/x for x in ('local','base','remote')]
                    for f,data in zip(files,(local,base,remote)): f.write_bytes(data)
                    merged=subprocess.run(['git','merge-file','-p',*map(str,files)],capture_output=True)
                    if merged.returncode: conflicts.append(name);continue
                    result=merged.stdout
            if result is None: p.unlink(missing_ok=True)
            else: p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(result)
        if conflicts: raise SystemExit('Manual integration required; nothing published:\n'+'\n'.join(conflicts))
        Path('.flow/upstream-tag').write_text(target+'\n')
    if not changed:
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as f: f.write('changed=false\n')
        return
    # A Flow revision always has a monotonically increasing Android version code.
    p=Path('app/build.gradle.kts');s=p.read_text()
    current=int(re.search(r'versionCode\s*=\s*(\d+)',s)[1])
    revision=int(os.environ.get('GITHUB_RUN_NUMBER','1'))+10
    name=target.lstrip('v')+'.flow.'+str(revision)
    s=re.sub(r'versionCode\s*=\s*\d+',f'versionCode = {max(current+1, 10000+revision)}',s,count=1)
    s=re.sub(r'versionName\s*=\s*"[^"]+"',f'versionName = "{name}"',s,count=1);p.write_text(s)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as f:
            f.write(f'changed={str(changed).lower()}\ntag={name}\nprerelease={str("-" in target).lower()}\n')

if __name__=='__main__': main()
