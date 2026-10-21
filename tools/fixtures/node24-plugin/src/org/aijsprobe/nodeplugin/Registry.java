package org.aijsprobe.nodeplugin;
import android.content.Context;
public final class Registry {
    public static Object loadDefault(Context host, Context plugin, Object runtime, Object scope) {
        return new FixturePlugin(plugin);
    }
}
