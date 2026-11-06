"rhino";
var until = Date.now() + 30000;
while (Date.now() < until) {
 try {
  var edit = className('android.widget.EditText').findOnce();
  if (edit && String(edit.packageName()) === 'com.jdkshen.aijspro' && String(edit.text()) !== 'AI.js API Test') {
   edit.setText('AI.js API Test'); console.log('MI8_APP_DRIVER input own test target');
  }
  var choice = text('获取应用包名').findOnce();
  if (choice && String(choice.packageName()) === 'com.jdkshen.aijspro') choice.click();
  var accept = text('确定').findOnce() || text('确认').findOnce() || text('OK').findOnce();
  if (accept && String(accept.packageName()) === 'com.jdkshen.aijspro') accept.click();
 } catch (ignored) {}
 sleep(400);
}
