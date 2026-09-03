package org.autojs.autojs.build;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens the bundled APK-packing template and reports the legacy external plugin status.
 *
 * <p>Reconstructed from the DEX signatures preserved in the archived Debug APK. The
 * template is now shipped inside the main app as {@code assets/template.apk} (generated
 * from {@code :inrt:assembleRelease}), so this helper no longer depends on the legacy
 * external ApkBuilder plugin.</p>
 */
public class ApkBuilderPluginHelper {

    private static final String PLUGIN_PACKAGE_NAME = "org.autojs.apk_builder.plugin";
    private static final String TEMPLATE_APK_PATH = "template.apk";

    public static InputStream openTemplateApk(Context context) throws IOException {
        return context.getAssets().open(TEMPLATE_APK_PATH);
    }

    public static boolean isPluginAvailable(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PLUGIN_PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static int getPluginVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(PLUGIN_PACKAGE_NAME, 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static int getSuitablePluginVersion() {
        return 0;
    }
}
