# -*- coding: utf-8 -*-
"""Rebuild the first-generation Auto.js Pro API alignment inventory.

Stages
  1. extract : PDF -> .artifacts/pro-api-lines.json   (page / size / fonts / bold_ratio / text)
  2. index   : lines -> .artifacts/pro-api-index.json (module / section / API entries + body details)
  3. check   : index -> .artifacts/pro-api-status.json + docs/architecture/pro-api-compatibility.csv
  4. rollup  : status -> .artifacts/pro-api-rollup.md  (markdown table for the doc)

Usage
  python tools/pro-api/rebuild.py --pdf "<path to Auto.js Pro官方API文档.pdf>"
  python tools/pro-api/rebuild.py --pdf ... --stage index --stage check

Requires: pdfminer.six   (pip install pdfminer.six)

Why font metadata matters: this PDF is a mash-up of articles, so heading sizes
change from page to page; absolute size is not a reliable "heading level".  What
is stable is the *style*: headings are bold (12.0 / 14.0 / 14.1 / 15.0), body is
11.0/11.1 regular, bullets are Calibri 10.4/10.5, code is Consolas, page
header/footer is 9.0.  `[Pro 8.8.17 新增]` markers are bold too, but only 11.0/11.1.
"""
import argparse
import io
import json
import os
import re
import sys
from collections import Counter, OrderedDict, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ART = os.path.join(REPO, '.artifacts')

DEFAULT_PDF = r'C:\Users\18101\Downloads\Auto.js Pro官方API文档.pdf'
LINES_JSON = os.path.join(ART, 'pro-api-lines.json')
INDEX_JSON = os.path.join(ART, 'pro-api-index.json')
STATUS_JSON = os.path.join(ART, 'pro-api-status.json')
ROLLUP_MD = os.path.join(ART, 'pro-api-rollup.md')
CSV_PATH = os.path.join(REPO, 'docs', 'architecture', 'pro-api-compatibility.csv')

ENTRY_MIN_SIZE = 11.8       # API entry headings are >= 12.0 bold
SECTION_MIN_SIZE = 13.0     # chapter 18 / module 16 / sub-section 15
MIN_BOLD_RATIO = 0.99       # entry headings are fully bold
SECTION_BOLD_RATIO = 0.30   # section headings are only partly bold (mixed fonts)
PAGEART_MAX_SIZE = 9.5      # page header / footer

SIG = re.compile(r'^([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]*)*)\s*\(')
CTOR = re.compile(r'^new\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]*)*)\s*\(')
DOTTED = re.compile(r'^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)+$')
CLASSNAME = re.compile(r'^[A-Z][A-Za-z0-9_]{1,30}$')
SECTION = re.compile(r'^(\d+(?:\.\d+)*)\s*(\S.*)$')
VERMARK = re.compile(r'^\[[^\]]*新增[^\]]*\]$')
STABILITY = re.compile(r'Stability\s*:\s*([012])\s*-?\s*(\w+)', re.I)
BULLET = re.compile(r'^\s*[•·]\s*(.*)$')
SIGTAIL = re.compile(r"^[A-Za-z0-9_$\.\s\[\],=\"'\-]*\)\s*$")
SECTIONTITLE = re.compile(r'^\d+\.\d+')
BLOCK = re.compile(r'^(\s*)(?:var|let|const|global\.)?\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*'
                   r'(?:Object\.freeze\(|Object\.assign\(|)?\s*\{\s*$')


# --------------------------------------------------------------------------- 1
def stage_extract(pdf):
    from pdfminer.high_level import extract_pages
    from pdfminer.layout import LTTextContainer, LTTextLine, LAParams

    lines = []
    pages = 0
    for page in extract_pages(pdf, laparams=LAParams(line_margin=0.2)):
        pages += 1
        for element in page:
            if not isinstance(element, LTTextContainer):
                continue
            for tl in element:
                if not isinstance(tl, LTTextLine):
                    continue
                text = tl.get_text().rstrip('\n')
                if not text.strip():
                    continue
                sizes, fonts, bold, total = [], [], 0, 0
                for ch in tl:
                    if not hasattr(ch, 'fontname'):
                        continue
                    total += 1
                    sizes.append(round(ch.size, 1))
                    short = ch.fontname.split('+')[-1]
                    if short not in fonts:
                        fonts.append(short)
                    if 'Bold' in short:
                        bold += 1
                lines.append({
                    'page': pages,
                    'size': max(sizes) if sizes else 0.0,
                    'font': fonts[0] if fonts else '',
                    'fonts': fonts,
                    'bold_ratio': round(bold / total, 3) if total else 0.0,
                    'text': text,
                    'x0': round(tl.x0, 1),
                    'y0': round(tl.y0, 1),
                })
    io.open(LINES_JSON, 'w', encoding='utf-8').write(
        json.dumps({'pages': pages, 'lines': lines}, ensure_ascii=False))
    print('[extract] pages=%d lines=%d -> %s' % (pages, len(lines), LINES_JSON))


# --------------------------------------------------------------------------- 2
def norm(text):
    t = text.strip()
    t = re.sub(r'#+\s*$', '', t).strip()
    return t.replace('（', '(').replace('）', ')')


def is_entry_head(line):
    return line['bold_ratio'] >= MIN_BOLD_RATIO and line['size'] >= ENTRY_MIN_SIZE


def is_section_head(line):
    return (line['size'] >= SECTION_MIN_SIZE and line['bold_ratio'] >= SECTION_BOLD_RATIO
            and SECTION.match(norm(line['text'])) is not None)


def is_page_art(line):
    return line['size'] <= PAGEART_MAX_SIZE


def balanced(s):
    return s.count('(') == s.count(')')


def merge_signature(lines, i):
    """Join a wrapped API heading; page breaks are allowed (their header/footer is skipped)."""
    text = norm(lines[i]['text'])
    j = i
    guard = 0
    while not balanced(text) and guard < 8:
        guard += 1
        k = j + 1
        while k < len(lines) and is_page_art(lines[k]):
            k += 1
        if k >= len(lines):
            break
        nxt = lines[k]
        nt = norm(nxt['text'])
        # normal case: the next line is another bold fragment of the heading
        if is_entry_head(nxt) and not (VERMARK.match(nt) or SIG.match(nt) or SECTION.match(nt)):
            j = k
        # wrapped tail that lost its bold run (e.g. `..., bufferSize =` + `8192])`)
        elif SIGTAIL.match(nt) and not is_section_head(nxt):
            j = k
        else:
            break
        text += nt if text.endswith((' ', '(', '[')) or nt.startswith((')', ',', ' ', ']')) else ' ' + nt
    return text, j


def classify(text):
    c = CTOR.match(text)
    if c and text.endswith(')'):
        return 'constructor', c.group(1)
    m = SIG.match(text)
    if m and text.endswith(')'):
        return 'function', m.group(1)
    if DOTTED.match(text):
        return 'member', text
    if CLASSNAME.match(text):
        return 'class', text
    return 'label', text[:60]


def stage_index():
    data = json.load(io.open(LINES_JSON, encoding='utf-8'))
    lines = data['lines']

    headings = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not (is_entry_head(line) or is_section_head(line)):
            i += 1
            continue
        text, j = merge_signature(lines, i)
        headings.append({'idx': i, 'page': line['page'], 'size': line['size'],
                         'bold': line['bold_ratio'], 'text': text})
        i = j + 1

    sections, entries = [], []
    state = {'module': None, 'section': None}

    def add(page, sig, kind, name, size, line_no):
        entries.append({
            'page': page,
            'module_num': state['module']['num'] if state['module'] else '',
            'module': state['module']['title'] if state['module'] else '',
            'section_num': state['section']['num'] if state['section'] else '',
            'section': state['section']['title'] if state['section'] else '',
            'kind': kind, 'name': name, 'signature': sig, 'head_size': size, 'line': line_no,
        })

    for h in headings:
        t = h['text']
        m = SECTION.match(t)
        if (m is not None and len(m.group(1)) <= 8 and h['size'] >= SECTION_MIN_SIZE
                and h['bold'] < MIN_BOLD_RATIO):
            num, title = m.group(1), m.group(2).strip()
            sections.append({'page': h['page'], 'num': num, 'title': title[:80], 'size': h['size']})
            if re.fullmatch(r'\d+\.\d+', num):
                state['module'] = {'num': num, 'title': title[:80], 'page': h['page']}
                state['section'] = None
            else:
                state['section'] = {'num': num, 'title': title[:80], 'page': h['page']}
            kind, name = classify(title)     # e.g. "2.2.1 setScreenMetrics(width, height)"
            if kind in ('function', 'constructor'):
                add(h['page'], title, kind, name, h['size'], h['idx'])
            continue
        kind, name = classify(t)
        add(h['page'], t, kind, name, h['size'], h['idx'])

    by_line = {e['line']: e for e in entries}
    order = sorted(by_line)
    for pos, ln in enumerate(order):
        e = by_line[ln]
        end = order[pos + 1] if pos + 1 < len(order) else len(lines)
        e.update({'version_marker': '', 'stability': '', 'returns': '', 'bullets': [], 'doc': ''})
        doc_taken = False
        for b in lines[ln + 1:end]:
            txt = b['text'].strip()
            if VERMARK.match(txt):
                e['version_marker'] = txt.strip('[]')
                continue
            s = STABILITY.search(txt)
            if s:
                e['stability'] = s.group(1)
                continue
            bm = BULLET.match(b['text'])
            if bm:
                content = bm.group(1).strip()
                if content.startswith('返回'):
                    e['returns'] = content[2:].strip()
                elif content:
                    e['bullets'].append(content)
                continue
            if not doc_taken and 'Consolas' not in b['fonts'] and b['bold_ratio'] < MIN_BOLD_RATIO:
                e['doc'] = txt[:140]
                doc_taken = True

    io.open(INDEX_JSON, 'w', encoding='utf-8').write(json.dumps(
        {'pages': data['pages'], 'entries': entries, 'sections': sections},
        ensure_ascii=False, indent=1))
    print('[index] entries=%d %s modules=%d' % (
        len(entries), dict(Counter(e['kind'] for e in entries)),
        len([s for s in sections if re.fullmatch(r'\d+\.\d+', s['num'])])))


# --------------------------------------------------------------------------- 3
ROOTS = ['modules', 'apps/app/src/main/java', 'apps/app/src/main/assets', 'inrt/src']
EXTS = ('.js', '.java', '.kt', '.cpp', '.h', '.hpp')
SKIP_PARTS = ('/build/', '/.cxx/', '/.gradle/', '/node_modules/', '/jniLibs/')

HINTS = {
    'app': ('app', ['AppUtils', 'AppModule']),
    'automator': ('automator', []),
    'console': ('console', ['AbstractConsole', 'GlobalConsole']),
    'canvas': ('canvas', ['Canvas']),
    'colors': ('colors', ['Colors']),
    'crypto': ('crypto', ['Crypto']),
    'debug': ('debug', ['Debug']),
    'base64': ('base64', ['Base64']),
    'device': ('device', ['Device']),
    'dialogs': ('dialogs', ['Dialogs']),
    'engines': ('engines', ['Engines']),
    'events': ('events', ['Events']),
    'floaty': ('floaty', ['Floaty']),
    'files': ('files', ['Files']),
    'globals': ('globals', ['ScriptRuntime']),
    'http': ('http', ['Http', 'OkHttp']),
    'images': ('images', ['Images', 'OpenCvYoloDetector']),
    'keys': ('keys', ['Keys', 'Accessibility']),
    'media': ('media', ['Media']),
    'module': ('module', ['jvm-npm']),
    'ocr': ('ocr', ['Ocr']),
    'plugins': ('plugins', ['Plugins']),
    'power_manager': ('power_manager', ['PowerManager']),
    'sensors': ('sensors', ['Sensors']),
    'settings': ('settings', ['Settings']),
    'shell': ('shell', ['AbstractShell', 'ShizukuShell', 'Shell']),
    'sqlite': ('sqlite', ['Sqlite']),
    'storages': ('storages', ['Storages', 'LocalStorage']),
    'threads': ('threads', ['ScriptThreadPool', 'Threads']),
    'timers': ('timers', ['Timers']),
    'ui': ('ui', ['UI', 'Ui']),
    'util': ('util', ['Util']),
    'web': ('web', ['WebViewModule', 'Web']),
    'work_manager': ('work_manager', ['WorkManagerModule']),
    'zip': ('zips', ['Zips', 'Zip']),
    'zips': ('zips', ['Zips', 'Zip']),
    'yolo': ('yolo', ['Yolo', 'OpenCvYoloDetector']),
    'selector': ('selector', ['UiSelector', 'Selector', 'Accessibility']),
    'RootAutomator': ('RootAutomator', ['RootAutomator']),
    'WebSocket': ('web', ['WebSocket']),
    'thirdPartyPlugins': ('', ['Pytorch', 'Tensor', 'OdResult', 'Vocab', 'ObjectDetection']),
}

GLOBAL_FILES = ('__globals__.js', 'init.js', 'ScriptRuntime.java', '__automator__.js',
                '__timers__.js', '__selector__.js', 'AutoJs.java', 'GlobalConsole.java',
                'AbstractConsole.java')

# real engine modules (used to tell "module member" from "object/instance member")
MODULE_NAMES = set(HINTS) | {'globals', 'zips', 'zip', 'web', 'util', 'sqlite', 'io', 'keys',
                             'canvas', 'colors', 'selector', 'automator', 'console', 'timers'}

# PDF module number -> JS namespace, used to attribute free-form labels
PDF_NS = {
    '2.3': 'app', '2.5': 'colors', '2.6': 'canvas', '2.7': 'console', '2.8': 'crypto',
    '2.10': 'device', '2.11': 'dialogs', '2.12': 'engines', '2.13': 'events',
    '2.14': 'floaty', '2.15': 'files', '2.16': 'globals', '2.17': 'http',
    '2.18': 'images', '2.19': 'keys', '2.20': 'media', '2.25': 'sensors', '2.26': 'shell',
    '2.27': 'storages', '2.28': 'settings', '2.29': 'threads', '2.31': 'timers',
    '2.32': 'work_manager', '2.34': 'web', '2.35': 'zips', '2.36': 'ui',
}


def read(path):
    try:
        return io.open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


def collect_files():
    files = {}
    for root in ROOTS:
        base = os.path.join(REPO, *root.split('/'))
        for dirpath, _dirs, names in os.walk(base):
            for n in names:
                if not n.endswith(EXTS):
                    continue
                p = os.path.join(dirpath, n)
                rel = os.path.relpath(p, REPO).replace('\\', '/')
                if any(part in '/' + rel for part in SKIP_PARTS):
                    continue
                try:
                    if os.path.getsize(p) > 4 * 1024 * 1024:
                        continue
                except OSError:
                    continue
                files[rel] = read(p)
    return files


def js_object_members(text):
    """module name -> top-level keys of `x = { ... }` / `global.x = Object.freeze({...})`."""
    lines = text.split('\n')
    out = OrderedDict()
    for i, line in enumerate(lines):
        m = BLOCK.match(line)
        if not m:
            continue
        indent, name = len(m.group(1)), m.group(2)
        members, depth, j = [], 1, i + 1
        while j < len(lines) and depth > 0:
            cur = lines[j]
            key = re.match(r'^(\s*)([A-Za-z_$][A-Za-z0-9_$]*)\s*:', cur)
            if key and len(key.group(1)) == indent + 4 and depth == 1:
                members.append(key.group(2))
            depth += cur.count('{') + cur.count('[') - cur.count('}') - cur.count(']')
            j += 1
        out.setdefault(name, set()).update(members)
    return out


def stage_check():
    idx = json.load(io.open(INDEX_JSON, encoding='utf-8'))

    def keep(e):
        if e['kind'] in ('function', 'member', 'class', 'constructor'):
            return True
        if e['kind'] != 'label' or len(e['name']) > 40:
            return False
        if not e['module_num'] and e['page'] <= 1:      # PDF cover title, not an API
            return False
        for bad in ('附录', '命令简介', '方法索引', '绘制', '获取画布'):
            if bad in e.get('section', '') or bad in e['name']:
                return False
        return True

    entries = [e for e in idx['entries'] if keep(e)]
    files = collect_files()

    by_stem = {}
    for rel in files:
        stem = os.path.splitext(os.path.basename(rel))[0].strip('_').lower()
        by_stem.setdefault(stem, []).append(rel)

    cpp_rel = 'modules/engine/src/main/cpp/quickjs_jni.cpp'
    cpp_text = files.get(cpp_rel, '')
    qjs_members = js_object_members(cpp_text)
    bridge_rel = 'modules/engine/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java'
    bridge_text = files.get(bridge_rel, '')
    qjs_samples = [rel for rel in files if '/sample/QuickJS 新引擎/' in '/' + rel]
    qjs_regression = [rel for rel in qjs_samples if '回归测试/' in rel]
    global_sources = [rel for rel in files if os.path.basename(rel) in GLOBAL_FILES]

    generic_cache = {}

    def generic_sources(mod):
        if mod not in generic_cache:
            pat = re.compile(r'(?<![\w$])%s(?![\w$])' % re.escape(mod))
            hits = [rel for rel, text in files.items() if pat.search(text)]
            hits.sort(key=len)
            generic_cache[mod] = hits[:12]
        return generic_cache[mod]

    CLASS_DECL = re.compile(r'(?:^|\n)\s*(?:public\s+|open\s+|abstract\s+|internal\s+|final\s+|'
                            r'sealed\s+|data\s+)*(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)')

    class_index = {}
    for _rel, _text in files.items():
        for _m in CLASS_DECL.finditer(_text):
            class_index.setdefault(_m.group(1), []).append((_rel, _m.start()))

    def parents_of(rel, start):
        tail = files[rel][start:start + 600]
        out = []
        m = re.search(r'\bextends\s+([A-Za-z_][\w\.]*)', tail)
        if m:
            out.append(m.group(1).split('.')[-1])
        m = re.search(r'\bimplements\s+([^{]+)', tail)
        if m:
            out += [x.strip().split('.')[-1].split('<')[0] for x in m.group(1).split(',')]
        for m in re.finditer(r':\s*([A-Z][\w\.]*)\s*[(<]', tail):
            out.append(m.group(1).split('.')[-1])
        return [x for x in out if x and x[0].isupper()]

    class_cache = {}

    def class_sources(mod):
        """Definition file of class `mod` plus its supertypes (UiSelector ->
        UiGlobalSelector, ...) so that inherited members are in scope."""
        if mod in class_cache:
            return class_cache[mod]
        seen_files, seen_classes, queue = [], {mod}, list(class_index.get(mod, []))
        depth = 0
        while queue and depth < 4:
            nxt = []
            for rel, start in queue:
                if rel not in seen_files:
                    seen_files.append(rel)
                for p in parents_of(rel, start):
                    if p in seen_classes:
                        continue
                    seen_classes.add(p)
                    nxt += class_index.get(p, [])
            queue = nxt
            depth += 1
        class_cache[mod] = seen_files
        return seen_files

    def rhino_sources(mod):
        hint, extra = HINTS.get(mod, (mod.lstrip('$'), []))
        out = []
        if hint:
            for key in (hint.lower(), ('_' + hint).lower()):
                for rel in by_stem.get(key.strip('_'), []):
                    if rel not in out:
                        out.append(rel)
        low = mod.lstrip('$').lower()
        for rel in files:
            base = os.path.basename(rel).rsplit('.', 1)[0].lower()
            if (base == low or base == low + 'module') and rel not in out:
                out.append(rel)
        for e in extra:
            for rel in files:
                if e.lower() in os.path.basename(rel).lower() and rel not in out:
                    out.append(rel)
        if mod[:1].isupper():
            for rel in class_sources(mod) + generic_sources(mod):
                if rel not in out:
                    out.append(rel)
        if not out:
            # no dedicated source file for this name (window.setPosition,
            # UiCollection, ...): fall back to every file that mentions it
            out = generic_sources(mod)
        return out

    debug_mod = os.environ.get('PRO_API_DEBUG_MOD')
    if debug_mod:
        print('[debug] sources(%s) = %s' % (debug_mod, rhino_sources(debug_mod)))
        print('[debug] class_sources = %s' % class_sources(debug_mod))
        print('[debug] generic_sources = %s' % generic_sources(debug_mod))
        print('[debug] by_stem has floaty/floats: %s' % [k for k in by_stem if 'float' in k])

    rows = []
    dev = {}
    for eng in ('rhino', 'quickjs', 'rhino-ui', 'quickjs-ui'):
        p = os.path.join(ART, 'pro-api-device-%s.json' % eng)
        if os.path.exists(p):
            dev[eng] = json.load(io.open(p, encoding='utf-8'))

    def merge_device(engine, mod, member, status, evidence, module_num='', kind=''):
        """Let the on-device member probe override the static verdict.

        * a hit in *any* probe variant (plain script / `"ui";` script) is proof
          the member exists -> `实机有`;
        * a miss is only conclusive when the static scan also found nothing --
          `canvas` for instance is implemented (`ScriptCanvas`) but is handed to
          `<canvas>` callbacks instead of being mounted as a global module, so a
          probe miss alone must not be reported as "missing";
        * class / instance names are never conclusive (host Java objects).
        """
        variants = [v for k, v in dev.items() if k == engine or k == engine + '-ui']
        if not variants:
            return status, evidence
        for v in variants:
            if mod == 'globals':
                kind = v['globals'].get(member)
                if kind and kind != 'MISSING':
                    return '实机有', 'probe: global.%s 存在(%s)' % (member, kind)
            elif mod in v['modules'] and member in v['modules'][mod]:
                return '实机有', 'probe: %s.%s 存在' % (mod, member)
        if mod == 'globals':
            # A dot-less name may be a real global function (`toast`, `Home()`)
            # or a class / class member the doc happens to write without a dot
            # (`Rect`, `isChecked`).  Only the globals chapter (2.16) is a
            # reliable place to read "this global is missing".
            if module_num == '2.16' and kind in ('function', 'constructor') \
                    and all(v['globals'].get(member) == 'MISSING' for v in variants) \
                    and status in ('未定位', '待探针(类成员)', '仅引用'):
                return '实机缺', 'probe: 全局 %s 不存在' % member
            return status, evidence
        if mod in MODULE_NAMES and all(mod in v['missing'] for v in variants):
            if kind != 'label' and status in ('未定位', '待探针(类成员)', '仅引用'):
                return '实机缺', 'probe: %s 模块未挂载' % mod
            if kind != 'label':
                return status, (evidence + ' ; probe: 全局 %s 未挂载' % mod).strip(' ;')
            return status, evidence
        if mod in MODULE_NAMES and any(mod in v['modules'] for v in variants):
            if status not in ('已定义', '已定义(全局)', '回归已覆盖', '样例已用'):
                return '实机未见', 'probe: %s 在，成员表无 %s' % (mod, member)
        return status, evidence

    rows = []
    for e in entries:
        name = e['name']
        if e['kind'] == 'label' and SECTIONTITLE.match(name):
            # a bare section heading that survived the section filter (e.g.
            # "2.36.2.10 相对布局: relative") -- not an API, nothing to scan
            rows.append({
                'module_num': e['module_num'], 'module': e['module'], 'api': name,
                'kind': e['kind'], 'pdf_page': e['page'], 'version': e['version_marker'],
                'section': e['section_num'], 'rhino': '不适用', 'quickjs': '不适用',
                'rhino_evidence': '', 'quickjs_evidence': '',
                'signature': e['signature'], 'note': '章节标题，不是 API',
            })
            continue
        mod, member = name.rsplit('.', 1) if '.' in name else ('globals', name)
        if e['kind'] == 'label':
            # free-form bold labels (event names, option names, execution config
            # members): search inside the JS namespace of the PDF module itself.
            ns = PDF_NS.get(e['module_num'])
            if ns:
                mod = ns
            tokens = re.findall(r'[A-Za-z_$][A-Za-z0-9_$]*', name)
            member = tokens[-1] if tokens else name
        base_mod = mod.lstrip('$')

        defpats = [
            re.compile(r'(?<![\w$])%s\s*\.\s*%s\s*[:=]' % (re.escape(base_mod), re.escape(member))),
            re.compile(r'(?<![\w$])%s\s*[:=]\s*function' % re.escape(member)),
            re.compile(r'public\s+[^;{}\n]*\b%s\s*\(' % re.escape(member)),
            re.compile(r'\bfun\s+%s\s*\(' % re.escape(member)),
            re.compile(r'\bfunction\s+%s\s*\(' % re.escape(member)),
            re.compile(r'(?<![\w$])%s\s*\(\s*\)\s*\{' % re.escape(member)),
        ]
        menpat = re.compile(r'(?<![\w$])%s(?![\w$])' % re.escape(member))

        def scan(rel_list):
            for rel in rel_list:
                text = files.get(rel)
                if text is None:
                    continue
                for n, line in enumerate(text.split('\n'), 1):
                    if any(p.search(line) for p in defpats):
                        return '已定义', '%s:%d' % (rel, n)
                for n, line in enumerate(text.split('\n'), 1):
                    if menpat.search(line):
                        return '同模块', '%s:%d' % (rel, n)
            return '未定位', ''

        r_status, r_ev = scan(rhino_sources(mod))
        if r_status == '未定位':
            g_status, g_ev = scan(global_sources)   # Auto.js also exposes many APIs as globals
            if g_status != '未定位':
                r_status, r_ev = g_status + '(全局)', g_ev
        if r_status == '未定位':
            pat = re.compile(r'(?<![\w$])%s\s*\.\s*%s\b' % (re.escape(base_mod), re.escape(member)))
            for rel, text in files.items():
                hit = pat.search(text)
                if hit:
                    r_status = '仅引用'
                    r_ev = '%s:%d' % (rel, text[:hit.start()].count('\n') + 1)
                    break

        q_status, q_ev = '未定位', ''
        if member in qjs_members.get(base_mod, set()):
            q_status, q_ev = '已定义', 'cpp/quickjs_jni.cpp (bootstrap %s{})' % base_mod
        elif base_mod == 'globals':
            # bare global functions are assigned as `global.x = ...` / `function x(...)`
            for pat in (re.compile(r'\bglobal\s*\.\s*%s\b' % re.escape(member)),
                        re.compile(r'\bfunction\s+%s\s*\(' % re.escape(member)),
                        re.compile(r'\bvar\s+%s\s*=\s*function' % re.escape(member))):
                hit = pat.search(cpp_text)
                if hit:
                    q_status = '已定义'
                    q_ev = 'cpp/quickjs_jni.cpp:%d' % (cpp_text[:hit.start()].count('\n') + 1)
                    break
        if q_status == '未定位':
            pat = re.compile(r'(?<![\w$.])%s\s*\.\s*%s\b' % (re.escape(base_mod), re.escape(member)))
            hit = pat.search(cpp_text)
            if hit:
                q_status, q_ev = '仅引用', 'cpp/quickjs_jni.cpp:%d' % (cpp_text[:hit.start()].count('\n') + 1)
            else:
                hit = pat.search(bridge_text)
                if hit:
                    q_status, q_ev = '仅引用', '%s:%d' % (bridge_rel, bridge_text[:hit.start()].count('\n') + 1)
                else:
                    for tier, rel_list in (('回归已覆盖', qjs_regression), ('样例已用', qjs_samples)):
                        for rel in rel_list:
                            h = pat.search(files[rel])
                            if h:
                                q_status = tier
                                q_ev = '%s:%d' % (rel, files[rel][:h.start()].count('\n') + 1)
                                break
                        if q_status != '未定位':
                            break

        if q_status == '未定位' and (base_mod[:1].isupper() or base_mod not in MODULE_NAMES):
            # class / object members (UiObject.click, window.setSize, Image.getPixel,
            # Tensor.toFloat32, ...) are host Java objects in QuickJS -- a static JS
            # scan cannot see them, so a miss is "needs a device probe", not "not supported".
            q_status = '待探针(类成员)'

        r_status, r_ev = merge_device('rhino', mod, member, r_status, r_ev, e['module_num'], e['kind'])
        q_status, q_ev = merge_device('quickjs', mod, member, q_status, q_ev, e['module_num'], e['kind'])

        rows.append({
            'module_num': e['module_num'], 'module': e['module'], 'api': name, 'kind': e['kind'],
            'pdf_page': e['page'], 'version': e['version_marker'], 'section': e['section_num'],
            'rhino': r_status, 'quickjs': q_status,
            'rhino_evidence': r_ev, 'quickjs_evidence': q_ev,
            'signature': e['signature'], 'note': ', '.join(e['bullets'][:3])[:160],
        })

    io.open(STATUS_JSON, 'w', encoding='utf-8').write(
        json.dumps(rows, ensure_ascii=False, indent=1))

    cols = ['module_num', 'module', 'api', 'kind', 'pdf_page', 'version', 'section',
            'rhino', 'quickjs', 'rhino_evidence', 'quickjs_evidence', 'signature', 'note']
    os.makedirs(os.path.dirname(CSV_PATH), exist_ok=True)
    with io.open(CSV_PATH, 'w', encoding='utf-8-sig', newline='') as fh:
        fh.write(','.join(cols) + '\n')
        for r in rows:
            fh.write(','.join('"%s"' % str(r[c]).replace('"', '""') for c in cols) + '\n')
    print('[check] entries=%d rhino=%s quickjs=%s' % (
        len(rows), dict(Counter(r['rhino'] for r in rows)),
        dict(Counter(r['quickjs'] for r in rows))))
    print('[check] csv -> %s' % CSV_PATH)


# --------------------------------------------------------------------------- 4
R_LOCATED = ('已定义', '已定义(全局)')
R_SAMEFILE = ('同模块', '同模块(全局)')


def stage_rollup():
    rows = json.load(io.open(STATUS_JSON, encoding='utf-8'))

    def bucket(c, prefix, status):
        if status == '实机有':
            c[prefix + 'yes'] += 1
        elif status in ('已定义', '已定义(全局)', '回归已覆盖', '样例已用'):
            c[prefix + 'src'] += 1
        elif status in ('同模块', '同模块(全局)', '仅引用', '实机未见'):
            c[prefix + 'soft'] += 1
        elif status.startswith('待探针'):
            c[prefix + 'probe'] += 1
        elif status == '实机缺':
            c[prefix + 'miss'] += 1
        elif status == '不适用':
            pass
        else:
            c[prefix + 'no'] += 1

    per = defaultdict(Counter)
    for r in rows:
        c = per[(r['module_num'], r['module'])]
        c['n'] += 1
        if r['kind'] in ('function', 'constructor'):
            c['fn'] += 1
        bucket(c, 'r', r['rhino'])
        bucket(c, 'q', r['quickjs'])

    head = ('| 模块 | 条目 | 函数 | Rhino 实机有 | Rhino 源码有 | Rhino 待核实 | Rhino 未定位 | Rhino 实机缺 '
            '| QuickJS 实机有 | QuickJS 源码有 | QuickJS 待探针 | QuickJS 未定位 | QuickJS 实机缺 |')
    out = [head, '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    tot = Counter()

    for (num, mod), c in sorted(per.items(),
                                key=lambda kv: [int(x) for x in kv[0][0].split('.')] if kv[0][0] else [999]):
        out.append('| %s %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d |' % (
            num, mod, c['n'], c['fn'], c['ryes'], c['rsrc'], c['rsoft'], c['rno'], c['rmiss'],
            c['qyes'], c['qsrc'], c['qprobe'], c['qno'], c['qmiss']))
        tot.update(c)
    out.append('| **合计** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** |' % (
        tot['n'], tot['fn'], tot['ryes'], tot['rsrc'], tot['rsoft'], tot['rno'], tot['rmiss'],
        tot['qyes'], tot['qsrc'], tot['qprobe'], tot['qno'], tot['qmiss']))
    io.open(ROLLUP_MD, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('[rollup] -> %s' % ROLLUP_MD)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pdf', default=DEFAULT_PDF)
    ap.add_argument('--stage', action='append', choices=['extract', 'index', 'check', 'rollup'])
    args = ap.parse_args()
    stages = args.stage or ['extract', 'index', 'check', 'rollup']
    if 'extract' in stages:
        stage_extract(args.pdf)
    if 'index' in stages:
        stage_index()
    if 'check' in stages:
        stage_check()
    if 'rollup' in stages:
        stage_rollup()


if __name__ == '__main__':
    main()
