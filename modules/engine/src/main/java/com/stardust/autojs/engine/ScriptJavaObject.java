package com.stardust.autojs.engine;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Wrapper;
import java.util.HashMap;
import java.util.Map;

/** Rhino Java wrapper that observes successful ownership-changing framework calls. */
public final class ScriptJavaObject extends NativeJavaObject {
    private final ScriptJavaResources resources;
    private final Map<String, Function> methods = new HashMap<>();

    public ScriptJavaObject(Scriptable scope, Object object, Class<?> type, ScriptJavaResources resources) {
        super(scope, object, type);
        this.resources = resources;
    }

    public static boolean supports(Object value) {
        return value instanceof android.content.Context || value instanceof android.view.WindowManager
                || value instanceof android.media.MediaPlayer || value instanceof android.media.session.MediaSession
                || value instanceof java.io.Closeable;
    }

    @Override public Object get(String name, Scriptable start) {
        Object member = super.get(name, start);
        if (!(member instanceof Function) || !observed(name)) return member;
        Function cached = methods.get(name);
        if (cached != null) return cached;
        Function original = (Function) member;
        BaseFunction wrapper = new BaseFunction() {
            @Override public String getFunctionName() { return name; }
            @Override public Object call(Context cx, Scriptable scope, Scriptable receiver, Object[] args) {
                Object result = original.call(cx, scope, receiver, args);
                Object[] javaArgs = args.clone();
                for (int i = 0; i < javaArgs.length; i++) {
                    if (javaArgs[i] instanceof Wrapper) javaArgs[i] = ((Wrapper) javaArgs[i]).unwrap();
                }
                Object target = receiver instanceof Wrapper ? ((Wrapper) receiver).unwrap() : unwrap();
                resources.called(target, name, javaArgs);
                return result;
            }
        };
        wrapper.setParentScope(getParentScope());
        wrapper.setPrototype(ScriptableObject.getFunctionPrototype(getParentScope()));
        methods.put(name, wrapper);
        return wrapper;
    }

    private static boolean observed(String name) {
        return "registerReceiver".equals(name) || "unregisterReceiver".equals(name)
                || "addView".equals(name) || "removeView".equals(name)
                || "removeViewImmediate".equals(name) || "release".equals(name) || "close".equals(name);
    }
}
