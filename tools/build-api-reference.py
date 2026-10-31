#!/usr/bin/env python3
"""Build the offline API search index from maintained guides and bundled legacy docs.

No declaration inventory is treated as runtime support. No network or third-party packages.
Run with --check in CI to detect stale generated assets.
"""
import argparse
import hashlib
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'apps/app/src/main/assets/docs'
GUIDES = [
    ('docs/guides/API_REFERENCE.md', ['rhino', 'quickjs', 'nodejs'], '三引擎使用手册'),
    ('docs/guides/NODE_UI_API.md', ['nodejs'], 'Node UI 与 Java 资源'),
    ('docs/guides/Node24基础自动化API.md', ['nodejs'], 'Node 自动化与图色'),
    ('docs/guides/Node24控件选择器API.md', ['nodejs'], 'Node 控件选择器'),
    ('docs/guides/API_DETAILS.md', ['rhino', 'quickjs', 'nodejs'], 'API 详解与实践'),
]
LEGACY = ['app', 'console', 'coordinates-based-automation', 'device', 'dialogs', 'engines',
          'events', 'floaty', 'files', 'globals', 'http', 'images', 'keys', 'media',
          'modules', 'widgets-based-automation', 'sensors', 'shell', 'storages',
          'threads', 'timers', 'ui', 'io', 'plugins', 'canvas']
GUIDE_ENGINE_OVERRIDES = {
    '4.1 dev-1294：Node 快照完整性、分页与跨布局身份': ['nodejs'],
    '9.1 Node UI 已知问题状态（dev-1295 文档线，2026-10-31）': ['nodejs'],
    '6.1 Rhino / QuickJS 传统 UI': ['rhino', 'quickjs'],
    '6.2 Node `ui.Activity`': ['nodejs'],
    '6.3 Node `ctx.loadResources` 与 `recreate`': ['nodejs'],
    '6.4 Node 浮窗与 Java 桥': ['nodejs'],
}


def inline(text):
    # Protect code spans before processing links/emphasis (types and signatures contain punctuation).
    spans = []
    def code(match):
        spans.append('<code>' + html.escape(match[1]) + '</code>')
        return '\x00' + str(len(spans) - 1) + '\x00'
    value = re.sub(r'`([^`]+)`', code, text)
    value = html.escape(value)
    value = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', value)
    # Repository paths are shown as text; only existing bundled documents get local links.
    value = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1 (<code>\2</code>)', value)
    return re.sub(r'\x00(\d+)\x00', lambda m: spans[int(m[1])], value)


def table_cells(line):
    cells, current, quoted, escaped = [], '', False, False
    for char in line.strip().strip('|'):
        if escaped:
            current += char; escaped = False
        elif char == '\\':
            escaped = True
        elif char == '`':
            quoted = not quoted; current += char
        elif char == '|' and not quoted:
            cells.append(current.strip()); current = ''
        else:
            current += char
    cells.append(current.strip())
    return cells


def markdown(lines):
    out, paragraph, table, items = [], [], [], []
    def flush():
        if paragraph:
            out.append('<p>' + inline(' '.join(paragraph)) + '</p>'); paragraph.clear()
        if items:
            out.append('<ul>' + ''.join('<li>' + inline(x) + '</li>' for x in items) + '</ul>'); items.clear()
        if table:
            rows = [table_cells(x) for x in table]
            rows = [r for r in rows if not all(re.fullmatch(r'[:\-\s]+', c) for c in r)]
            out.append('<div class="table-scroll"><table>' + ''.join('<tr>' + ''.join(
                ('<th>' if i == 0 else '<td>') + inline(c) + ('</th>' if i == 0 else '</td>') for c in r)
                + '</tr>' for i, r in enumerate(rows)) + '</table></div>'); table.clear()
    fence = None; code = []
    for line in lines:
        if line.lstrip().startswith('```'):
            flush()
            if fence is None:
                fence = line.strip()[3:]; code = []
            else:
                language = re.sub(r'[^a-z0-9_-]', '', fence.lower().split(' ')[0])
                attr = ' class="language-' + language + '"' if language else ''
                out.append('<pre><code' + attr + '>' + html.escape('\n'.join(code)) + '</code></pre>'); fence = None
            continue
        if fence is not None:
            code.append(line); continue
        if not line.strip(): flush()
        elif re.match(r'^#{1,6}\s+', line):
            flush(); heading = re.match(r'^(#{1,6})\s+(.+)', line)
            level = min(len(heading[1]), 4)
            out.append('<h' + str(level) + '>' + inline(heading[2]) + '</h' + str(level) + '>')
        elif line.lstrip().startswith('|'):
            if paragraph or items: flush()
            table.append(line)
        elif re.match(r'^\s*[-*]\s|^\s*\d+[.)]\s', line):
            if paragraph or table: flush()
            items.append(re.sub(r'^\s*(?:[-*]|\d+[.)])\s+', '', line))
        elif line.startswith('>'):
            flush(); out.append('<p class="note">' + inline(line.lstrip('> ')) + '</p>')
        else:
            if table or items: flush()
            paragraph.append(line.strip())
    if fence is not None: raise ValueError('Unclosed Markdown code fence')
    flush()
    return ''.join(out)


class Text(HTMLParser):
    def __init__(self): super().__init__(); self.parts = []
    def handle_data(self, data): self.parts.append(data)


def plain(fragment):
    parser = Text(); parser.feed(fragment)
    return ' '.join(' '.join(parser.parts).split())


class ReadableMarkdown(HTMLParser):
    """Readable legacy reference for MCP; preserve pre text byte-for-byte as Unicode."""
    def __init__(self):
        super().__init__(); self.parts = []; self.code = None
    def handle_starttag(self, tag, attrs):
        if tag == 'pre': self.code = []; return
        if self.code is not None: return
        if tag in ['p', 'div', 'table', 'ul', 'ol', 'blockquote']: self.parts.append('\n\n')
        elif tag == 'li': self.parts.append('\n- ')
        elif tag in ['br', 'tr', 'dt', 'dd']: self.parts.append('\n')
        elif tag == 'code': self.parts.append('`')
    def handle_endtag(self, tag):
        if tag == 'pre' and self.code is not None:
            code = ''.join(self.code)
            fence = '`' * max(3, max((len(x) + 1 for x in re.findall(r'`+', code)), default=3))
            self.parts.append('\n\n' + fence + '\n' + code + ('' if code.endswith('\n') else '\n') + fence + '\n\n')
            self.code = None; return
        if self.code is not None: return
        if tag in ['p', 'div', 'table', 'ul', 'ol', 'blockquote']: self.parts.append('\n\n')
        elif tag in ['td', 'th']: self.parts.append(' | ')
        elif tag == 'code': self.parts.append('`')
    def handle_data(self, data):
        if self.code is not None: self.code.append(data)
        else: self.parts.append(re.sub(r'\s+', ' ', data))


def readable_markdown(fragment):
    parser = ReadableMarkdown(); parser.feed(fragment)
    return ''.join(parser.parts).strip()


def language_name(value, code):
    alias = {'js': 'javascript', 'html': 'markup', 'xml': 'markup', 'sh': 'bash', 'shell': 'bash', 'text': 'plain', 'txt': 'plain'}
    if value: return alias.get(value, value)
    if code.lstrip().startswith('<'): return 'markup'
    if code.lstrip().startswith(('{', '[')):
        try: json.loads(code); return 'json'
        except ValueError: pass
    return 'javascript'


class CodeExamples(HTMLParser):
    def __init__(self):
        super().__init__(); self.code = None; self.language = ''; self.examples = []
    def handle_starttag(self, tag, attrs):
        if tag == 'pre': self.code = []; self.language = ''
        if self.code is not None:
            match = re.search(r'(?:language|lang)-([a-z0-9_-]+)', dict(attrs).get('class', ''), re.I)
            if match: self.language = match[1].lower()
    def handle_data(self, data):
        if self.code is not None: self.code.append(data)
    def handle_endtag(self, tag):
        if tag == 'pre' and self.code is not None:
            code = ''.join(self.code)
            if code.strip(): self.examples.append({'language': language_name(self.language, code), 'code': code})
            self.code = None


def code_examples(fragment):
    parser = CodeExamples(); parser.feed(fragment); return parser.examples


def detailed_sections(lines):
    sections, title, body, fenced = [], None, [], False
    def flush():
        if title is not None and '\n'.join(body).strip(): sections.append({'title': title, 'content': '\n'.join(body).strip()})
    for line in lines:
        if line.lstrip().startswith('```'): fenced = not fenced
        heading = None if fenced else re.match(r'^###\s+(.+)', line)
        if heading: flush(); title = heading[1]; body = []
        else: body.append(line)
    flush(); return sections


class LegacySections(HTMLParser):
    ALLOWED = {'p', 'code', 'pre', 'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'td', 'th',
               'em', 'strong', 'b', 'i', 'blockquote', 'br', 'a', 'div', 'span', 'dl', 'dt', 'dd'}
    def __init__(self, filename):
        super().__init__(convert_charrefs=False)
        self.filename = filename; self.rows = []; self.title = []; self.body = []
        self.heading = None; self.anchor = ''; self.active = False; self.skip = 0
    def flush(self):
        title = ' '.join(''.join(self.title).replace('#', '').split())
        if title and title not in ['目录', 'Table of Contents'] and self.body:
            self.rows.append((title, ''.join(self.body), self.anchor))
        self.title = []; self.body = []; self.anchor = ''; self.active = False
    def handle_starttag(self, tag, attrs):
        if tag in ['script', 'style', 'nav']:
            self.skip += 1; return
        if self.skip: return
        attrs = dict(attrs)
        if re.fullmatch(r'h[1-4]', tag):
            self.flush(); self.heading = tag; self.active = True
            self.anchor = attrs.get('id', ''); return
        if self.heading:
            if attrs.get('id'): self.anchor = attrs['id']
            return
        if not self.active or tag not in self.ALLOWED: return
        rendered = ''
        if tag in ['pre', 'code']:
            language = re.search(r'(?:language|lang)-([a-z0-9_-]+)', attrs.get('class', ''), re.I)
            if language: rendered = ' class="language-' + language[1].lower() + '"'
        if tag == 'a':
            href = attrs.get('href', '')
            if href.startswith('#'): href = self.filename + href
            if href and not href.startswith(('javascript:', 'data:', 'file:', '//')):
                rendered = ' href="' + html.escape(href, quote=True) + '"'
        self.body.append('<' + tag + rendered + '>')
    def handle_endtag(self, tag):
        if tag in ['script', 'style', 'nav']:
            self.skip = max(0, self.skip - 1); return
        if self.skip: return
        if tag == self.heading: self.heading = None; return
        if self.active and not self.heading and tag in self.ALLOWED and tag != 'br':
            self.body.append('</' + tag + '>')
    def handle_data(self, data):
        if self.skip: return
        if self.heading: self.title.append(data)
        elif self.active: self.body.append(html.escape(data))
    def handle_entityref(self, name): self.handle_data(html.unescape('&' + name + ';'))
    def handle_charref(self, name): self.handle_data(html.unescape('&#' + name + ';'))


def build():
    records, sources = [], []
    for file, engines, category in GUIDES:
        text = (ROOT / file).read_text(encoding='utf-8-sig')
        sources.append({'path': file, 'sha256': hashlib.sha256(text.encode()).hexdigest()})
        title, lines, fence = category, [], False
        def flush_guide():
            body = markdown(lines)
            section_engines = GUIDE_ENGINE_OVERRIDES.get(title, engines) if file == 'docs/guides/API_REFERENCE.md' else engines
            if file == 'docs/guides/API_DETAILS.md' and title.startswith('Node '): section_engines = ['nodejs']
            if plain(body): records.append({'title': title, 'html': body, 'engines': section_engines,
                'category': category, 'kind': 'guide', 'source': file, 'link': None,
                'content': '\n'.join(lines).strip(), 'sections': detailed_sections(lines)})
        for line in text.splitlines():
            if line.lstrip().startswith('```'): fence = not fence
            heading = None if fence else re.match(r'^#{1,2}\s+(.+)' if file == 'docs/guides/API_DETAILS.md' else r'^#{1,4}\s+(.+)', line)
            if heading:
                flush_guide(); title = heading[1]; lines = []
            else: lines.append(line)
        flush_guide()
    for name in LEGACY:
        file = ASSETS / (name + '.html')
        text = file.read_text(encoding='utf-8-sig')
        sources.append({'path': file.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(text.encode()).hexdigest()})
        parser = LegacySections(file.name); parser.feed(text); parser.flush()
        for title, body, anchor in parser.rows:
            if len(plain(body)) < 8 or title in ['AI.js Pro 4.1.0 文档']: continue
            records.append({'title': title, 'html': body, 'engines': ['rhino'], 'category': '传统 API · ' + name,
                'kind': 'legacy', 'source': file.relative_to(ROOT).as_posix(),
                'link': file.name + ('#' + anchor if anchor else ''), 'content': readable_markdown(body)})
    for index, record in enumerate(records):
        record['id'] = 'api-' + str(index + 1)
        record['text'] = plain(record['html'])
        record['search'] = (record['title'] + ' ' + record['category'] + ' ' + record['text']).lower()
        record.setdefault('sections', [])
        record['examples'] = code_examples(record['html'])
    result = {'schemaVersion': 3, 'sources': sources, 'records': records,
        'scope': '现行三引擎指南与 Node 专题；传统 API 原文供 Rhino 查询，不能据此推断 QuickJS/Node 全量支持。'}
    return 'window.AIJS_API_REFERENCE=' + json.dumps(result, ensure_ascii=False, separators=(',', ':')) + ';\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args(); result = build(); target = ASSETS / 'api-reference-data.js'
    if args.check:
        if not target.is_file() or target.read_text(encoding='utf-8') != result:
            raise SystemExit('API reference assets are stale; run python tools/build-api-reference.py')
    else: target.write_text(result, encoding='utf-8')
    data = json.loads(result[len('window.AIJS_API_REFERENCE='):-2])
    print(json.dumps({'records': len(data['records']), 'sources': len(data['sources']), 'bytes': len(result.encode()), 'checked': args.check}))


if __name__ == '__main__': main()
