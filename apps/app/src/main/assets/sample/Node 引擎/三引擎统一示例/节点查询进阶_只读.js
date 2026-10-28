"nodejs";
// 只读取当前界面，不点击、不修改输入和剪贴板。需先开启无障碍。
// filter/addFilter 回调使用 n.text()；BFS 不与本地谓词混用。
var S = require('accessibility').select;
var firstDfs=S().className('android.widget.Button').algorithm('DFS').findOnce();
var firstBfs=S().className('android.widget.Button').algorithm('BFS').findOnce();
console.log('NODE_ADVANCED Node DFS:', firstDfs ? String(firstDfs.text()) : '(无按钮)');
console.log('NODE_ADVANCED Node BFS:', firstBfs ? String(firstBfs.text()) : '(无按钮)');
var texts=S().className('android.widget.TextView').filter(function(n){return String(n.text()).length>0;}).find();
console.log('NODE_ADVANCED Node 可读文本数量:',texts.size());
for(var i=0;i<Math.min(3,texts.size());i++) console.log(String(texts.get(i).text()).slice(0,100));
var slider=S().className('android.widget.SeekBar').findOnce();
var range=slider ? slider.getRangeInfo() : null;
console.log('NODE_ADVANCED Node 范围:',range ? [range.getMin(),range.getMax(),range.getCurrent(),range.getType()] : '(控件不提供范围)');
console.log('NODE_ADVANCED_DONE Node');
