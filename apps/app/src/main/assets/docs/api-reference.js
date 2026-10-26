(function () {
    'use strict';
    const data = window.AIJS_API_REFERENCE;
    const search = document.getElementById('search'), category = document.getElementById('category');
    const results = document.getElementById('results'), status = document.getElementById('status');
    const empty = document.getElementById('empty'), more = document.getElementById('more');
    const filters = document.getElementById('filters'), filterToggle = document.getElementById('filter-toggle');
    const notice = document.getElementById('notice');
    const buttons = Array.from(document.querySelectorAll('[data-engine]'));
    let noticeTimer;
    function tell(message) {
        clearTimeout(noticeTimer); notice.textContent = message; notice.hidden = false;
        noticeTimer = setTimeout(() => {notice.hidden = true;}, 2600);
    }
    function copySelection(text) {
        const selection = window.getSelection(), ranges = [];
        if (selection) for (let i = 0; i < selection.rangeCount; i++) ranges.push(selection.getRangeAt(i).cloneRange());
        const active = document.activeElement, x = window.scrollX, y = window.scrollY;
        const field = document.createElement('textarea'); field.value = text; field.className = 'copy-buffer';
        field.readOnly = true; document.body.appendChild(field); field.select();
        let copied = false;
        try {copied = document.execCommand('copy');} catch (_) { /* Use the clipboard fallback below. */ }
        field.remove();
        if (active && typeof active.focus === 'function') active.focus({preventScroll: true});
        if (selection) {selection.removeAllRanges(); ranges.forEach(range => selection.addRange(range));}
        window.scrollTo(x, y); return copied;
    }
    async function copyCode(text) {
        if (window.AIJS_DOCS_NATIVE === true) {
            const reply = window.prompt('aijs-docs:copy', text);
            if (reply === 'copied') return true;
            if (reply === 'too_large') {tell('这段代码较长，请长按分段复制'); return false;}
        }
        if (copySelection(text)) return true;
        try {if (navigator.clipboard) {await navigator.clipboard.writeText(text); return true;}} catch (_) { /* Keep the code selectable. */ }
        tell('复制未成功，请长按代码选择复制'); return false;
    }
    function addCodeTools(body) {
        body.querySelectorAll('pre').forEach(pre => {
            const text = (pre.querySelector('code') || pre).textContent;
            if (!text.trim()) return;
            const block = document.createElement('div'); block.className = 'code-block';
            const tools = document.createElement('div'); tools.className = 'code-tools';
            const label = document.createElement('span'); label.textContent = '示例代码';
            const originalCode = pre.querySelector('code');
            const declared = originalCode && ((originalCode.className.match(/(?:language|lang)-([\w-]+)/) || [])[1]);
            const rendered = window.AIJS_DOCS_CODE && window.AIJS_DOCS_CODE.render(text, declared);
            const code = document.createElement('code');
            if (rendered) {
                code.innerHTML = rendered.html;
                // Text fidelity always takes priority over colored decoration.
                if (code.textContent !== text) code.textContent = text;
                label.textContent = rendered.label + ' · ' + text.split('\n').length + ' 行';
                code.className = 'language-' + rendered.language;
            } else code.textContent = text;
            pre.replaceChildren(code);
            const actions = document.createElement('div'); actions.className = 'code-actions';
            const wrap = document.createElement('button'); wrap.type = 'button'; wrap.className = 'wrap-code';
            wrap.textContent = '换行'; wrap.setAttribute('aria-pressed', String(wrapCodes)); wrap.setAttribute('aria-label', '切换代码自动换行');
            pre.classList.toggle('no-wrap', !wrapCodes);
            wrap.addEventListener('click', () => {
                wrapCodes = !wrapCodes;
                results.querySelectorAll('.wrap-code').forEach(b => b.setAttribute('aria-pressed', String(wrapCodes)));
                results.querySelectorAll('.code-block pre').forEach(p => p.classList.toggle('no-wrap', !wrapCodes));
                try {window.history.replaceState(Object.assign({}, window.history.state, {wrapCodes}), '');} catch (_) { /* Optional viewer state. */ }
            });
            const button = document.createElement('button'); button.type = 'button';
            button.className = 'copy-code'; button.textContent = '复制代码';
            button.addEventListener('click', async () => {
                button.disabled = true;
                try {
                    if (await copyCode(text)) {
                        button.textContent = '已复制 ✓'; button.classList.add('copied');
                        setTimeout(() => {button.textContent = '复制代码'; button.classList.remove('copied');}, 1800);
                    }
                } finally {button.disabled = false;}
            });
            actions.append(wrap, button); tools.append(label, actions); pre.replaceWith(block); block.append(tools, pre);
        });
    }
    function setFilters(open) {
        filters.hidden = !open; filterToggle.setAttribute('aria-expanded', String(open));
    }
    filterToggle.addEventListener('click', () => setFilters(filters.hidden));
    document.getElementById('back').addEventListener('click', () => {
        if (window.AIJS_DOCS_NATIVE === true) location.href = 'aijs-docs://back';
        else if (history.length > 1) history.back();
    });
    document.getElementById('collapse-all').addEventListener('click', () => {
        results.querySelectorAll('details[open]').forEach(detail => {detail.open = false;});
    });
    if (!data || !Array.isArray(data.records)) {
        status.textContent = '本地文档未完整加载，请返回后重试。'; return;
    }
    const labels = {rhino: 'Rhino', quickjs: 'QuickJS', nodejs: 'Node.js'};
    let saved = {};
    try {saved = window.history.state || {};} catch (_) { /* Older viewers keep the default filters. */ }
    let wrapCodes = saved.wrapCodes !== false;
    let engine = ['all', 'rhino', 'quickjs', 'nodejs'].includes(saved.engine) ? saved.engine : 'all';
    let pageSize = 20, visible = Math.max(20, Math.min(1000, Number(saved.visible) || 20)), timer;
    search.value = typeof saved.query === 'string' ? saved.query : '';
    Array.from(new Set(data.records.map(r => r.category))).forEach(name => {
        const option = document.createElement('option'); option.value = name; option.textContent = name; category.appendChild(option);
    });
    if (Array.from(category.options).some(o => o.value === saved.category)) category.value = saved.category;
    buttons.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.engine === engine)));
    // Reuse formatted bodies across searches/pagination. Bound retained DOM so
    // browsing the whole reference does not retain every highlighted example.
    const contentCache = new Map();
    function render(resetScroll = false) {
        const firstRender = results.childElementCount === 0;
        const expanded = new Set(Array.from(results.querySelectorAll('details[open]'), d => d.dataset.id));
        const query = search.value.trim().toLocaleLowerCase();
        const words = query.split(/\s+/).filter(Boolean);
        const matches = data.records.filter(row => (engine === 'all' || row.engines.includes(engine))
            && (!category.value || row.category === category.value) && words.every(w => row.search.includes(w)));
        // A method-name hit precedes incidental mentions in a larger chapter.
        if (query) matches.sort((a, b) => Number(b.title.toLocaleLowerCase().includes(query)) - Number(a.title.toLocaleLowerCase().includes(query))
            || Number(b.category === 'API 详解与实践') - Number(a.category === 'API 详解与实践'));
        results.replaceChildren();
        matches.slice(0, visible).forEach((row, index) => {
            const article = document.createElement('article'), detail = document.createElement('details');
            detail.dataset.id = row.id;
            const summary = document.createElement('summary'), title = document.createElement('span');
            title.className = 'title'; title.textContent = row.title.replace(/`([^`]+)`/g, '$1'); summary.appendChild(title);
            const badge = document.createElement('span'); badge.className = 'badge';
            badge.textContent = row.kind === 'legacy' ? 'Rhino · 传统 API 参考' : row.category + ' · ' + row.engines.map(x => labels[x]).join(' / ');
            if (row.examples && row.examples.length) badge.textContent += ' · ' + row.examples.length + ' 个示例';
            summary.appendChild(badge);
            const preview = document.createElement('span'); preview.className = 'preview';
            preview.textContent = row.text.slice(0, 90) + '…';
            summary.appendChild(preview); detail.appendChild(summary);
            let loaded = false;
            function load() {
                if (loaded) return; loaded = true;
                const cached = contentCache.get(row.id);
                if (cached) {
                    contentCache.delete(row.id); contentCache.set(row.id, cached);
                    cached.querySelectorAll('pre').forEach(pre => pre.classList.toggle('no-wrap', !wrapCodes));
                    cached.querySelectorAll('.wrap-code').forEach(button => button.setAttribute('aria-pressed', String(wrapCodes)));
                    detail.appendChild(cached); return;
                }
                const body = document.createElement('div'); body.className = 'content';
                // The build step sanitizes legacy HTML and escapes Markdown. There is no user HTML input.
                body.innerHTML = row.html;
                const headings = Array.from(body.querySelectorAll('h2,h3,h4'));
                if (headings.length > 1) {
                    const outline = document.createElement('div'); outline.className = 'section-links'; outline.setAttribute('aria-label', '本条目录');
                    headings.forEach((heading, i) => {
                        heading.id = row.id + '-section-' + i;
                        const button = document.createElement('button'); button.type = 'button'; button.textContent = heading.textContent;
                        button.addEventListener('click', () => heading.scrollIntoView({block: 'start', behavior: 'smooth'})); outline.appendChild(button);
                    });
                    body.prepend(outline);
                }
                addCodeTools(body);
                if (row.link) {const link = document.createElement('a'); link.className = 'chapter'; link.href = row.link; link.textContent = '查看完整章节'; body.appendChild(link);}
                detail.appendChild(body);
                contentCache.set(row.id, body);
                if (contentCache.size > 32) contentCache.delete(contentCache.keys().next().value);
            }
            detail.addEventListener('toggle', () => {if (detail.open) load();});
            if ((query && index === 0 && (resetScroll || firstRender)) || (!resetScroll && expanded.has(row.id))) {
                load(); detail.open = true;
            }
            article.appendChild(detail); results.appendChild(article);
        });
        empty.hidden = matches.length !== 0; more.hidden = matches.length <= visible;
        status.textContent = (labels[engine] || '全部') + ' · ' + matches.length + ' 条'
            + (matches.length > visible ? '，已显示 ' + visible + ' 条' : '');
        filterToggle.classList.toggle('active', Boolean(category.value));
        try {window.history.replaceState({engine, query: search.value, category: category.value, visible, wrapCodes}, '');}
        catch (_) { /* Searching still works when a viewer disallows history state. */ }
        if (resetScroll) window.scrollTo(0, 0);
    }
    search.addEventListener('input', () => {clearTimeout(timer); visible = pageSize; timer = setTimeout(() => render(true), 100);});
    category.addEventListener('change', () => {visible = pageSize; render(true); setFilters(false);});
    buttons.forEach(button => button.addEventListener('click', () => {
        engine = button.dataset.engine; visible = pageSize;
        if (category.value && !data.records.some(r => r.category === category.value && (engine === 'all' || r.engines.includes(engine)))) category.value = '';
        buttons.forEach(b => b.setAttribute('aria-pressed', String(b === button))); render(true);
    }));
    more.addEventListener('click', () => {visible += pageSize; render();});
    function measureHeader() {document.documentElement.style.setProperty('--doc-header-height', document.querySelector('header').offsetHeight + 'px');}
    if (window.ResizeObserver) new ResizeObserver(measureHeader).observe(document.querySelector('header'));
    measureHeader();
    render();
})();
