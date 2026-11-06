"rhino";
// Only drive the explicitly selected dialog samples in this app.
var until = Date.now() + 45000;
var edits = [];
var choices = [];
function owned(node) { return node && String(node.packageName()) === 'com.jdkshen.aijspro'; }
while (Date.now() < until) {
    try {
            var input = className('android.widget.EditText').findOnce();
            if (owned(input) && !text('输入测试 2/2').exists() && String(input.text()) !== '3') {
                input.setText('3');
                edits.push(String(input.bounds()));
                console.log('MI8_DIALOG_ACTION input=3');
            }
            var demo = text('确认框与提示框').findOnce();
            if (owned(demo)) { demo.click(); console.log('MI8_DIALOG_ACTION Node confirm branch'); }
            var choice = className('android.widget.CheckedTextView').findOnce();
            var titleNode = id('android:id/alertTitle').findOnce();
            var key = choice ? (titleNode ? String(titleNode.text()) : '') + String(choice.bounds()) + String(choice.text()) : '';
            if (owned(choice) && choices.indexOf(key) < 0) {
                choice.click();
                choices.push(key);
                console.log('MI8_DIALOG_ACTION first choice');
            }
            var accept = id('android:id/button1').findOnce();
            if (!accept) {
                var labels = ['确定', '确认', 'OK', '继续', '完成', '知道了', '好'];
                for (var i = 0; i < labels.length && !accept; i++) accept = text(labels[i]).findOnce();
            }
            if (owned(accept) && accept.enabled()) {
                accept.click();
                console.log('MI8_DIALOG_ACTION accept');
            }
    } catch (ignored) {
        // A dismissed dialog can invalidate its node between lookup and click.
    }
    sleep(450);
}
