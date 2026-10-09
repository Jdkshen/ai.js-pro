// @engine rhino
"ui";

var IconView = (function() {
    //继承ui.Widget
    util.extend(IconView, ui.Widget);

    function IconView() {
        //调用父类构造函数
        ui.Widget.call(this);
        //自定义属性color，定义按钮颜色
        this.defineAttr("icon", (view, name, defaultGetter) => {
            return this._icon;
        }, (view, name, value, defaultSetter) => {
            this._icon = value;
            // this.icon 绑定的是 loadIcon() 得到的 Drawable；绑定管线可能把值字符串化，
            // 此时无法还原为 Drawable —— 忽略图标即可，不让演示因图标而失败。
            try {
                view.setImageDrawable(value);
            } catch (e) {
                try {
                    view.setImageResource(value);
                } catch (e2) {
                    // 图标不可用：保留文本展示即可
                }
            }
        });
    }
    IconView.prototype.render = function() {
        return (
            <img />
        );
    }
    ui.registerWidget("icon", IconView);
    return IconView;
})();

var apps = [];

ui.layout(
    <vertical  bg="#ffffff">
        <list id="apps" layout_weight="1">
            <linear bg="?selectableItemBackground">
                <icon icon="{{this.icon}}" w="50" h="70" margin="16" />
                <vertical>
                    <text id="name" textSize="16sp" textColor="#000000" text="{{this.appName}}" marginTop="16" maxLines="1" ellipsize="end"/>
                    <text id="path" textSize="13sp" textColor="#929292" text="{{this.packageName}}" marginTop="8" maxLines="1" ellipsize="end"/>
                </vertical>
            </linear>
        </list>
        <progressbar id="progressbar" indeterminate="true" style="@style/Base.Widget.AppCompat.ProgressBar.Horizontal"/>
    </vertical>
);

ui.apps.setDataSource(apps);

ui.apps.on("item_click", function(item, pos){
    toast(item);
});

//启动线程来扫描应用（原示例遗留：musicFiles 未定义，统一用 apps，填充后刷新列表）
threads.start(function () {
    listApps(apps);
    ui.run(()=> {
        ui.apps.setDataSource(apps);
        ui.progressbar.setVisibility(8);
    });
});

function listApps(apps) {
    var pm = context.getPackageManager();
    let list = pm.getInstalledPackages(0);
    for(let i = 0; i < list.size(); i++){
        let p = list.get(i);
        apps.push({
            appName: p.applicationInfo.loadLabel(pm).toString(),
            packageName: p.packageName,
            versionName: p.versionName,
            versionCode: p.versionCode,
            icon: p.applicationInfo.loadIcon(pm)
        });
    }
}
