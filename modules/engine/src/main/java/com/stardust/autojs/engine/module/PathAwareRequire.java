package com.stardust.autojs.engine.module;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.Require;

/** Encodes spaces before Rhino resolves relative IDs, including nested require calls. */
public final class PathAwareRequire extends Require {

    private static final long serialVersionUID = 1L;

    public PathAwareRequire(Context cx, Scriptable scope, ModuleScriptProvider provider) {
        super(cx, scope, provider, null, null, true);
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args == null || args.length == 0) {
            return super.call(cx, scope, thisObj, args);
        }
        String moduleId = Context.toString(args[0]);
        String encoded = encodeModuleId(moduleId);
        if (args[0] instanceof String && moduleId.equals(encoded)) {
            return super.call(cx, scope, thisObj, args);
        }
        Object[] adapted = args.clone();
        adapted[0] = encoded;
        return super.call(cx, scope, thisObj, adapted);
    }

    static String encodeModuleId(String moduleId) {
        return moduleId == null ? null : moduleId.replace(" ", "%20");
    }
}
