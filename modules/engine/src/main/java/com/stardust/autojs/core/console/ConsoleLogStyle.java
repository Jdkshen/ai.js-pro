package com.stardust.autojs.core.console;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared defaults for Flutter, editor panels and native/embedded consoles. */
public final class ConsoleLogStyle {
    private static final int[] DARK = {0xff9e9e9e, 0xffe6e6e6, 0xff64dd17,
            0xffffd54f, 0xffff5252, 0xffff7043};
    private static final int[] LIGHT = {0xff616161, 0xff212121, 0xff2e7d32,
            0xffbf360c, 0xffc62828, 0xffb71c1c};
    private static final String[] SHORT = {"V", "D", "I", "W", "E", "A"};
    private static final String[] LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"};
    private static final ThreadLocal<SimpleDateFormat> TIME = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        }
    };

    private ConsoleLogStyle() { }
    private static int index(int level) { return level >= 2 && level <= 7 ? level - 2 : 2; }
    public static int color(int level, boolean dark) { return (dark ? DARK : LIGHT)[index(level)]; }
    public static String shortLevel(int level) { return SHORT[index(level)]; }
    public static String levelName(int level) { return LEVELS[index(level)]; }
    public static String format(long time, int level, String name, String text) {
        String source = name == null || name.isEmpty() || "main".equals(name) ? "" : name + ": ";
        return TIME.get().format(new Date(time)) + "/" + shortLevel(level) + ": "
                + source + (text == null ? "" : text);
    }
}
