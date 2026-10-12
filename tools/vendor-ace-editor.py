"""Vendor pinned upstream Ace and retain the Android touch integration.

Usage: python tools/vendor-ace-editor.py <unpacked ace-builds package> [AJ root]
Verify npm tarball integrity before invoking. AJ keeps the native APK's legacy
asset URL. This tool preserves the existing TypeScript service and formatter.
"""
import hashlib
import json
from pathlib import Path
import sys

VERSION = '1.44.0'
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path(sys.argv[1]).resolve()
SOURCE = PACKAGE / 'src-min-noconflict'
TARGET = ROOT / 'apps/app/src/main/assets/editor/ace'
assert json.loads((PACKAGE / 'package.json').read_text(encoding='utf-8'))['version'] == VERSION


def android_core():
    source = (SOURCE / 'ace.js').read_text(encoding='utf-8')
    start = source.index('ace.define("ace/mouse/touch_handler"')
    end = source.index('ace.define(', start + 12)
    # Use upstream modules except Android's native selection, magnifier and
    # frame-paced inertia. These touch APIs are verified on the actual devices.
    compatibility = (ROOT / 'tools/ace-android-touch.js').read_text(encoding='utf-8')
    return (source[:start] + '(function(){\n' + compatibility + '\n})(),'
            + source[end:]).encode('utf-8')


def manifest(folder, extra):
    files = {p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(folder.rglob('*')) if p.is_file() and p.name != 'DEPENDENCIES.json'}
    (folder / 'DEPENDENCIES.json').write_text(json.dumps({'ace': VERSION, **extra,
        'androidTouch': 'tools/ace-android-touch.js', 'files': files}, indent=2) + '\n', encoding='utf-8')
    print(folder.name, VERSION, len(files), 'files')


core = android_core()
for name in ('ace.js', 'mode-javascript.js', 'mode-json.js', 'mode-typescript.js',
             'theme-tomorrow_night.js', 'theme-textmate.js',
             'ext-language_tools.js', 'ext-searchbox.js', 'snippets/javascript.js'):
    dest = TARGET / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(core if name == 'ace.js' else (SOURCE / name).read_bytes())
(TARGET / 'LICENSE').write_bytes((PACKAGE / 'LICENSE').read_bytes())
previous = json.loads((TARGET / 'DEPENDENCIES.json').read_text(encoding='utf-8'))
manifest(TARGET, {'typescript': previous['typescript']})

if len(sys.argv) > 2:
    aj = Path(sys.argv[2]).resolve() / 'assets/editor'
    for src in SOURCE.rglob('*'):
        if not src.is_file():
            continue
        dest = aj / 'src-min-noconflict' / src.relative_to(SOURCE)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(core if src.name == 'ace.js' else src.read_bytes())
    (aj / 'LICENSE').write_bytes((PACKAGE / 'LICENSE').read_bytes())
    manifest(aj, {'legacyAssetDirectory': 'ace-builds-1.4.12'})
