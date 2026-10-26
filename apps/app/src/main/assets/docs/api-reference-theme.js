(function () {
    'use strict';
    const theme = new URLSearchParams(location.search).get('theme');
    document.documentElement.dataset.theme = theme === 'light' || theme === 'dark' ? theme
        : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
})();
