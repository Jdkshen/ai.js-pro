"ui"; "nodejs";
const ui = require('ui');

// render 可返回 XML，也可像本例直接返回 Android View。
class NativePanel extends ui.Widget {
    constructor() { super(); this.count = 0; }
    created() { console.log('1. created：完成 JS 构造'); }
    render(context, parent) {
        console.log('2. render：收到真实 Context，parent = ' + parent);
        const LinearLayout = $autojs.java.findClass('android.widget.LinearLayout');
        const view = new LinearLayout(context);
        view.setOrientation(1);
        return view;
    }
    rootViewCreated(view) {
        console.log('3. rootViewCreated：XML 子节点数 = ' + view.getChildCount());
    }
    get initialAttributes() {
        // 现在可以访问真实 View，且 XML 子节点已经创建。
        return {alpha: '0.65', padding: '12'};
    }
    inflated() {
        console.log('4. inflated：子节点数 = ' + this.view.getChildCount()
            + '，alpha = ' + this.view.getAlpha());
    }
    mounted() {
        console.log('5. mounted：外部 alpha 已覆盖为 ' + this.view.getAlpha());
        const label = this.view.findView('count');
        this.view.findView('increment').on('click', () => {
            label.setText('点击计数：' + (++this.count));
        });
    }
}
ui.Widget.register('demo-native-panel', NativePanel);
class Main extends ui.Activity {
    get layoutXml() {
        return '<vertical padding="16"><text text="原生 Widget：真实分阶段创建" size="20"/>'
            + '<demo-native-panel id="panel" alpha="1" padding="16">'
            + '<text id="count" text="点击计数：0"/><button id="increment" text="增加计数"/>'
            + '</demo-native-panel><button id="rebuild" text="重建界面"/>'
            + '<button id="close" text="关闭"/></vertical>';
    }
    onContentViewSet(root) {
        root.binding.rebuild.on('click', () => this.recreate());
        root.binding.close.on('click', () => ui.close());
    }
}
ui.setMainActivity(Main);
