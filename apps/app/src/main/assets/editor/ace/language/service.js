/* JSON-RPC language service in a Web Worker. No Node process or network server. */
'use strict';
importScripts('typescript.js','standard-libs.js');
const documents=new Map(), disk=new Map();
const workspace=new URL(self.location.href).searchParams.get('workspace');
let projectVersion=0;
function canonical(name) {
    const parts=[];
    name.replace(/\\/g,'/').split('/').forEach(p=>{if(p==='..')parts.pop();else if(p && p!=='.')parts.push(p);});
    return '/'+parts.join('/');
}
function readUrl(path) {
    try { const xhr=new XMLHttpRequest();
        if(path.startsWith('/workspace/') && workspace!==null)path+='?workspace='+encodeURIComponent(workspace);
        xhr.open('GET',path,false);xhr.send();
        return xhr.status===200 ? xhr.responseText : undefined;
    } catch(e) { return undefined; }
}
const api=readUrl('/language/autojs.d.ts') || '';
function readFile(name) {
    name=canonical(name);
    if(documents.has(name)) return documents.get(name).text;
    if(name==='/autojs.d.ts') return api;
    const lib=standardLibs[name.substring(1)];if(lib!==undefined)return lib;
    if(!name.startsWith('/workspace/')) return undefined;
    if(!disk.has(name)) {
        const text=readUrl(name.split('/').map(encodeURIComponent).join('/'));
        disk.set(name,text && text.length>256*1024 ? undefined : text);
    }
    return disk.get(name);
}
const host={
    getCompilationSettings:()=>({allowJs:true,checkJs:true,noEmit:true,strict:false,
        target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,
        moduleResolution:ts.ModuleResolutionKind.Node10,lib:['lib.es2022.d.ts'],skipLibCheck:true}),
    getScriptFileNames:()=>['/autojs.d.ts',...documents.keys()],
    getScriptVersion:name=>documents.has(name)?String(documents.get(name).version):String(projectVersion),
    getScriptSnapshot:name=>{const value=readFile(name);return value===undefined?undefined:ts.ScriptSnapshot.fromString(value);},
    getCurrentDirectory:()=>'/workspace',getDefaultLibFileName:()=>'/lib.es2022.d.ts',
    fileExists:name=>readFile(name)!==undefined,readFile:readFile,
    directoryExists:name=>name==='/' || name.startsWith('/workspace'),
    getDirectories:()=>[],readDirectory:()=>[],useCaseSensitiveFileNames:()=>true,
    getNewLine:()=> '\n',getProjectVersion:()=>String(projectVersion)
};
const service=ts.createLanguageService(host);
function fileOf(params){return canonical('/workspace/'+params.textDocument.uri);}
function offset(file,position){const doc=documents.get(file), lines=doc.text.split('\n');
    let result=0;for(let i=0;i<Math.min(position.line,lines.length-1);i++)result+=lines[i].length+1;
    return result+Math.min(position.character,(lines[position.line] || '').length);}
function display(parts){return ts.displayPartsToString(parts || []);}
function refreshDisk(){
    disk.forEach((old,name)=>{let text=readUrl(name.split('/').map(encodeURIComponent).join('/'));
        if(text && text.length>256*1024)text=undefined;
        if(text!==old){disk.set(name,text);projectVersion++;}});
}
function diagnostics(file){
    const source=service.getProgram().getSourceFile(file);
    return [...service.getSyntacticDiagnostics(file),...service.getSemanticDiagnostics(file)].slice(0,100).map(d=>{
        const start=source.getLineAndCharacterOfPosition(d.start || 0),end=source.getLineAndCharacterOfPosition((d.start || 0)+(d.length || 0));
        return {range:{start:{line:start.line,character:start.character},end:{line:end.line,character:end.character}},
            severity:d.category===ts.DiagnosticCategory.Error?1:2,code:d.code,
            message:ts.flattenDiagnosticMessageText(d.messageText,'\n'),source:'TypeScript'};
    });
}
function handle(method,params){
    if(method==='initialize') return {capabilities:{completionProvider:{triggerCharacters:['.']},
        hoverProvider:true,signatureHelpProvider:{triggerCharacters:['(', ',']},textDocumentSync:1}};
    if(method==='textDocument/didClose'){documents.delete(fileOf(params));disk.clear();projectVersion++;return;}
    if(method==='textDocument/didOpen' || method==='textDocument/didChange'){
        const file=fileOf(params), previous=documents.get(file);
        const text=method.endsWith('didOpen')?params.textDocument.text:params.contentChanges[0].text;
        if(previous && previous.version>params.textDocument.version)return;
        documents.set(file,{text:text,version:params.textDocument.version});projectVersion++;return;
    }
    const file=fileOf(params);
    if(!documents.has(file)) return null;
    refreshDisk();
    if(method==='textDocument/diagnostic'){
        const doc=documents.get(file);
        self.postMessage({jsonrpc:'2.0',method:'textDocument/publishDiagnostics',
            params:{uri:params.textDocument.uri,version:doc.version,diagnostics:diagnostics(file)}});return;
    }
    if(method==='textDocument/completion'){
        const pos=offset(file,params.position), result=service.getCompletionsAtPosition(file,pos,{});
        const prefix=(params.prefix || '').toLowerCase();
        return result ? result.entries.filter(e=>!prefix || e.name.toLowerCase().startsWith(prefix)).slice(0,250).map(entry=>{
            let details;
            try{details=service.getCompletionEntryDetails(file,pos,entry.name,{},entry.source,{},entry.data);}catch(e){}
            return {label:entry.name,insertText:entry.insertText || entry.name,
                detail:details?display(details.displayParts):entry.kind,
                documentation:details?display(details.documentation):''};
        }):[];
    }
    if(method==='textDocument/hover'){
        const info=service.getQuickInfoAtPosition(file,offset(file,params.position));
        return info?{contents:display(info.displayParts)+'\n'+display(info.documentation)}:null;
    }
    if(method==='textDocument/signatureHelp'){
        const info=service.getSignatureHelpItems(file,offset(file,params.position),{});
        return info?{activeSignature:info.selectedItemIndex,activeParameter:info.argumentIndex,
            signatures:info.items.map(s=>({label:display(s.prefixDisplayParts)
                +s.parameters.map(p=>display(p.displayParts)).join(display(s.separatorDisplayParts))+display(s.suffixDisplayParts),
                documentation:display(s.documentation)}))}:null;
    }
    return null;
}
self.onmessage=function(event){const m=event.data;
    try{const result=handle(m.method,m.params || {});
        if(m.id!==undefined)self.postMessage({jsonrpc:'2.0',id:m.id,result:result});
    }catch(error){if(m.id!==undefined)self.postMessage({jsonrpc:'2.0',id:m.id,error:{code:-32603,message:String(error)}});}
};
