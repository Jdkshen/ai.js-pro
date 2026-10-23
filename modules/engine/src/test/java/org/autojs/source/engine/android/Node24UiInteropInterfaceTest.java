package org.autojs.source.engine.android;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.SeekBar;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P1-2: a JS object literal reaches Java as {@code $map} of {@code $callback}
 * values. These tests exercise the real conversion layer (dynamic proxies over
 * the actual Android listener interfaces), not just source text.
 */
public class Node24UiInteropInterfaceTest {

    private static final class RecordingCallback implements Node24JavaArguments.Callback {
        private final String label;
        private final List<String> calls;

        RecordingCallback(String label, List<String> calls) {
            this.label = label;
            this.calls = calls;
        }

        @Override
        public Object asInterface(Class<?> type) {
            return Node24JavaArguments.interfaceProxy(type, (method, args) -> {
                calls.add(label + "." + method.getName());
                return Node24JavaArguments.defaultValue(method.getReturnType());
            });
        }
    }

    public static class Target {
        TextWatcher watcher;

        public void setWatcher(TextWatcher watcher) {
            this.watcher = watcher;
        }
    }

    private static Map<String, Object> textWatcherMethods(List<String> calls) {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("beforeTextChanged", new RecordingCallback("before", calls));
        methods.put("onTextChanged", new RecordingCallback("on", calls));
        methods.put("afterTextChanged", new RecordingCallback("after", calls));
        return methods;
    }

    @Test
    public void textWatcherObjectLiteralAdaptsToRealInterface() {
        List<String> calls = new ArrayList<>();
        Map<String, Object> methods = textWatcherMethods(calls);
        assertTrue(Node24JavaArguments.compatible(TextWatcher.class, methods));

        TextWatcher watcher = (TextWatcher) Node24JavaArguments.coerce(TextWatcher.class, methods);
        watcher.beforeTextChanged(null, 0, 0, 1);
        watcher.onTextChanged(null, 0, 0, 1);
        Editable editable = null;
        watcher.afterTextChanged(editable);

        assertEquals(Arrays.asList(
                "before.beforeTextChanged", "on.onTextChanged", "after.afterTextChanged"), calls);
    }

    @Test
    public void seekBarListenerObjectLiteralCoversAllThreeCallbacks() {
        List<String> calls = new ArrayList<>();
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("onProgressChanged", new RecordingCallback("progress", calls));
        methods.put("onStartTrackingTouch", new RecordingCallback("start", calls));
        methods.put("onStopTrackingTouch", new RecordingCallback("stop", calls));

        assertTrue(Node24JavaArguments.compatible(SeekBar.OnSeekBarChangeListener.class, methods));
        SeekBar.OnSeekBarChangeListener listener = (SeekBar.OnSeekBarChangeListener)
                Node24JavaArguments.coerce(SeekBar.OnSeekBarChangeListener.class, methods);
        listener.onStartTrackingTouch(null);
        listener.onProgressChanged(null, 42, true);
        listener.onStopTrackingTouch(null);

        assertEquals(Arrays.asList(
                "start.onStartTrackingTouch", "progress.onProgressChanged", "stop.onStopTrackingTouch"), calls);
    }

    @Test
    public void incompleteOrNonCallableMapsAreNotMistakenForListeners() {
        List<String> calls = new ArrayList<>();
        Map<String, Object> partial = textWatcherMethods(calls);
        partial.remove("afterTextChanged");
        assertFalse(Node24JavaArguments.compatible(TextWatcher.class, partial));

        Map<String, Object> nonCallable = new LinkedHashMap<>();
        nonCallable.put("onProgressChanged", "not a callback");
        nonCallable.put("onStartTrackingTouch", new RecordingCallback("start", calls));
        nonCallable.put("onStopTrackingTouch", new RecordingCallback("stop", calls));
        assertFalse(Node24JavaArguments.compatible(SeekBar.OnSeekBarChangeListener.class, nonCallable));

        assertFalse(Node24JavaArguments.compatible(TextWatcher.class, new LinkedHashMap<String, Object>()));
    }

    @Test
    public void reflectionInvocationAcceptsObjectLiteralForRealInterfaceParameter() throws Exception {
        Object value = textWatcherMethods(new ArrayList<String>());
        Method setter = Target.class.getMethod("setWatcher", TextWatcher.class);
        Class<?> parameter = setter.getParameterTypes()[0];
        assertTrue(Node24JavaArguments.compatible(parameter, value));

        Target target = new Target();
        setter.invoke(target, Node24JavaArguments.coerce(parameter, value));
        assertTrue(target.watcher != null);
    }

    @Test
    public void interfacesWithDefaultMethodsRequireTheExplicitMarker() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("compare", new RecordingCallback("compare", new ArrayList<String>()));
        // A map proxy would silently skip Comparator.reversed()/thenComparing(), so
        // automatic $map adaptation deliberately declines interfaces with defaults.
        assertFalse(Node24JavaArguments.compatible(Comparator.class, methods));
    }

    @Test
    public void mapParametersStillReceiveTheOriginalMap() {
        Map<String, Object> methods = textWatcherMethods(new ArrayList<String>());
        assertTrue(Node24JavaArguments.compatible(Map.class, methods));
        assertSame(methods, Node24JavaArguments.coerce(Map.class, methods));
        methods.put("count", 0);
        assertTrue(Node24JavaArguments.compatible(TextWatcher.class, methods));
    }

    @Test
    public void singleFunctionCallbackStillWorksForSamInterfaces() {
        final boolean[] ran = {false};
        Node24JavaArguments.Callback callback = type -> {
            assertSame(Runnable.class, type);
            return (Runnable) () -> ran[0] = true;
        };
        assertTrue(Node24JavaArguments.compatible(Runnable.class, callback));
        Runnable runnable = (Runnable) Node24JavaArguments.coerce(Runnable.class, callback);
        runnable.run();
        assertTrue(ran[0]);

        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("run", callback);
        assertTrue(Node24JavaArguments.compatible(Runnable.class, methods));
        ((Runnable) Node24JavaArguments.coerce(Runnable.class, methods)).run();
        assertTrue(ran[0]);
    }

    @Test
    public void proxyBehavesLikeAJavaObject() {
        Runnable proxy = (Runnable) Node24JavaArguments.interfaceProxy(
                Runnable.class, (method, args) -> null);
        assertEquals(proxy, proxy);
        assertTrue(proxy.equals(proxy));
        assertFalse(proxy.equals(null));
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        assertTrue(proxy.toString().contains(Runnable.class.getName()));

        JvmTestAssertions.assertThrows(IllegalArgumentException.class,
                () -> Node24JavaArguments.interfaceProxy(String.class, (method, args) -> null));
    }
}
