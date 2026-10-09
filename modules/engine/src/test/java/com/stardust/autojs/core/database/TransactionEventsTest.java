package com.stardust.autojs.core.database;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;

import static org.junit.Assert.*;

public class TransactionEventsTest {
    @Test
    public void replayedErrorListenerCanRethrow() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            TransactionEvents events = new TransactionEvents();
            events.occur("error", "migration failed");
            Function listener = cx.compileFunction(scope,
                    "function(error){ throw new Error(error); }", "test", 1, null);
            try {
                events.on("error", listener);
                fail("Listener exception was swallowed");
            } catch (JavaScriptException expected) {
                assertTrue(expected.getMessage().contains("migration failed"));
            }
        } finally {
            Context.exit();
        }
    }

    @Test
    public void registeredCommitListenerStillReceivesItsPayload() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            cx.evaluateString(scope, "var result = null;", "test", 1, null);
            TransactionEvents events = new TransactionEvents();
            assertSame(events, events.on("commit", cx.compileFunction(scope,
                    "function(value){ result = value; }", "test", 1, null)));
            events.occur("commit", "committed");
            assertEquals("committed", Context.toString(scope.get("result", scope)));
        } finally {
            Context.exit();
        }
    }
    public static class Payload {
        public String getName() { return "transaction"; }
    }

    @Test
    public void replayedJavaPayloadIsWrappedForScriptTruthinessAndMethods() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            cx.evaluateString(scope, "var result = null;", "test", 1, null);
            TransactionEvents events = new TransactionEvents();
            events.occur("end", new Payload());
            events.on("end", cx.compileFunction(scope,
                    "function(value){ if(!value) throw new Error('missing payload'); result=value.getName(); }", "test", 1, null));
            assertEquals("transaction", Context.toString(scope.get("result", scope)));
        } finally { Context.exit(); }
    }
}
