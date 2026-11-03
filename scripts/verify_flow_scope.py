"""Reject changes to preserved Flow 11 files and UI outside the two integration hooks."""
from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'scripts/flow-scope-manifest.json').read_text())
sha = lambda b: hashlib.sha256(b).hexdigest()
for name, expected in manifest['preserved_files'].items():
    assert sha((root / name).read_bytes()) == expected, f'Preserved Flow file changed: {name}'
for name, spec in manifest['ui_boundaries'].items():
    text = (root / name).read_text()
    start = text.index(spec['start'])
    end = text.index(spec['end'], start)
    assert sha(text[:start].encode()) == spec['prefix'], f'Flow UI prefix changed: {name}'
    assert sha(text[end:].encode()) == spec['suffix'], f'Flow UI suffix changed: {name}'
print(f"Preserved {len(manifest['preserved_files'])} Flow files and checked player/stream UI boundaries.")

settings = (root / 'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt').read_bytes()
# The Buffer & Network page is intentionally Arc's page (ported 1:1); any other edit must update the manifest hash.
assert sha(settings) == manifest['arc_buffer_settings_page_sha256'], 'Buffer & Network settings page changed'
print('Buffer & Network settings page matches the ported Arc page.')

import re
build = (root / "app/build.gradle.kts").read_text()
assert sha(re.sub(r"version(?:Code|Name)\s*=\s*[^\n]+", "VERSION", build).encode()) == manifest["build_configuration_sha256"], "Build identity changed"
