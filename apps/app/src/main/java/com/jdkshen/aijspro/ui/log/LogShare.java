package com.jdkshen.aijspro.ui.log;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shares a UTF-8 snapshot via the system chooser, including multiline/large logs. */
public final class LogShare {
    private LogShare() { }

    public static void show(Activity activity, String text) throws IOException {
        File directory = new File(activity.getCacheDir(), "updates/log-share");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建日志分享缓存");
        }
        File file = File.createTempFile("运行日志-", ".txt", directory);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Uri uri = AppFileProvider.getUriForFile(activity, file);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("日志", uri));
        activity.startActivity(Intent.createChooser(send, "分享日志"));
    }
}
