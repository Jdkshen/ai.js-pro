/* Ace owns text, undo and rendering; Android owns files, tabs and execution. */
'use strict';
ace.config.set('basePath', location.origin + '/');
const editor = window.editor = ace.edit('editor');
installAndroidEditorTouch(editor);
const editorClipboard = installEditorClipboard(editor);
installEditorAppearance(editor);
// The Android-flavoured Ace touch handler calls these hooks even without its
// old `autojs` bridge. This host provides editing actions in the native toolbar;
// keep touch selection/scrolling usable when those optional hooks are absent.
['startActionMode','finishActionMode','startActionModeIfSelectionInView'].forEach(name=>{
    if(typeof editor[name]!=='function')editor[name]=function(){};
});
const Range = ace.require('ace/range').Range;
editor.setOptions({fontSize:17,showPrintMargin:false,tabSize:4,useSoftTabs:true,
    enableSnippets:true,enableBasicAutocompletion:true,enableLiveAutocompletion:true,
    showFoldWidgets:true,fadeFoldWidgets:false,hasCssTransforms:true,scrollPastEnd:0.5});
editor.setTheme('ace/theme/tomorrow_night');
editor.session.setMode('ace/mode/javascript');
editor.session.setUseWorker(false); // TypeScript service supplies diagnostics in a separate worker.
let worker=null, path='script.js', version=0, sequence=0, snapshotSequence=0, active=false, destroyed=false;
const requests=new Map();
const status=document.getElementById('signature');
let diagnosticTimer, signatureTimer, marker;
let lastLanguageError='';
let readyPromise=Promise.resolve(false), resolveReady=null, readyTimer;
let documentTimer, languageTimer, initializingText=false, largeFile=false, plainText=false;
let publishFrame=null;
const sessions=new Map(), loadingSessions=new Map();
let currentSessionId=null, activation=0, workspace='', languagePath=null, languageWorkspace='', idleWorkerTimer;
function configureFileSize() {
    const lines=editor.session.doc.$lines, characters=editor.getValue().length;
    largeFile=characters>256*1024 || lines.length>10000 || lines.some(line=>line.length>20000);
    plainText=characters>4*1024*1024 || lines.some(line=>line.length>20000);
    editor.setOption('enableLiveAutocompletion',!largeFile);
    const mode=plainText?'ace/mode/text':'ace/mode/'+(/\.json$/.test(path)?'json':/\.ts$/.test(path)?'typescript':'javascript');
    if(editor.session.getMode().$id!==mode)editor.session.setMode(mode);
    editor.session.setUseWorker(false);
    if(largeFile)pauseLanguage();
    const saved=sessions.get(currentSessionId);
    if(saved){saved.largeFile=largeFile;saved.plainText=plainText;}
}
function snapshot(withText,saved) {
    saved=saved || sessions.get(currentSessionId);
    const session=saved?saved.session:editor.session, doc=session.doc, range=session.getSelection().getRange(), um=session.getUndoManager();
    const value={id:saved?saved.id:'',sequence:++snapshotSequence,start:doc.positionToIndex(range.start),end:doc.positionToIndex(range.end),
        row:session.getSelection().getCursor().row,scrollX:session.getScrollLeft(),scrollY:session.getScrollTop(),
        fontSize:saved && saved.id!==currentSessionId?saved.font:parseFloat(editor.getFontSize()),canUndo:um.hasUndo(),canRedo:um.hasRedo(),
        breakpoints:Object.keys(session.getBreakpoints()).filter(r=>session.getBreakpoints()[r]).map(Number)};
    if(withText!==false)value.text=session.getValue();
    return value;
}
function publish(withText) { if (!destroyed && currentSessionId!==null && window.EditorBridge) EditorBridge.update(JSON.stringify(snapshot(withText))); }
// Scroll/fling can update both axes several times before the screen is drawn.
// Only notify Android once per frame; actual text deltas still publish immediately.
function schedulePublish() {
    if(publishFrame!==null || destroyed)return;
    publishFrame=requestAnimationFrame(()=>{publishFrame=null;publish(false);});
}
function send(method,params) { if(worker) worker.postMessage({jsonrpc:'2.0',method:method,params:params}); }
async function request(method,params) {
    if(largeFile) return null;
    if(active)startLanguage();
    if(!worker) return Promise.resolve(null);
    flushDocument();
    const current=worker;
    if(!await readyPromise || worker!==current) return null;
    return new Promise(resolve=>{
        const id=++sequence, timer=setTimeout(()=>{requests.delete(id);resolve(null);},8000);
        requests.set(id,{resolve:resolve,timer:timer});
        worker.postMessage({jsonrpc:'2.0',id:id,method:method,params:params});
    });
}
function updateDocument() {
    send('textDocument/didChange',{textDocument:{uri:path,version:version},contentChanges:[{text:editor.getValue()}]});
}
function flushDocument() { if(documentTimer){clearTimeout(documentTimer);documentTimer=null;updateDocument();} }
function scheduleDocument() { clearTimeout(documentTimer);documentTimer=setTimeout(()=>{documentTimer=null;updateDocument();},120); }
function scheduleLanguage() { clearTimeout(languageTimer);if(!largeFile)languageTimer=setTimeout(startLanguage,600); }
function startLanguage() {
    clearTimeout(languageTimer);languageTimer=null;
    clearTimeout(idleWorkerTimer);
    if(destroyed || largeFile || !active || !/\.(?:js|ts)$/.test(path)) return;
    if(worker && languageWorkspace!==workspace)stopLanguage();
    if(worker){openLanguageDocument();scheduleDiagnostics();return;}
    try { worker=new Worker('language/service.js?workspace='+encodeURIComponent(workspace));languageWorkspace=workspace; } catch(e) { console.error(e); return; }
    readyPromise=new Promise(resolve=>resolveReady=resolve);
    readyTimer=setTimeout(()=>{lastLanguageError='Language service startup timed out';stopLanguage();},15000);
    worker.onmessage=function(event){
        const message=event.data;
        if(message.id===0){clearTimeout(readyTimer);if(resolveReady)resolveReady(true);resolveReady=null;return;}
        if(message.method==='textDocument/publishDiagnostics') {
            if(!active || largeFile || message.params.uri!==path || message.params.version!==version) return;
            editor.session.setAnnotations(message.params.diagnostics.map(d=>({row:d.range.start.line,
                column:d.range.start.character,text:d.message,type:d.severity===1?'error':'warning'})));
            return;
        }
        if(message.error)lastLanguageError=message.error.message || JSON.stringify(message.error);
        const pending=requests.get(message.id);
        if(pending){clearTimeout(pending.timer);requests.delete(message.id);pending.resolve(message.result || null);}
    };
    worker.onerror=function(e){lastLanguageError=e.message;console.error('Language service',e.message);stopLanguage();};
    worker.postMessage({jsonrpc:'2.0',id:0,method:'initialize',params:{}});
    openLanguageDocument();
    scheduleDiagnostics();
}
function openLanguageDocument() {
    if(languagePath===path)return;
    if(languagePath!==null)send('textDocument/didClose',{textDocument:{uri:languagePath}});
    languagePath=path;
    send('textDocument/didOpen',{textDocument:{uri:path,version:version,text:editor.getValue()}});
}
function pauseLanguage() {
    clearTimeout(languageTimer);languageTimer=null;clearTimeout(documentTimer);documentTimer=null;
    clearTimeout(diagnosticTimer);clearTimeout(signatureTimer);status.style.display='none';
    if(languagePath!==null)send('textDocument/didClose',{textDocument:{uri:languagePath}});
    languagePath=null;
    requests.forEach(p=>{clearTimeout(p.timer);p.resolve(null);});requests.clear();
}
function stopLanguage() {
    clearTimeout(idleWorkerTimer);languagePath=null;
    clearTimeout(languageTimer);languageTimer=null;clearTimeout(documentTimer);documentTimer=null;
    clearTimeout(readyTimer);if(resolveReady)resolveReady(false);resolveReady=null;
    readyPromise=Promise.resolve(false);
    if(worker) worker.terminate(); worker=null;
    requests.forEach(p=>{clearTimeout(p.timer);p.resolve(null);});requests.clear();
    status.style.display='none';clearTimeout(diagnosticTimer);clearTimeout(signatureTimer);
}
function scheduleDiagnostics() {
    clearTimeout(diagnosticTimer);
    if(largeFile)return;
    diagnosticTimer=setTimeout(()=>send('textDocument/diagnostic',{textDocument:{uri:path,version:version}}),450);
}
function positionParams(position) { return {textDocument:{uri:path,version:version},
    position:{line:position.row,character:position.column}}; }
const completer={
    getCompletions:function(e,session,pos,prefix,callback){
        const v=version, docPath=path;
        request('textDocument/completion',Object.assign(positionParams(pos),{prefix:prefix})).then(result=>{
            if(v!==version || docPath!==path) return callback(null,[]);
            callback(null,(result || []).map(item=>({caption:item.label,value:item.insertText || item.label,
                meta:item.detail || 'JavaScript',score:1000,docText:item.documentation || ''})));
        });
    },
    getDocTooltip:function(item){ if(item.docText) item.docText=String(item.docText); }
};
editor.completers.push(completer);
editor.on('change',delta=>{
    version++;if(initializingText)return;
    if(!destroyed && window.EditorBridge){
        const value=snapshot(false), text=delta.lines.join(editor.session.doc.getNewLineCharacter());
        value.edit={offset:editor.session.doc.positionToIndex(delta.start),
            removed:delta.action==='remove'?text.length:0,inserted:delta.action==='insert'?text:''};
        EditorBridge.update(JSON.stringify(value));
    }
    if(!largeFile && editor.getValue().length>256*1024)configureFileSize();
    if(!largeFile){scheduleDocument();scheduleDiagnostics();}
});
editor.on('changeSelection',schedulePublish);
editor.on('changeSelection',()=>{
    clearTimeout(signatureTimer);
    if(initializingText || largeFile || !active)return;
    signatureTimer=setTimeout(()=>{
        const v=version,pos=editor.getCursorPosition();
        request('textDocument/signatureHelp',positionParams(pos)).then(result=>{
            if(v!==version || pos.row!==editor.getCursorPosition().row || pos.column!==editor.getCursorPosition().column) return;
            const signature=result && result.signatures && result.signatures[result.activeSignature || 0];
            status.textContent=signature ? signature.label : '';status.style.display=signature ? 'block' : 'none';
        });
    },200);
});
function toggleBreakpoint(row) {
    const enabled=!editor.session.getBreakpoints()[row];
    if(enabled)editor.session.setBreakpoint(row);else editor.session.clearBreakpoint(row);
    publish(false); if(window.EditorBridge) EditorBridge.breakpoint(currentSessionId,row,enabled);
}
editor.on('guttermousedown',event=>{
    if(event.domEvent.target.classList.contains('ace_fold-widget')) return;
    if(!event.domEvent.target.classList.contains('ace_gutter-cell')) return;
    toggleBreakpoint(event.getDocumentPosition().row);event.stop();
});
const pinch=installEditorPinch(editor,schedulePublish);
function rememberSession() {
    const saved=sessions.get(currentSessionId);
    if(saved){saved.font=parseFloat(editor.getFontSize());saved.theme=editor.getTheme();saved.marker=marker;}
}
function useSession(saved) {
    currentSessionId=saved.id;path=saved.path;workspace=saved.workspace;
    largeFile=saved.largeFile;plainText=saved.plainText;marker=saved.marker;
    editor.setSession(saved.session);pinch.setFontSize(saved.font);editor.setTheme(saved.theme);
    // A session swap does not end an Ace command. Refresh the attached textarea
    // so the IME receives the new document's surrounding text and selection.
    editor.textInput.resetSelection();
    editor.setOption('enableLiveAutocompletion',!largeFile);
}
async function openSession(id,docPath,font,theme,root,select) {
    const token=select?++activation:activation;
    if(!sessions.has(id)) {
        if(!loadingSessions.has(id))loadingSessions.set(id,fetch('/document?session='+encodeURIComponent(id))
            .then(response=>{if(!response.ok)throw new Error('HTTP '+response.status);return response.text();})
            .then(text=>{
                const session=ace.createEditSession(text), lines=session.doc.$lines;
                const large=text.length>256*1024 || lines.length>10000 || lines.some(line=>line.length>20000);
                const plain=text.length>4*1024*1024 || lines.some(line=>line.length>20000);
                session.setMode(plain?'ace/mode/text':'ace/mode/'+(/\.json$/.test(docPath)?'json':/\.ts$/.test(docPath)?'typescript':'javascript'));
                session.setUseWorker(false);session.setTabSize(4);session.setUseSoftTabs(true);session.bgTokenizer.stop();
                session.on('changeScrollTop',schedulePublish);session.on('changeScrollLeft',schedulePublish);
                const saved={id:id,path:docPath,workspace:root,font:font,theme:'ace/theme/'+theme,session:session,largeFile:large,plainText:plain};
                sessions.set(id,saved);loadingSessions.delete(id);
                if(window.EditorBridge)EditorBridge.update(JSON.stringify(snapshot(session.getValue()!==text,saved)));
                return saved;
            }));
        try{await loadingSessions.get(id);}catch(error){loadingSessions.delete(id);if(window.EditorBridge)EditorBridge.error('读取编辑器文件失败：'+error);return;}
    }
    if(select && token===activation) {
        rememberSession();pauseLanguage();
        if(editor.completer)editor.completer.detach();
        const previous=sessions.get(currentSessionId);if(previous)previous.session.bgTokenizer.stop();
        const saved=sessions.get(id);saved.path=docPath;saved.workspace=root;saved.theme='ace/theme/'+theme;
        useSession(saved);version++;active=true;clearTimeout(idleWorkerTimer);
        editor.session.bgTokenizer.scheduleStart();editor.resize();scheduleLanguage();publish(false);
    }
    if(window.EditorBridge)EditorBridge.prepared(id);
}
window.port={snapshot:snapshot,
    openSession:openSession,
    copySelection:editorClipboard.copySelection,
    cutSelection:editorClipboard.cutSelection,
    copyAll:editorClipboard.copyAll,
    copyLine:editorClipboard.copyLine,
    paste:editorClipboard.paste,
    selectAll:editorClipboard.selectAll,
    onNativeActionModeClosed:function(){editor.actionMode='';},
    snapshotSession:function(id){const saved=sessions.get(id);return saved?snapshot(true,saved):null;},
    withSession:function(id,action){
        const saved=sessions.get(id);if(!saved)return null;
        if(currentSessionId===id)return action();
        rememberSession();const previous=sessions.get(currentSessionId), oldActive=active;
        const original=editor.session;active=false;useSession(saved);
        try{return action();}finally{rememberSession();if(previous)useSession(previous);
            else{currentSessionId=null;editor.setSession(original);}active=oldActive;saved.session.bgTokenizer.stop();}
    },
    closeSession:function(id){const saved=sessions.get(id);if(!saved)return;
        if(currentSessionId===id){pauseLanguage();active=false;currentSessionId=null;
            editor.setSession(ace.createEditSession(''));}saved.session.destroy();sessions.delete(id);},
    showKeyboard:function(){if(active && currentSessionId!==null && window.EditorBridge)EditorBridge.showKeyboard(currentSessionId);},
    setActive:function(value){active=value;if(active){clearTimeout(idleWorkerTimer);editor.session.bgTokenizer.scheduleStart();
        scheduleLanguage();editor.resize();}else{pauseLanguage();editor.session.bgTokenizer.stop();
            idleWorkerTimer=setTimeout(stopLanguage,20000);}},
    setDocument:function(value){if(path!==value) {pauseLanguage();path=value;version++;}
        const saved=sessions.get(currentSessionId);if(saved)saved.path=value;
        configureFileSize();if(active)scheduleLanguage();},
    initialText:function(text,alreadyNative){initializingText=true;try{editor.setValue(text,-1);
        editor.session.getUndoManager().reset();}finally{initializingText=false;}
        configureFileSize();updateDocument();publish(!alreadyNative || editor.getValue()!==text);
        if(active)scheduleLanguage();else editor.session.bgTokenizer.stop();},
    setText:function(text){editor.session.replace(new Range(0,0,editor.session.getLength(),0),text);},
    setFontSize:function(size){pinch.setFontSize(size);schedulePublish();},
    restore:function(cursor,x,y){editor.selection.moveCursorToPosition(editor.session.doc.indexToPosition(Math.max(0,Math.min(cursor,editor.getValue().length))));
        editor.session.setScrollLeft(Math.max(0,x));editor.session.setScrollTop(Math.max(0,y));editor.resize(true);},
    toggleFold:function(){const row=editor.getCursorPosition().row, fold=editor.session.getFoldAt(row,0,1)
        ||editor.session.getAllFolds().find(f=>f.start.row===row);
        if(fold)editor.session.expandFold(fold);else editor.session.foldAll(row,row+1);},
    beautify:function(){const selection=editor.getSelectedText(), result=js_beautify(selection || editor.getValue(),{indent_size:4,e4x:true});
        if(selection)editor.session.replace(editor.getSelectionRange(),result);else this.setText(result);},
    toggleBreakpoint:toggleBreakpoint,
    debugLine:function(row){if(marker!==undefined) editor.session.removeMarker(marker);marker=undefined;
        if(row>=0) marker=editor.session.addMarker(new Range(row,0,row,1),'debug-marker','fullLine');},
    languageRequest:request,
    freezeSession:function(){
        editor.session.markUndoGroup();stopLanguage();editor.session.bgTokenizer.stop();
        const undo=editor.session.getUndoManager(), data={};
        ['$undoStack','$redoStack','$rev','$maxRev','$redoStackBaseRev','mark','selections'].forEach(k=>data[k]=undo[k]);
        const state=snapshot(), folds=[];
        function saveFold(f){folds.push({start:f.start,end:f.end,placeholder:f.placeholder});(f.subFolds || []).forEach(saveFold);}
        editor.session.getAllFolds().forEach(saveFold);
        return {snapshot:state,session:{sequence:state.sequence,undo:data,folds:folds,
            selection:editor.selection.toJSON(),scrollX:state.scrollX,scrollY:state.scrollY,
            font:state.fontSize,breakpoints:state.breakpoints}};
    },
    resumeSession:function(saved){
        snapshotSequence=Math.max(snapshotSequence,saved.sequence || 0);
        const undo=editor.session.getUndoManager();
        Object.keys(saved.undo).forEach(k=>undo[k]=saved.undo[k]);
        undo.lastDeltas=null;undo.$lastDelta=null;editor.session.markUndoGroup();
        (saved.folds || []).forEach(f=>{try{editor.session.addFold(f.placeholder,
            new Range(f.start.row,f.start.column,f.end.row,f.end.column));}catch(error){console.error(error);}});
        (saved.breakpoints || []).forEach(row=>editor.session.setBreakpoint(row));
        editor.selection.fromJSON(saved.selection);pinch.setFontSize(saved.font);
        editor.session.setScrollLeft(saved.scrollX);editor.session.setScrollTop(saved.scrollY);publish(false);
    },
    languageStatus:function(){return {active:active,worker:!!worker,path:path,version:version,error:lastLanguageError,
        sessionId:currentSessionId,sessions:sessions.size,
        pending:requests.size,largeFile:largeFile,plainText:plainText};},
    destroy:function(){destroyed=true;cancelAnimationFrame(publishFrame);publishFrame=null;
        stopLanguage();pinch.destroy();editor.destroy();}
};
new ResizeObserver(()=>editor.resize(true)).observe(document.getElementById('editor'));
if(window.EditorBridge)EditorBridge.ready();
