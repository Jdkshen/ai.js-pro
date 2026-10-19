package com.jdkshen.aijspro.flutterpoc;

/** Same LogPage widget, with a root route that returns directly to the native editor. */
public final class UnifiedLogFlutterActivity extends GlassFlutterActivity {
    @Override public String getDartEntrypointFunctionName() { return "logMain"; }
}
