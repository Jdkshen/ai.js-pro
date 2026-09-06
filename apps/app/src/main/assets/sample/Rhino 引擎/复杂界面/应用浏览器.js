"ui";

var packageManager = context.getPackageManager();
var iconCache = {};

var IconView = (function () {
    util.extend(IconView, ui.Widget);

    function IconView() {
        ui.Widget.call(this);
        var widget = this;
        this.defineAttr("packageName", function () {
            return widget._packageName;
        }, function (view, name, value) {
            widget._packageName = value;
            view.setImageDrawable(iconCache[value]);
        });
    }

    IconView.prototype.render = function () {
        return <img w="56" h="56" scaleType="fitCenter" />;
    };

    ui.registerWidget("appicon", IconView);
    return IconView;
})();

var apps = [];

ui.layout(
    <vertical bg="#ffffff">
        <progressbar id="progress" indeterminate="true"
            style="@style/Base.Widget.AppCompat.ProgressBar.Horizontal" />
        <list id="apps" layout_weight="1">
            <horizontal w="*" padding="12 8" gravity="center_vertical"
                bg="?selectableItemBackground">
                <appicon packageName="{{this.packageName}}" />
                <vertical layout_weight="1" marginLeft="12">
                    <text text="{{this.appName}}" textSize="16sp" textColor="#202124"
                        maxLines="1" ellipsize="end" />
                    <text text="{{this.packageName}}" textSize="13sp" textColor="#777777"
                        maxLines="1" ellipsize="end" />
                    <text text="版本 {{this.versionName}} ({{this.versionCode}})"
                        textSize="12sp" textColor="#999999" />
                </vertical>
            </horizontal>
        </list>
    </vertical>
);

ui.apps.setDataSource(apps);
ui.apps.on("item_click", function (item) {
    toast(item.appName + "\n" + item.packageName);
});

threads.start(function () {
    var installed = packageManager.getInstalledPackages(0);
    var loaded = [];
    for (var i = 0; i < installed.size(); i++) {
        var info = installed.get(i);
        var label = info.applicationInfo.loadLabel(packageManager).toString();
        iconCache[info.packageName] = info.applicationInfo.loadIcon(packageManager);
        loaded.push({
            appName: label,
            packageName: info.packageName,
            versionName: info.versionName || "-",
            versionCode: info.versionCode
        });
    }
    loaded.sort(function (left, right) {
        return left.appName.localeCompare(right.appName);
    });
    ui.run(function () {
        Array.prototype.push.apply(apps, loaded);
        ui.progress.setVisibility(8);
    });
});
