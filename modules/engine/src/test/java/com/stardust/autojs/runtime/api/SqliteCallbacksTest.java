package com.stardust.autojs.runtime.api;

import com.stardust.autojs.core.database.Database;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class SqliteCallbacksTest {
    private Database.Callbacks callbacks(Scriptable object) throws Exception {
        Method method = Sqlite.class.getDeclaredMethod("adaptCallbacks", Object.class);
        method.setAccessible(true);
        return (Database.Callbacks) method.invoke(null, object);
    }

    @Test public void lifecycleCallbacksKeepScopeAndUpgradeArguments() throws Exception {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Scriptable object = (Scriptable) cx.evaluateString(scope,
                    "var calls=[]; ({onCreate:function(db){calls.push('create');},onUpgrade:function(db,oldV,newV){calls.push(oldV+'>'+newV);},onOpen:function(db){calls.push('open');}})",
                    "sqlite-test", 1, null);
            Database.Callbacks callbacks = callbacks(object);
            callbacks.onCreate(null);
            callbacks.onUpgrade(null, 1, 2);
            callbacks.onOpen(null);
            assertEquals("create|1>2|open", Context.toString(cx.evaluateString(scope, "calls.join('|')", "sqlite-test", 1, null)));
        } finally { Context.exit(); }
    }

    @Test public void migrationExceptionReachesHelperCaller() throws Exception {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Scriptable object = (Scriptable) cx.evaluateString(scope,
                    "({onUpgrade:function(){throw new Error('migration rejected');}})", "sqlite-test", 1, null);
            try { callbacks(object).onUpgrade(null, 1, 2); fail("Migration exception must propagate"); }
            catch (JavaScriptException expected) { assertTrue(expected.getMessage().contains("migration rejected")); }
        } finally { Context.exit(); }
    }
}