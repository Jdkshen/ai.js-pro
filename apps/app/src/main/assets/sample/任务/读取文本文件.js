// 可在任务管理中把本脚本配置为处理 text/plain 的 VIEW 或 SEND Intent。
var intent = engines.myEngine().execArgv.intent;

if (!intent || !intent.getData()) {
    dialogs.alert(
        "读取文本文件",
        "请在任务管理中添加本脚本，动作选择查看或发送，文件类型填写 text/plain。"
    );
    exit();
}

var stream = null;
var reader = null;
try {
    stream = context.getContentResolver().openInputStream(intent.getData());
    reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, "UTF-8"));
    var lines = [];
    var line;
    while ((line = reader.readLine()) != null) {
        lines.push(String(line));
    }
    dialogs.alert("文件：" + intent.getData(), lines.join("\n"));
} finally {
    if (reader) {
        reader.close();
    } else if (stream) {
        stream.close();
    }
}
