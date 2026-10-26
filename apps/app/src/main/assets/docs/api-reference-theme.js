(function () {
    'use strict';
    window.Prism = {manual: true, disableWorkerMessageHandler: true};
    const params = new URLSearchParams(location.search), media = window.matchMedia('(prefers-color-scheme: dark)');
    let explicit = params.get('theme');
    function luminance(rgb) {
        const channels = rgb.map(x => x / 255).map(x => x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4));
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
    }
    function readableAccent(hex, dark) {
        const rgb = hex.match(/\w\w/g).map(x => parseInt(x, 16)), background = dark ? [36, 42, 45] : [240, 244, 244];
        const bg = luminance(background), target = dark ? 255 : 0;
        for (let i = 0; i < 32; i++) {
            const fg = luminance(rgb);
            if ((Math.max(bg, fg) + .05) / (Math.min(bg, fg) + .05) >= 4.5) break;
            for (let c = 0; c < 3; c++) rgb[c] = Math.round(rgb[c] * .9 + target * .1);
        }
        return '#' + rgb.map(x => x.toString(16).padStart(2, '0')).join('');
    }
    window.AIJS_DOCS_SET_APPEARANCE = appearance => {
        const theme = appearance.theme === 'dark' ? 'dark' : 'light';
        document.documentElement.dataset.theme = theme;
        const accent = appearance.accent;
        if (/^#[0-9a-f]{6}$/i.test(accent || '')) {
            document.documentElement.style.setProperty('--accent', readableAccent(accent.slice(1), theme === 'dark'));
            document.documentElement.dataset.accent = accent;
        }
        if (window.AIJS_DOCS_NATIVE === true) {
            explicit = theme;
            // Keep reload/history restoration in the current native theme without a color flash.
            try {
                const url = new URL(location.href); url.searchParams.set('theme', theme);
                if (/^#[0-9a-f]{6}$/i.test(accent || '')) url.searchParams.set('accent', accent);
                window.history.replaceState(window.history.state, '', url.href);
            } catch (_) { /* Color synchronization still works in restricted viewers. */ }
        }
    };
    function apply() {
        window.AIJS_DOCS_SET_APPEARANCE({theme: explicit === 'dark' || explicit === 'light' ? explicit : (media.matches ? 'dark' : 'light'), accent: params.get('accent')});
    }
    apply();
    if (media.addEventListener) media.addEventListener('change', () => {if (!explicit) apply();});
})();
