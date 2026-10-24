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

    /**
     * Shape-only probe template. It walks own/prototype descriptors and never invokes a JS getter.
     *
     * <p>When a runtime exposes members through a Java/Rhino (or similar) proxy and ECMAScript
     * descriptor APIs are unavailable, this reports {@code unverified} instead of pretending all
     * such members are missing. Java methods/fields are read only after reflection says they are
     * plain methods/fields; JavaBean-style properties are reported as {@code accessor} without
     * being read. This script is also executed directly by tools/tests/test_mcp_probe_behavior.py.
     */
    private const val PROBE_TEMPLATE = """(function(){
  var moduleName = __MODULE__;
  var memberName = __NAME__;
  var includeInherited = __INHERITED__;
  var root = null;
  var rootError = null;
  try { root = __ROOT__; } catch (error) { rootError = error; }

  function text(value) {
    if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return String(value);
    var d = descriptorAt(value, 'message');
    return d.found && d.data && typeof d.value === 'string' ? d.value : 'unreadable';
  }
  function ownNames(o) {
    var a = [];
    try { a = Object.getOwnPropertyNames(Object(o)); }
    catch (error) { try { for (var k in o) a.push(k); } catch (ignored) { } }
    try {
      if (isJava(o)) {
        var c = javaClass(o), m = c.getMethods(), f = c.getFields();
        for (var i = 0; i < m.length; i++) a.push(String(m[i].getName()));
        for (var j = 0; j < f.length; j++) a.push(String(f[j].getName()));
      }
    } catch (ignored) { }
    var seen = Object.create(null);
    return a.filter(function(k) { if (seen[k]) return false; seen[k] = true; return true; });
  }
  function descriptorAt(o, k) {
    var p = o, depth = 0;
    while (p !== null && p !== undefined && depth++ < 8) {
      try {
        var d = Object.getOwnPropertyDescriptor(Object(p), k);
        if (d) return { found: true, data: ('value' in d), value: d.value, descriptor: d };
      } catch (error) { return { found: false, error: text(error) }; }
      try { p = Object.getPrototypeOf(p); }
      catch (error) { return { found: false, error: text(error) }; }
    }
    return { found: false };
  }
  function isJava(o) {
    try {
      if (typeof Symbol !== 'undefined' && Symbol.toStringTag) {
        var tag = descriptorAt(o, Symbol.toStringTag);
        if (tag.found && !tag.data) return false;
      }
      return Object.prototype.toString.call(o).indexOf('Java') >= 0;
    }
    catch (error) { return false; }
  }
  function javaClass(o) {
    var d = descriptorAt(o, 'getClass');
    if (d.found && !d.data) return null;
    return o.getClass();
  }
  var DYNAMIC_MARKERS = ['__handle', '__classRef', '__className', '__ajsClass', '__packageRef', '__remoteProxy', '__nodeUiMarker', '__class'];
  function isDynamic(o) {
    if (isJava(o)) return true;
    for (var i = 0; i < DYNAMIC_MARKERS.length; i++) {
      try {
        var d = Object.getOwnPropertyDescriptor(Object(o), DYNAMIC_MARKERS[i]);
        if (d && ('value' in d)) return true;
      } catch (error) { }
    }
    return false;
  }
  function javaShapes(o) {
    var methods = Object.create(null), fields = Object.create(null);
    try {
      var c = javaClass(o);
      try {
        var m = c.getMethods();
        for (var i = 0; i < m.length; i++) {
          var n = String(m[i].getName());
          if (!(n in methods)) methods[n] = true;
        }
      } catch (ignored) { }
      try {
        var f = c.getFields();
        for (var j = 0; j < f.length; j++) fields[String(f[j].getName())] = true;
      } catch (ignored) { }
    } catch (ignored) { }
    return { methods: methods, fields: fields };
  }
  function javaKind(key, shapes) {
    if (shapes.methods[key] !== undefined) return 'method';
    if (shapes.fields[key]) return 'field';
    return 'accessor';
  }
  function hasKey(o, k) {
    try { return { known: true, value: k in o }; }
    catch (first) {
      try { return { known: true, value: k in Object(o) }; }
      catch (second) { return { known: false, error: text(first) }; }
    }
  }

  var parts = memberName ? memberName.split('.') : [];
  var value = root;
  var failureAt = null;
  var evidence = rootError ? 'none' : 'root';
  var presence = rootError ? 'unreadable' : 'present';
  var present = rootError ? null : true;
  var errorText = rootError ? text(rootError) : null;
  var typeName = rootError ? 'unreadable' : 'unverified';

  if (!rootError) {
    for (var index = 0; index < parts.length; index++) {
      var key = parts[index];
      var prefix = parts.slice(0, index + 1).join('.');
      if (value === null || value === undefined) {
        presence = 'missing'; present = false; typeName = 'undefined';
        failureAt = prefix; evidence = 'shape'; value = undefined; break;
      }
      var d = descriptorAt(value, key);
      if (d.found) {
        if (!d.data) {
          presence = 'accessor'; present = true; typeName = 'accessor';
          failureAt = prefix; evidence = 'descriptor'; value = undefined; break;
        }
        value = d.value; evidence = 'descriptor'; failureAt = null; errorText = null;
        continue;
      }
      var java = isJava(value);
      var dynamic = java || isDynamic(value);
      var shapes = java ? javaShapes(value) : null;
      var has = hasKey(value, key);
      if (d.error) {
        if (java) {
          if (has.known && has.value) {
            var kind = javaKind(key, shapes);
            if (kind === 'accessor') {
              presence = 'accessor'; present = true; typeName = 'accessor';
              failureAt = prefix; evidence = 'reflection'; value = undefined; break;
            }
            try {
              value = value[key];
              evidence = 'reflection'; failureAt = null; errorText = null;
            } catch (readError) {
              presence = 'unreadable'; present = true; typeName = 'unreadable';
              failureAt = prefix; evidence = 'reflection'; errorText = text(readError); value = undefined; break;
            }
            continue;
          }
          if (has.known && !has.value) {
            presence = 'missing'; present = false; typeName = 'undefined';
            failureAt = prefix; evidence = 'shape'; value = undefined; break;
          }
          presence = 'unverified'; present = null; typeName = 'unverified';
          failureAt = prefix; evidence = 'shape'; errorText = d.error; value = undefined; break;
        }
        if (dynamic) {
          presence = 'unverified'; present = (has.known && has.value) ? true : null;
          typeName = 'unverified'; failureAt = prefix; evidence = 'presence';
          errorText = d.error; value = undefined; break;
        }
        presence = 'unreadable'; present = null; typeName = 'unreadable';
        failureAt = prefix; evidence = 'descriptor'; errorText = d.error; value = undefined; break;
      }
      if (has.known && has.value) {
        if (java) {
          var kind2 = javaKind(key, shapes);
          if (kind2 === 'accessor') {
            presence = 'accessor'; present = true; typeName = 'accessor';
            failureAt = prefix; evidence = 'reflection'; value = undefined; break;
          }
          try {
            value = value[key];
            evidence = 'reflection'; failureAt = null; errorText = null;
          } catch (readError2) {
            presence = 'unreadable'; present = true; typeName = 'unreadable';
            failureAt = prefix; evidence = 'reflection'; errorText = text(readError2); value = undefined; break;
          }
          continue;
        }
        presence = 'unverified'; present = true; typeName = 'unverified';
        failureAt = prefix; evidence = 'presence'; value = undefined; break;
      }
      if (has.known && !has.value) {
        if (dynamic) {
          presence = 'unverified'; present = null; typeName = 'unverified';
          failureAt = prefix; evidence = 'shape'; value = undefined; break;
        }
        presence = 'missing'; present = false; typeName = 'undefined';
        failureAt = prefix; evidence = 'shape'; value = undefined; break;
      }
      presence = 'unverified'; present = null; typeName = 'unverified';
      failureAt = prefix; evidence = 'shape'; errorText = has.error; value = undefined; break;
    }
  }

  var result = {
    name: memberName,
    module: moduleName,
    present: present,
    presence: presence,
    type: typeName,
    keys: [],
    members: [],
    arity: null,
    evidence: evidence,
    probeMode: 'shape'
  };
  if (failureAt) result.failureAt = failureAt;
  if (errorText) result.error = errorText;

  if (presence === 'present' && value !== null && value !== undefined) {
    result.type = typeof value;
    if (typeof value === 'function') {
      try { result.arity = value.length; } catch (ignored) { }
    }
    var javaValue = isJava(value);
    var dynamicValue = javaValue || isDynamic(value);
    var shapesValue = javaValue ? javaShapes(value) : null;
    var names = [], seen = Object.create(null), descriptors = Object.create(null);
    var p = value, depth = 0;
    while (p !== null && p !== undefined && (depth === 0 || includeInherited) && depth++ < 8) {
      ownNames(p).forEach(function(k) {
        if (!seen[k]) { seen[k] = true; names.push(k); descriptors[k] = descriptorAt(p, k); }
      });
      try { p = Object.getPrototypeOf(p); } catch (error) { p = null; }
      if (p === Object.prototype) break;
    }
    names.sort();
    result.keys = names;
    result.members = names.map(function(k) {
      var member = { name: k, type: 'unverified', arity: null, present: null, presence: 'unverified' };
      var memberDescriptor = descriptors[k];
      if (memberDescriptor && memberDescriptor.found) {
        if (memberDescriptor.data) {
          var memberValue = memberDescriptor.value;
          member.type = typeof memberValue;
          if (typeof memberValue === 'function') { try { member.arity = memberValue.length; } catch (ignored) { } }
          member.present = true; member.presence = 'present'; member.evidence = 'descriptor';
        } else {
          member.type = 'accessor'; member.present = true; member.presence = 'accessor'; member.evidence = 'descriptor';
        }
        return member;
      }
      if (javaValue) {
        var shape = javaKind(k, shapesValue);
        if (shape === 'accessor') {
          member.type = 'accessor'; member.present = true; member.presence = 'accessor'; member.evidence = 'reflection';
          return member;
        }
        try {
          var memberValue2 = value[k];
          member.type = typeof memberValue2;
          if (typeof memberValue2 === 'function') { member.arity = memberValue2.length; }
          member.present = true; member.presence = 'present'; member.evidence = 'reflection';
        } catch (readError3) {
          member.type = 'unreadable'; member.present = true; member.presence = 'unreadable';
          member.evidence = 'reflection'; member.error = text(readError3);
        }
        return member;
      }
      if (memberDescriptor && memberDescriptor.error) {
        member.type = 'unreadable'; member.present = null; member.presence = 'unreadable';
        member.evidence = 'descriptor'; member.error = memberDescriptor.error;
        return member;
      }
      if (dynamicValue) {
        member.evidence = 'shape';
        return member;
      }
      member.evidence = 'shape';
      return member;
    });
  }

  console.log('MCP_API_PROBE_RESULT=' + JSON.stringify(result));
}).call(this);"""

    private fun render(template: String, values: Map<String, String>): String =
        Regex("__[A-Z]+__").replace(template) { values[it.value] ?: it.value }

    fun probe(module: String, name: String, inherited: Boolean): String {
        require(module.length <= 240 && !module.contains('\u0000')) { "module 过长或包含 NUL" }
        require(name.isEmpty() || name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
            "name 必须是点分隔的 API 成员路径"
        }
        val root = if (module.isEmpty()) "(typeof globalThis!=='undefined'?globalThis:this)" else "require(${quote(module)})"
        return render(PROBE_TEMPLATE, mapOf(
            "__ROOT__" to root,
            "__MODULE__" to quote(module),
            "__NAME__" to quote(name),
            "__INHERITED__" to inherited.toString()
        ))
    }

    fun call(module: String, name: String, args: JsonArray): String = "(function(){" +
        "function fail(e){console.log('MCP_API_PROBE_RESULT='+JSON.stringify({ok:false,error:{type:e&&e.name||'Error',message:String(e&&e.message||e),stack:String(e&&e.stack||'')}}));}" +
        "function done(v){try{var encoded=JSON.stringify({ok:true,returnType:typeof v,value:v},function(k,x){if(typeof x==='bigint')return String(x);return x;});console.log('MCP_API_PROBE_RESULT='+encoded);}catch(e){fail(e);}}" +
        "try{" + target(module, name) + "if(typeof value!=='function')throw new TypeError('API 不是函数');" +
        "var result=value.apply(owner,$args);if(result&&typeof result.then==='function')result.then(done,fail);else done(result);" +
        "}catch(e){fail(e);}}).call(this);"
}
