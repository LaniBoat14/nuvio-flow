"""Verify the integrated Arc runtime against the supplied archive byte for byte."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'vendor/arc/runtime-manifest.json').read_text())
errors=[]
for entry in manifest['files']:
 p=root/entry['path']
 if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:
  errors.append(entry['path'])
if errors: raise SystemExit('Arc source mismatch: '+', '.join(errors))
print(f"Verified {len(manifest['files'])} original Arc runtime files, including native libraries.")
