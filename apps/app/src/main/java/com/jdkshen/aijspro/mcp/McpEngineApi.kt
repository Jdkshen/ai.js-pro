package com.jdkshen.aijspro.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray

/** Generates ordinary scripts evaluated by the selected real engine, never a second evaluator. */
internal object McpEngineApi {
    private val enumeration =
        "function ownNames(o){var a=[];try{a=Object.getOwnPropertyNames(Object(o));}catch(e){for(var k in o)a.push(k);}" +
        "try{if(Object.prototype.toString.call(o).indexOf('Java')>=0&&typeof o.getClass==='function'){var c=o.getClass(),m=c.getMethods(),f=c.getFields();for(var i=0;i<m.length;i++)a.push(String(m[i].getName()));for(var j=0;j<f.length;j++)a.push(String(f[j].getName()));}}catch(e){}" +
        "var seen=Object.create(null);return a.filter(function(k){if(seen[k])return false;seen[k]=true;return true;});}" +
        "function descriptor(o,k){try{return Object.getOwnPropertyDescriptor(Object(o),k);}catch(e){return null;}}" +
        "function prototype(o){try{return Object.getPrototypeOf(o);}catch(e){return null;}}"
    private fun quote(value: String) = Gson().toJson(value)
    private fun target(module: String, name: String): String {
        require(module.length <= 240 && !module.contains('\u0000')) { "module 过长或包含 NUL" }
        require(name.isEmpty() || name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
            "name 必须是点分隔的 API 成员路径"
        }
        return "var root=" + (if (module.isEmpty()) "(typeof globalThis!=='undefined'?globalThis:this)" else "require(${quote(module)})") + ";" +
            "var value=root,owner=root,parts=" + quote(name) + ".split('.');" +
            "if(${quote(name)}){for(var i=0;i<parts.length;i++){owner=value;if(value==null)throw new Error('API 不存在: '+${quote(name)});value=value[parts[i]];}}"
    }
    fun list(module: String): String = "(function(){" + enumeration + target(module, "") +
        "var items=ownNames(root).filter(function(n){return n.indexOf('__')!==0;}).sort();" +
        "var modules=typeof __ajsListModules==='function'?__ajsListModules():[];" +
        "console.log('MCP_API_PROBE_RESULT='+JSON.stringify({items:items,modules:modules}));}).call(this);"

    fun probe(module: String, name: String, inherited: Boolean): String = "(function(){" + enumeration + target(module, name) +
        "var names=[],seen=Object.create(null),descriptors=Object.create(null),p=value,depth=0;while(p!=null&&(depth===0||$inherited)&&depth++<8){" +
        "ownNames(p).forEach(function(k){if(!seen[k]){seen[k]=true;descriptors[k]=descriptor(p,k);names.push(k);}});p=prototype(p);if(p===Object.prototype)break;}" +
        "var members=names.sort().map(function(k){try{var d=descriptors[k];" +
        "var v=d&&d.get?undefined:value[k];return {name:k,type:d&&d.get?'accessor':typeof v,arity:typeof v==='function'?v.length:null};}" +
        "catch(e){return {name:k,type:'unreadable',error:String(e)};}});" +
        "console.log('MCP_API_PROBE_RESULT='+JSON.stringify({name:${quote(name)},module:${quote(module)},type:typeof value,keys:names,members:members,arity:typeof value==='function'?value.length:null}));}).call(this);"

    fun call(module: String, name: String, args: JsonArray): String = "(function(){" +
        "function fail(e){console.log('MCP_API_PROBE_RESULT='+JSON.stringify({ok:false,error:{type:e&&e.name||'Error',message:String(e&&e.message||e),stack:String(e&&e.stack||'')}}));}" +
        "function done(v){try{var encoded=JSON.stringify({ok:true,returnType:typeof v,value:v},function(k,x){if(typeof x==='bigint')return String(x);return x;});console.log('MCP_API_PROBE_RESULT='+encoded);}catch(e){fail(e);}}" +
        "try{" + target(module, name) + "if(typeof value!=='function')throw new TypeError('API 不是函数');" +
        "var result=value.apply(owner,$args);if(result&&typeof result.then==='function')result.then(done,fail);else done(result);" +
        "}catch(e){fail(e);}}).call(this);"
}
