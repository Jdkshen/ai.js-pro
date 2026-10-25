(function () {
    'use strict';
    const data = window.AIJS_API_REFERENCE;
    const search = document.getElementById('search'), category = document.getElementById('category');
    const results = document.getElementById('results'), status = document.getElementById('status');
    const empty = document.getElementById('empty'), more = document.getElementById('more');
    const buttons = Array.from(document.querySelectorAll('[data-engine]'));
    if (!data || !Array.isArray(data.records)) {
        status.textContent = '本地文档未完整加载，请返回后重试。'; return;
    }
    const labels = {rhino: 'Rhino', quickjs: 'QuickJS', nodejs: 'Node.js'};
    let saved = {};
    try {saved = window.history.state || {};} catch (_) { /* Older viewers keep the default filters. */ }
    let engine = ['all', 'rhino', 'quickjs', 'nodejs'].includes(saved.engine) ? saved.engine : 'all';
    let pageSize = 20, visible = Math.max(20, Math.min(1000, Number(saved.visible) || 20)), timer;
    search.value = typeof saved.query === 'string' ? saved.query : '';
    Array.from(new Set(data.records.map(r => r.category))).forEach(name => {
        const option = document.createElement('option'); option.value = name; option.textContent = name; category.appendChild(option);
    });
    if (Array.from(category.options).some(o => o.value === saved.category)) category.value = saved.category;
    buttons.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.engine === engine)));
    function render() {
        const query = search.value.trim().toLocaleLowerCase();
        const words = query.split(/\s+/).filter(Boolean);
        const matches = data.records.filter(row => (engine === 'all' || row.engines.includes(engine))
            && (!category.value || row.category === category.value) && words.every(w => row.search.includes(w)));
        // A method-name hit precedes incidental mentions in a larger chapter.
        if (query) matches.sort((a, b) => Number(b.title.toLocaleLowerCase().includes(query)) - Number(a.title.toLocaleLowerCase().includes(query)));
        results.replaceChildren();
        matches.slice(0, visible).forEach((row, index) => {
            const article = document.createElement('article'), detail = document.createElement('details');
            const summary = document.createElement('summary'), title = document.createElement('span');
            title.className = 'title'; title.textContent = row.title; summary.appendChild(title);
            const badge = document.createElement('span'); badge.className = 'badge';
            badge.textContent = row.kind === 'legacy' ? 'Rhino · 传统 API 参考' : row.category;
            summary.appendChild(badge);
            const preview = document.createElement('span'); preview.className = 'preview';
            preview.textContent = row.text.slice(0, 120) + '…';
            summary.appendChild(preview); detail.appendChild(summary);
            let loaded = false;
            function load() {
                if (loaded) return; loaded = true;
                const body = document.createElement('div'); body.className = 'content';
                // The build step sanitizes legacy HTML and escapes Markdown. There is no user HTML input.
                body.innerHTML = row.html;
                if (row.link) {const link = document.createElement('a'); link.className = 'chapter'; link.href = row.link; link.textContent = '查看完整章节'; body.appendChild(link);}
                detail.appendChild(body);
            }
            detail.addEventListener('toggle', () => {if (detail.open) load();});
            if (query && index < 2) {load(); detail.open = true;}
            article.appendChild(detail); results.appendChild(article);
        });
        empty.hidden = matches.length !== 0; more.hidden = matches.length <= visible;
        status.textContent = (labels[engine] || '全部引擎') + ' · ' + matches.length + ' 条参考条目'
            + (matches.length > visible ? '，已显示 ' + visible + ' 条' : '') + ' · 无需联网';
        try {window.history.replaceState({engine, query: search.value, category: category.value, visible}, '');}
        catch (_) { /* Searching still works when a viewer disallows history state. */ }
    }
    search.addEventListener('input', () => {clearTimeout(timer); visible = pageSize; timer = setTimeout(render, 100);});
    category.addEventListener('change', () => {visible = pageSize; render();});
    buttons.forEach(button => button.addEventListener('click', () => {
        engine = button.dataset.engine; visible = pageSize;
        buttons.forEach(b => b.setAttribute('aria-pressed', String(b === button))); render();
    }));
    more.addEventListener('click', () => {visible += pageSize; render();});
    render();
})();
