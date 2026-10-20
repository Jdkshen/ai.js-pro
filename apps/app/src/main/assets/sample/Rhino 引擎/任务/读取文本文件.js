"rhino";
// 获取Intent参数，用于获取要处理的文件路径等参数
var intent = $engines.myEngine().execArgv.intent;
// 若Intent不为空，则是打开外部文件触发的脚本执行
if (intent) {
    // 读取文本文件
    handleIntent(intent);
    exit();
}
alert("请在任务中将本脚本添加到外部Intent触发", "动作为查看或发送，文件类型填写为text/plain");

// 外部文件可能放在 Intent.data、EXTRA_STREAM 或 ClipData 里，按顺序取第一个可用 URI。
function pickUri(intent) {
    var uri = null;
    if (typeof intent.getData === "function") {
        uri = intent.getData();
    } else {
        uri = intent.data;
    }
    if (!uri && typeof intent.getParcelableExtra === "function") {
        var key = (typeof android !== "undefined" && android.content && android.content.Intent)
            ? android.content.Intent.EXTRA_STREAM : "stream";
        uri = intent.getParcelableExtra(key);
    }
    if (!uri && typeof intent.getClipData === "function") {
        var clip = intent.getClipData();
        if (clip && typeof clip.getItemCount === "function" && clip.getItemCount() > 0) {
            var item = clip.getItemAt(0);
            if (item && typeof item.getUri === "function") {
                uri = item.getUri();
            }
        }
    }
    return uri;
}

function handleIntent(intent) {
    var uri = pickUri(intent);
    if (!uri) {
        alert("请在任务中将本脚本添加到外部Intent触发", "动作为查看或发送，文件类型填写为text/plain");
        return;
    }
    // 根据安卓文档，使用 contentResolver 打开流，无需获取文件路径。
    var stream = context.contentResolver.openInputStream(uri);
    try {
        var content = $files.read(stream);
        alert("文件" + uri + "内容", content);
    } finally {
        // 读取失败也要关流；异常继续向上抛出，调用方可看到真实错误。
        stream.close();
    }
}
