"ui";
// ui.layout(files.read("src/layout/activity_main.xml"));
var _importClass = importClass;
importClass = function(c) {
    try { return _importClass(c); } catch (e) {
        if (String(c).indexOf("DocumentFile") >= 0) return undefined;
        throw e;
    }
};
ui.useAndroidResources();
global.androidx = Packages.androidx;
global.ThemeColors = storages.create("App").get("ThemeColors") || "#0061A6";
if (!ui.R.string) ui.R.string = {};
ui.R.string.openDrawerContentDesc = 0;
ui.R.drawable.ic_home_black_48dp = android.R.drawable.ic_menu_compass;
ui.R.drawable.ic_dashboard_black_48dp = android.R.drawable.ic_menu_view;
ui.R.drawable.ic_settings_applications_black_48dp = android.R.drawable.ic_menu_manage;
ui.R.drawable.ic_done_all_black_48dp = android.R.drawable.ic_menu_add;
ui.R.drawable.ic_cancel_black_48dp = android.R.drawable.ic_menu_close_clear_cancel;
require("./MainActivity.js");
