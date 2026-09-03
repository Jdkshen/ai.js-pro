package org.autojs.autojs.theme;

import android.graphics.Color;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.stardust.autojs.core.console.ConsoleView;

import org.autojs.autojs.R;

/** Applies the shared app palette to every part of the legacy console widget. */
public final class ConsoleThemeHelper {

    private ConsoleThemeHelper() {
    }

    public static void apply(ConsoleView consoleView, AppThemePalette palette) {
        if (consoleView == null || palette == null) return;

        SparseArray<Integer> colors = new SparseArray<>();
        colors.put(Log.VERBOSE, palette.textSecondary);
        colors.put(Log.DEBUG, palette.textPrimary);
        colors.put(Log.INFO, palette.isDark ? Color.rgb(100, 221, 23) : Color.rgb(46, 125, 50));
        colors.put(Log.WARN, palette.isDark ? Color.rgb(255, 213, 79) : Color.rgb(230, 81, 0));
        colors.put(Log.ERROR, palette.danger);
        colors.put(Log.ASSERT, palette.isDark ? Color.rgb(255, 112, 106) : Color.rgb(183, 28, 28));
        consoleView.setColors(colors);
        consoleView.setBackgroundColor(palette.surfacePrimary);

        View logList = consoleView.findViewById(R.id.log_list);
        if (logList != null) logList.setBackgroundColor(palette.surfacePrimary);
        View inputContainer = consoleView.findViewById(R.id.input_container);
        if (inputContainer != null) inputContainer.setBackgroundColor(palette.surfaceSecondary);
        EditText input = consoleView.findViewById(R.id.input);
        if (input != null) {
            input.setTextColor(palette.textPrimary);
            input.setHintTextColor(palette.textSecondary);
        }
        Button submit = consoleView.findViewById(R.id.submit);
        if (submit != null) submit.setTextColor(palette.textPrimary);
    }
}
