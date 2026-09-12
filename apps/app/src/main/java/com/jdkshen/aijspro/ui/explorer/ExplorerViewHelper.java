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

    /**
     * 有专属图标的类型返回图标资源，其它返回 0（回退到「首字母 + 灰底」方案）。
     * 全套图标风格统一：圆角色块底 + 白色图形符号（与 ic_code_file_24dp 的 &lt;/&gt; 一致）。
     */
    public static int getFileIconRes(ExplorerItem item) {
        if (isJavaScript(item)) {
            return R.drawable.ic_code_file_24dp;
        }
        String name = item.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".json")) {
            return R.drawable.ic_json_file_24dp;
        }
        if (name.endsWith(".md")) {
            return R.drawable.ic_markdown_file_24dp;
        }
        if (name.endsWith(".apk")) {
            return R.drawable.ic_apk_file_24dp;
        }
        return 0;
    }

    public static boolean usesCodeIcon(ExplorerItem item) {
        return getFileIconRes(item) != 0;
    }

    public static String getIconText(ExplorerItem item) {
        if (usesCodeIcon(item)) {
            // 有专属图标时不再叠字母，避免图标下面透出半个首字母。
            return "";
        }
        String type = item.getType();
        if (type.isEmpty()) {
            return TYPE_UNKNOWN;
        }
        if (type.equals(TYPE_AUTO_FILE)) {
            return "R";
        }

        return type.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    public static int getIconColor(ExplorerItem item) {
        String name = item.getName().toLowerCase(java.util.Locale.ROOT);
        if (isJavaScript(item)) {
            return Color.rgb(10, 14, 15);        // 黑：JS（对齐 Auto.js Pro 的 "<>" 黑徽章）
        }
        if (name.endsWith(".json")) {
            return Color.rgb(10, 14, 15);        // 黑：JSON（与 JS 同色，Pro 同款近黑）
        }
        if (name.endsWith(".md")) {
            return Color.rgb(30, 136, 229);      // 蓝：Markdown
        }
        if (name.endsWith(".apk")) {
            return Color.rgb(50, 215, 128);      // 亮绿：安装包（对齐 Auto.js Pro）
        }
        switch (item.getType()) {
            case TYPE_AUTO_FILE:
                return getColor(GlobalAppContext.get(), R.color.color_r);
            default:
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
