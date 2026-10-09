package com.jdkshen.aijspro.ui.common;

import android.content.Context;
import android.widget.Toast;

import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.model.explorer.ExplorerItem;
import com.jdkshen.aijspro.model.script.Scripts;
import com.stardust.util.IntentUtil;
import com.stardust.util.MimeTypes;

import java.util.Locale;

/** One tap policy shared by the legacy and Miuix file lists. */
public final class FileOpenRouter {
    private FileOpenRouter() {
    }

    public static void open(Context context, ExplorerItem item) {
        if (context == null || item == null) return;
        if (item.isEditable()) {
            Scripts.INSTANCE.edit(context, item.toScriptFile());
            return;
        }

        String path = item.getPath();
        String extension = item.getType();
        String type = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if ("apk".equals(type)) {
            IntentUtil.installApkOrToast(context, path, AppFileProvider.AUTHORITY);
            return;
        }

        String mimeType = MimeTypes.fromFile(path);
        if (mimeType == null || "*/*".equals(mimeType)) {
            Toast.makeText(context, "此格式不能直接预览；可从文件菜单选择“用其他应用打开”",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (!IntentUtil.viewFile(context, path, mimeType, AppFileProvider.AUTHORITY)) {
                Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException error) {
            Toast.makeText(context, "打开失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
