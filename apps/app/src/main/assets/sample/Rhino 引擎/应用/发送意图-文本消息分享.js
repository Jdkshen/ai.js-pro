"rhino";
var content = rawInput('请输入要分享的文本');
if (content == null) exit();
// 打开系统分享选择器，由用户决定目标并最终发送。
app.startActivity({ action: "android.intent.action.SEND", type: "text/plain",
    extras: { "android.intent.extra.TEXT": content } });
