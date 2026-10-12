/* Keep the WebView page scale at one. Composite a pinch preview, then commit
 * Ace's font once on release; restore geometry before accepting normal taps. */
window.installEditorPinch = function (editor, changed) {
    let gesture = null, previousRange = null, frame = null, destroyed = false;
    const distance = t => Math.hypot(t[0].clientX-t[1].clientX,t[0].clientY-t[1].clientY);
    function setSize(size) {
        size=Math.max(8, Math.min(40, size));
        if(parseFloat(editor.getFontSize())===size)return false;
        // Ace's font option already measures glyphs, updates character metrics
        // and resizes the renderer. Repeating those operations forces a second
        // complete render (and a second whole-document wrap calculation).
        editor.setFontSize(size);
        return true;
    }
    function cancel() {
        if (frame !== null) cancelAnimationFrame(frame);
        const preview=gesture && gesture.preview;
        if (preview) restorePreview(preview);
        frame = null; gesture = null; previousRange = null;
        if (preview && !destroyed) editor.resize(true);
    }
    function restorePreview(preview) {
        Object.keys(preview.styles).forEach(key=>editor.container.style[key]=preview.styles[key]);
        document.body.style.backgroundColor=preview.background;
    }
    function beginPreview() {
        const style=editor.container.style, rect=editor.container.getBoundingClientRect();
        const styles={};
        ['transform','transformOrigin','willChange'].forEach(key=>styles[key]=style[key]);
        gesture.preview={styles:styles,background:document.body.style.backgroundColor};
        document.body.style.backgroundColor=getComputedStyle(editor.container).backgroundColor;
        // Keep the existing viewport and texture size during the gesture.
        // Enlarging it to preload shrinking rows stalls low-memory WebViews.
        // Newly exposed rows and line wrapping are filled on release instead.
        style.transformOrigin=(gesture.x-rect.left)+'px '+(gesture.y-rect.top)+'px';
        style.willChange='transform';
    }
    function applyGesture() {
        frame = null;
        if (destroyed || !gesture || gesture.session !== editor.session) return;
        const original=gesture.preview.styles.transform;
        editor.container.style.transform=(original ? original+' ' : '')+'scale('+gesture.nextSize/gesture.size+')';
    }
    function stop(event) { event.preventDefault(); event.stopImmediatePropagation(); }
    function start(event) {
        if (!editor.container.contains(event.target)) return;
        if (event.touches.length === 1 && !gesture) previousRange = editor.getSelectionRange().clone();
        if (event.touches.length < 2) { if (gesture) stop(event); return; }
        // Let Ace see the second touchstart so it cancels its first finger's
        // long-press timer. Its zoom movement is intercepted below.
        event.preventDefault();
        if (!gesture) {
            const t=event.touches, x=(t[0].clientX+t[1].clientX)/2, y=(t[0].clientY+t[1].clientY)/2;
            gesture={distance:Math.max(1,distance(t)),size:parseFloat(editor.getFontSize()),
                nextSize:parseFloat(editor.getFontSize()),session:editor.session,
                anchor:editor.renderer.screenToTextCoordinates(x,y), x:x,y:y,
                range:previousRange || editor.getSelectionRange().clone()};
            if (editor.completer) editor.completer.detach();
            const position=editor.renderer.textToScreenCoordinates(gesture.anchor.row,gesture.anchor.column);
            gesture.offsetX=(x-position.pageX)/editor.renderer.characterWidth;
            gesture.offsetY=(y-position.pageY)/editor.renderer.lineHeight;
            beginPreview();
        }
    }
    function move(event) {
        if (!gesture) return;
        stop(event);
        if (event.touches.length < 2) return;
        gesture.nextSize=Math.max(8,Math.min(40,Math.round(gesture.size*distance(event.touches)/gesture.distance*2)/2));
        // Touch delivery can exceed display refresh. Apply only its latest
        // distance once per frame, without reflowing Ace or wrapping the file.
        if (frame === null) frame=requestAnimationFrame(applyGesture);
    }
    function end(event) {
        if (!gesture) return;
        if (event.type !== 'touchcancel' && event.touches.length) { stop(event); return; }
        // Ace also needs the final touchend to clear its gesture state.
        event.preventDefault();
        if (frame !== null) cancelAnimationFrame(frame);
        const final=gesture;restorePreview(final.preview);
        frame=null;gesture=null;previousRange=null;
        editor.selection.setSelectionRange(final.range);
        if (!setSize(final.nextSize)) editor.resize(true);
        const actual=editor.renderer.textToScreenCoordinates(final.anchor.row,final.anchor.column);
        editor.session.setScrollTop(editor.session.getScrollTop()+actual.pageY-final.y
            +final.offsetY*editor.renderer.lineHeight-window.scrollY);
        editor.session.setScrollLeft(editor.session.getScrollLeft()+actual.pageX-final.x
            +final.offsetX*editor.renderer.characterWidth-window.scrollX);
        if (changed) changed();
    }
    const options={capture:true,passive:false};
    window.addEventListener('touchstart',start,options);
    window.addEventListener('touchmove',move,options);
    window.addEventListener('touchend',end,options);
    window.addEventListener('touchcancel',end,options);
    function destroy() {
        if (destroyed) return;
        destroyed=true;cancel();
        window.removeEventListener('touchstart',start,true); window.removeEventListener('touchmove',move,true);
        window.removeEventListener('touchend',end,true); window.removeEventListener('touchcancel',end,true);
        editor.off('changeSession',cancel);editor.off('destroy',destroy);
    }
    editor.on('changeSession',cancel);editor.on('destroy',destroy);
    return {setFontSize:function(size){cancel();return setSize(size);},destroy:destroy};
};
