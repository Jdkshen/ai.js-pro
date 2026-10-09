// @engine rhino
// 支持查看（data）与发送（EXTRA_STREAM / ClipData）的文本文件。
// 获取Intent参数，用于获取要处理的文件路径等参数
const intent = $engines.myEngine().execArgv.intent;
var fileUri = getFileUri(intent);
if (fileUri) {
    // 读取文本文件
    handleFile(fileUri);
    exit();
}
alert("请在任务中将本脚本添加到外部Intent触发", "动作为查看或发送，文件类型填写为text/plain");

function getFileUri(intent) {
    if (!intent) return null;
    var uri = intent.getData();
    if (uri) return uri;
    uri = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
    if (uri) return uri;
    var clip = intent.getClipData();
    return clip && clip.getItemCount() > 0 ? clip.getItemAt(0).getUri() : null;
}

function handleFile(uri) {
    // 根据安卓文档，使用contentResolver打开流，无需获取文件路径
    const stream = context.contentResolver.openInputStream(uri);
    if (!stream) throw new Error('无法打开文本文件：' + uri);
    var content;
    try {
        content = $files.read(stream);
    } finally {
        stream.close();
    }
    alert('文件' + uri + '内容', content);
}
