package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.theme.dialog.ThemeColorMaterialDialogBuilder;
import com.stardust.app.DialogUtils;
import com.stardust.util.ClipboardUtil;
import com.stardust.view.accessibility.LayoutInspector;

/** Displays the last capture report without collecting node text. */
public final class LayoutInspectorDiagnostics {
    private static MaterialDialog sDialog;

    private LayoutInspectorDiagnostics() {}

    public static void show(Context context, LayoutInspector inspector) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> show(context, inspector));
            return;
        }
        if (sDialog != null) sDialog.dismiss();
        final String report = inspector.getCaptureLog();
        Context themed = new ContextThemeWrapper(context, R.style.AppTheme);
        MaterialDialog dialog = new ThemeColorMaterialDialogBuilder(themed)
                .title(R.string.text_layout_inspector_diagnostics)
                .content(report)
                .positiveText(R.string.text_layout_inspector_copy_log)
                .negativeText(R.string.cancel)
                .onPositive((d, which) -> {
                    ClipboardUtil.setClip(themed, report);
                    Toast.makeText(themed, R.string.text_already_copy_to_clip, Toast.LENGTH_SHORT).show();
                })
                .dismissListener(d -> {
                    if (sDialog == d) sDialog = null;
                })
                .build();
        if (dialog.getContentView() != null) dialog.getContentView().setTextIsSelectable(true);
        sDialog = dialog;
        DialogUtils.showDialog(dialog);
    }
}
