package com.stardust.autojs.core.graphics;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Prefer the known static CJK font bundled with the app over ROM variable fonts. */
final class ImGuiFont {
    static synchronized String resolve(Context context) {
        File font = new File(context.getCacheDir(), "imgui-NotoSansCJKsc-Regular.otf");
        if (font.canRead() && font.length() > 0) return font.getAbsolutePath();
        File temporary = new File(context.getCacheDir(), "imgui-NotoSansCJKsc-Regular.otf.tmp");
        try (InputStream source = context.getAssets().open("fonts/NotoSansCJKsc-Regular.otf");
             FileOutputStream target = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = source.read(buffer)) != -1) target.write(buffer, 0, count);
            target.flush();
        } catch (Exception error) {
            android.util.Log.w("ImGuiFont", "Bundled CJK font unavailable", error);
            temporary.delete();
            return ""; // native layer builds a checked Latin fallback
        }
        if (temporary.renameTo(font)) return font.getAbsolutePath();
        temporary.delete(); return "";
    }
}
