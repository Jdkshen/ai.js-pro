/* Shared editor appearance. The native host owns the saved application theme. */
window.installEditorAppearance = function (editor) {
    if (editor.$appearance) return editor.$appearance;
    function updateSurface() {
        const dark=editor.container.classList.contains('ace_dark');
        document.documentElement.style.colorScheme=dark?'dark':'light';
        document.body.style.backgroundColor=getComputedStyle(editor.container).backgroundColor;
        document.body.style.color=getComputedStyle(editor.container).color;
    }
    const appearance={setDark:function(dark){
        editor.setTheme('ace/theme/'+(dark?'tomorrow_night':'textmate'));
    },status:function(){return {theme:editor.getTheme(),
        dark:editor.container.classList.contains('ace_dark'),
        background:getComputedStyle(editor.container).backgroundColor};}};
    editor.on('changeTheme',updateSurface);
    editor.on('destroy',()=>editor.off('changeTheme',updateSurface));
    editor.$appearance=appearance;
    window.EditorAppearance=appearance;
    try {
        if(window.EditorAppearanceBridge)appearance.setDark(EditorAppearanceBridge.isDark());
    } catch(error) { console.warn('Editor appearance preference unavailable',error); }
    updateSurface();
    return appearance;
};
