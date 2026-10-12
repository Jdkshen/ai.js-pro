const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'), vm=require('node:vm');
const folder=path.resolve(__dirname,'../apps/app/src/main/assets/editor/ace/language');
const workspace=new Map([['/workspace/helper.js','exports.answer = { value: 42, label: "ok" };']]);
const messages=[];
const sandbox={console,setTimeout,clearTimeout,Map,Set,Date,performance,URL,
    location:{href:'https://editor.aijspro.local/language/service.js?workspace=test-root'},self:null};
sandbox.self=sandbox;
sandbox.postMessage=m=>messages.push(m);
sandbox.XMLHttpRequest=class {
    open(method,url){this.url=decodeURIComponent(url.split('?')[0]);
        if(url.startsWith('/workspace/'))assert(url.includes('?workspace=test-root'),'workspace context retained by reused worker');}
    send(){const name=path.basename(this.url), local=this.url.startsWith('/language/')?path.join(folder,name):null;
        this.responseText=local && fs.existsSync(local)?fs.readFileSync(local,'utf8'):workspace.get(this.url);
        this.status=this.responseText===undefined?404:200;}
};
const context=vm.createContext(sandbox);
sandbox.importScripts=(...names)=>names.forEach(name=>vm.runInContext(fs.readFileSync(path.join(folder,name),'utf8'),context,{filename:name}));
vm.runInContext(fs.readFileSync(path.join(folder,'service.js'),'utf8'),context,{filename:'service.js'});
let id=0, version=0;
function notify(method,params){sandbox.onmessage({data:{jsonrpc:'2.0',method,params}});}
function open(text){version++;notify('textDocument/didOpen',{textDocument:{uri:'main.js',version,text}});}
function request(method,extra={}){
    const key=++id;
    sandbox.onmessage({data:{jsonrpc:'2.0',id:key,method,params:{textDocument:{uri:'main.js',version},...extra}}});
    const message=messages.findLast(m=>m.id===key);
    assert(message && !message.error,JSON.stringify(message));return message.result;
}
open('const person = { name: "Ada", age: 25 };\nperson.');
let items=request('textDocument/completion',{position:{line:1,character:7}});
assert(items.some(i=>i.label==='name') && items.some(i=>i.label==='age'),'inferred object properties');
open('const names = ["a", "b"];\nnames.');
items=request('textDocument/completion',{position:{line:1,character:6}});
assert(items.some(i=>i.label==='map'),'standard ES library');
open('toast(');
const signature=request('textDocument/signatureHelp',{position:{line:0,character:6}});
assert(signature.signatures.some(s=>s.label.includes('message')),'Android API signature');
open('const helper = require("./helper");\nhelper.answer.');
items=request('textDocument/completion',{position:{line:1,character:14}});
assert(items.some(i=>i.label==='value'),'relative CommonJS module');
workspace.set('/workspace/helper.js','exports.answer = { changed: true };');
items=request('textDocument/completion',{position:{line:1,character:14}});
assert(items.some(i=>i.label==='changed') && !items.some(i=>i.label==='value'),'changed sibling file');
open('const broken = ;');
request('textDocument/diagnostic');
const d=messages.findLast(m=>m.method==='textDocument/publishDiagnostics');
assert(d.params.diagnostics.some(d=>d.severity===1),'syntax error diagnostic');
assert.equal(d.params.version,version,'diagnostic version');
notify('textDocument/didClose',{textDocument:{uri:'main.js'}});
assert.equal(request('textDocument/completion',{position:{line:0,character:0}}),null,'closed document released');
open('const fresh = { nextProperty: 1 };\nfresh.');
items=request('textDocument/completion',{position:{line:1,character:6}});
assert(items.some(i=>i.label==='nextProperty'),'worker reused after document switch');
console.log('PASS: inferred properties, standard library, API signature, module resolution, sibling refresh, diagnostics');
