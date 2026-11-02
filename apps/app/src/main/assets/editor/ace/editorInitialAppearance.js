/* Apply the native preference before body/Ace can produce an unthemed frame. */
(function () {
    'use strict';
    const params = new URLSearchParams(location.search);
    const theme = params.get('theme') === 'textmate' ? 'textmate' : 'tomorrow_night';
    const color = params.get('background');
    const background = /^#[0-9a-f]{6}$/i.test(color || '')
        ? color : theme === 'textmate' ? '#ffffff' : '#1d1f21';
    window.EditorInitialAppearance = {theme: theme, background: background};
    const style = document.documentElement.style;
    style.backgroundColor = background;
    style.colorScheme = theme === 'textmate' ? 'light' : 'dark';
    style.setProperty('--editor-initial-background', background);
})();
