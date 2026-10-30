"ui"; "nodejs";
const ui = require('ui');
class CustomButton extends ui.Widget {
    // 已覆盖 button，使用完整类名创建原生按钮，避免再次进入自己。
    render() { return '<android.widget.Button/>'; }
    set attr$label(value) { this.view.setText(value); }
    mounted() { this.view.on('click', () => console.log('自定义按钮已点击')); }
}
class Container extends ui.Widget {
    render() {
        // layoutXml 中 button 已经出现，但子节点尚未创建，可以在此注册。
        ui.Widget.register('button', CustomButton);
        return '<column><frame id="slot"/></column>';
    }
}
ui.Widget.register('late-container', Container);
class Main extends ui.Activity {
    get layoutXml() {
        return '<column padding="16"><text text="内置标签也可以注册为 Widget" size="20"/>'
            + '<late-container><button label="点击查看日志"/></late-container>'
            + '<android.widget.Button id="close" text="关闭"/></column>';
    }
    onContentViewSet(root) { root.binding.close.on('click', () => ui.close()); }
}
ui.setMainActivity(Main);
