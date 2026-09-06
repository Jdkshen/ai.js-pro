// 消息处理：加密、摘要、编码（Rhino / crypto 模块）
// 演示 MD5 / SHA-1 / SHA-256 / HMAC-SHA256 / Base64

var text = 'AI.js Pro 你好 Hello 123';

log('原文: ' + text);
log('MD5    = ' + crypto.md5(text));
log('SHA-1  = ' + crypto.sha1(text));
log('SHA256 = ' + crypto.sha256(text));
log('HMAC   = ' + crypto.hmacSha256(text, 'secret-key'));

var encoded = crypto.base64Encode(text);
log('Base64 = ' + encoded);
log('解码   = ' + crypto.base64Decode(encoded));

// 常用场景：校验字符串是否相等
log('MD5 是否为 32 位十六进制: ' + (crypto.md5('a').length === 32));
