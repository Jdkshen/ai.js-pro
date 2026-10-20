package com.stardust.autojs.core.console;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import com.stardust.autojs.engine.node24.NodeUiLocalSession;

/** Compatibility name for project XML, using the application's shared console renderer. */
public class FileConsoleView extends ConsoleView {
    public FileConsoleView(Context context) { super(context); bind(); }
    public FileConsoleView(Context context, AttributeSet attrs) { super(context, attrs); bind(); }
    public FileConsoleView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); bind(); }
    private void bind() {
        setInputEnabled(false);
        NodeUiLocalSession.bindConsoleView(this);
    }
    public View getContentView() { return findViewById(com.stardust.autojs.R.id.log_list); }
    public void setLogLevel(String level) {
        int minimum;
        switch (level == null ? "" : level.toUpperCase(java.util.Locale.ROOT)) {
            case "V": minimum = Log.VERBOSE; break;
            case "D": minimum = Log.DEBUG; break;
            case "I": minimum = Log.INFO; break;
            case "W": minimum = Log.WARN; break;
            case "E": minimum = Log.ERROR; break;
            case "A": minimum = Log.ASSERT; break;
            default: throw new IllegalArgumentException("未知日志级别：" + level);
        }
        setMinimumLogLevel(minimum);
    }
}
