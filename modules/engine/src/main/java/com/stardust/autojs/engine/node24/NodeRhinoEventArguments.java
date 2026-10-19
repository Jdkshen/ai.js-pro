package com.stardust.autojs.engine.node24;

import org.json.JSONArray;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Scriptable;

/** Decode into actual JS objects: Java Map wrappers do not expose payload.content in Rhino. */
public final class NodeRhinoEventArguments {
    private NodeRhinoEventArguments() {}
    public static Object[] decode(Scriptable scope, JSONArray arguments) {
        Context context = Context.enter();
        try {
            NativeArray array = (NativeArray) NativeJSON.parse(context, scope, arguments.toString(), (cx, callScope, self, values) -> values[1]);
            Object[] values = new Object[(int) array.getLength()];
            for (int i = 0; i < values.length; i++) values[i] = array.get(i, array);
            return values;
        } finally { Context.exit(); }
    }
}
