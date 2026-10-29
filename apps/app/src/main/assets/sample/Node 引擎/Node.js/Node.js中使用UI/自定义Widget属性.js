"ui"; "nodejs";
const ui = require('ui');
class Counter extends ui.Widget {
    constructor() { super(); this.count = 0; }
    get initialAttributes() { return {count: '0', padding: '16'}; }
    get attr$count() { return String(this.count); }
    set attr$count(value) {
        this.count = Number(value) || 0;
        this.view.setText('计数 ' + this.count + '（点击增加）');
    }
    render() { return '<button/>'; }
    mounted() {
        this.view.on('click', () => this.view.attr('count', this.count + 1));
    }
}
ui.Widget.register('demo-counter', Counter);
class Main extends ui.Activity {
    get layoutXml() {
        return '<vertical padding="16"><text text="自定义 Widget：属性更新不会重建页面"/>'
            + '<demo-counter id="counter" count="3"/><button id="close" text="关闭"/></vertical>';
    }
    onContentViewSet(root) {
        const widget = ui.Widget.of(root.binding.counter);
        console.log('初始计数：', widget.getAttribute('count'));
        root.binding.close.on('click', () => ui.close());
    }
}
ui.setMainActivity(Main);
