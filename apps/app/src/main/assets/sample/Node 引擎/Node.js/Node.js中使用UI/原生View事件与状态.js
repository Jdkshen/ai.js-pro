"ui"; "nodejs";
const ui = require('ui');
class Main extends ui.Activity {
    get layoutXml() {
        return '<vertical padding="16"><text id="status" text="长按、触摸或输入后按回车"/>'
            + '<button id="button" text="长按计数"/>'
            + '<text id="touch" text="触摸这里（不消费默认事件）" height="100"/>'
            + '<input id="input" hint="输入内容后按回车" singleLine="true"/>'
            + '<button id="close" text="关闭"/></vertical>';
    }
    onContentViewSet(root) {
        const status = root.findView('status'), button = root.binding.button;
        button.extras.count = 0;
        button.on('long_click', event => {
            event.consumed = true;
            status.setText('长按 ' + (++button.extras.count) + ' 次');
        });
        root.binding.touch.on('touch_down', event => {
            status.setText('触点：' + Math.round(event.getX()) + ', ' + Math.round(event.getY()));
        });
        root.binding.input.on('key_down', (keyCode, event, view) => {
            if (keyCode !== 66) return;
            event.consumed = true;
            status.setText('输入：' + view.getText().toString());
        });
        root.binding.close.on('click', () => ui.close());
    }
}
ui.setMainActivity(Main);
