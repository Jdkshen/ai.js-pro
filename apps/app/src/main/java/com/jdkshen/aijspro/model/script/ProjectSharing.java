package com.jdkshen.aijspro.model.script;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.stardust.util.MimeTypes;

import java.io.File;

/** Shares source projects as ZIP files and ordinary files through the same URI permission path. */
public final class ProjectSharing {
    private ProjectSharing() {}

    public static void share(Context context, File source) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (!canShow(context)) return;
            if (!source.isDirectory()) {
                launchChooser(context, source, R.string.text_send);
                return;
            }
            Toast.makeText(context, R.string.text_preparing_project_share, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    File archive = ProjectArchive.create(source, new File(context.getCacheDir(), "project-share"));
                    main.post(() -> {
                        if (canShow(context)) launchChooser(context, archive, R.string.text_share_project);
                    });
                } catch (Exception error) {
                    main.post(() -> showFailure(context, error));
                }
            }, "project-share").start();
        });
    }

    public static Intent createSendIntent(Context context, File file) {
        if (!file.isFile()) throw new IllegalArgumentException("Shared file does not exist: " + file);
        Uri uri = AppFileProvider.getUriForFile(context, file);
        String mime = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? "application/zip" : MimeTypes.fromFileOr(file.getPath(), "text/plain");
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(file.getName(), uri));
        return intent;
    }

    private static void launchChooser(Context context, File file, int titleResource) {
        try {
            Intent chooser = Intent.createChooser(createSendIntent(context, file), context.getString(titleResource));
            if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(context, R.string.text_no_share_app, Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            showFailure(context, error);
        }
    }

    private static boolean canShow(Context context) {
        return !(context instanceof Activity) || !((Activity) context).isFinishing() && !((Activity) context).isDestroyed();
    }

    private static void showFailure(Context context, Exception error) {
        if (canShow(context)) Toast.makeText(context,
                context.getString(R.string.text_project_share_failed, error.getMessage()), Toast.LENGTH_LONG).show();
    }
}
