package com.jdkshen.aijspro.ui.explorer;

import android.graphics.Color;

import com.stardust.app.GlobalAppContext;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.explorer.ExplorerItem;
import com.jdkshen.aijspro.model.explorer.ExplorerPage;
import com.jdkshen.aijspro.model.explorer.ExplorerProjectPage;
import com.jdkshen.aijspro.model.explorer.ExplorerSamplePage;

import static androidx.core.content.ContextCompat.getColor;
import static com.jdkshen.aijspro.model.explorer.ExplorerItem.TYPE_AUTO_FILE;
import static com.jdkshen.aijspro.model.explorer.ExplorerItem.TYPE_JAVASCRIPT;
import static com.jdkshen.aijspro.model.explorer.ExplorerItem.TYPE_UNKNOWN;

public class ExplorerViewHelper {

    public static String getDisplayName(ExplorerItem item) {
        if (item instanceof ExplorerSamplePage && ((ExplorerSamplePage) item).isRoot()) {
            return GlobalAppContext.getString(R.string.text_sample);
        }
        if (item instanceof ExplorerPage) {
            return item.getName();
        }
        return item.getName();
    }

    public static boolean isJavaScript(ExplorerItem item) {
        return TYPE_JAVASCRIPT.equals(item.getType());
    }

    public static boolean usesCodeIcon(ExplorerItem item) {
        String name = item.getName().toLowerCase(java.util.Locale.ROOT);
        return isJavaScript(item) || name.endsWith(".json");
    }

    public static String getIconText(ExplorerItem item) {
        String type = item.getType();
        if (type.isEmpty()) {
            return TYPE_UNKNOWN;
        }
        if (type.equals(TYPE_AUTO_FILE)) {
            return "R";
        }
        if (type.equals(TYPE_JAVASCRIPT)) {
            return "";
        }

        return type.substring(0, 1).toUpperCase();
    }

    public static int getIconColor(ExplorerItem item) {
        switch (item.getType()) {
            case TYPE_JAVASCRIPT:
                return Color.rgb(76, 175, 80);
            case TYPE_AUTO_FILE:
                return getColor(GlobalAppContext.get(), R.color.color_r);
            default:
                if (item.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                    return Color.rgb(4, 9, 11);
                }
                return Color.GRAY;
        }
    }

    public static int getIcon(ExplorerPage page) {
        if (page instanceof ExplorerSamplePage) {
            return R.drawable.ic_sample_dir;
        }
        if(page instanceof ExplorerProjectPage){
            return R.drawable.ic_project;
        }
        return R.drawable.ic_folder_yellow_100px;
    }
}
