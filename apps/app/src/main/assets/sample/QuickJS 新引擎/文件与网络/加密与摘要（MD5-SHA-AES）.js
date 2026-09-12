// @engine quickjs
// 加密与摘要（MD5 / SHA-256 / AES）
// 用途：用 JDK 自带的 MessageDigest 与 Cipher 做摘要和 AES 加解密（引擎没有内置 crypto 模块）
// 前置：无
// 覆盖：Java 互操作

importClass('java.security.MessageDigest');
importClass('javax.crypto.Cipher');
importClass('javax.crypto.spec.SecretKeySpec');
importClass('android.util.Base64');

/** 字节数组（互操作里就是 JS 数字数组）→ 十六进制字符串；注意字节是有符号的，先 & 0xff */
function toHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
        var b = bytes[i] & 0xff;
        out += (b < 16 ? '0' : '') + b.toString(16);
    }
    return out;
}

/** 纯 JS 实现 UTF-8 编码，用来和 Java 解出来的字节比对 */
function utf8Hex(text) {
    var bytes = [];
    for (var i = 0; i < text.length; i++) {
        var code = text.charCodeAt(i);
        if (code < 0x80) {
            bytes.push(code);
        } else if (code < 0x800) {
            bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        } else {
            bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        }
    }
    return toHex(bytes);
}

/** 摘要，输出标准十六进制。JS 字符串会按 UTF-8 自动转成 byte[]（互操作支持） */
function digestHex(algorithm, value) {
    return toHex(MessageDigest.getInstance(algorithm).digest(value));
}

var text = 'AI.js Pro QuickJS';
console.log('原文 = ' + text);
console.log('MD5    = ' + digestHex('MD5', text));
console.log('SHA-1  = ' + digestHex('SHA-1', text));
console.log('SHA-256= ' + digestHex('SHA-256', text));
// 对照：MD5("abc") 的知名结果是 900150983cd24fb0d6963f7d28e17f72
console.log('自检 MD5("abc") = ' + digestHex('MD5', 'abc'));

// ---------------- AES（ECB/PKCS5Padding，密钥 16 字节 = AES-128）----------------
var keySpec = new SecretKeySpec('AIjsPro-QuickJS1', 'AES');      // 字符串 → byte[]；AES 密钥必须 16/24/32 字节

var encryptCipher = Cipher.getInstance('AES/ECB/PKCS5Padding');
encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec);
var encrypted = encryptCipher.doFinal(text);                      // 字符串 → byte[]
var encryptedBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
console.log('AES 密文(Base64) = ' + encryptedBase64);

var decryptCipher = Cipher.getInstance('AES/ECB/PKCS5Padding');
decryptCipher.init(Cipher.DECRYPT_MODE, keySpec);
var decrypted = decryptCipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP));
// 解出来是 byte[]（JS 数字数组）：转成十六进制和原文比对
console.log('解密结果十六进制 = ' + toHex(decrypted));
console.log('原文十六进制     = ' + utf8Hex(text));
console.log('解密结果与原文一致 = ' + (toHex(decrypted) === utf8Hex(text)));
// 想把结果变回字符串：ByteArrayOutputStream + String(bytes, "UTF-8") 目前不可用，
// 简单的做法是解密后按字节拼字符串（纯 ASCII 场景）：
var plain = '';
for (var i = 0; i < decrypted.length; i++) plain += String.fromCharCode(decrypted[i] & 0xff);
console.log('还原文本 = ' + plain);

// 小结：
// 1) 摘要（MD5/SHA）只能验证不能还原，用来校验文件/密码；
// 2) 生产环境别用 ECB，改 AES/CBC/PKCS5Padding 并配一个随机 IV（IvParameterSpec）；
// 3) 密钥不要硬编码进脚本，安卓上可以用 storages 存一份随机值，或用 Keystore 托管；
// 4) 互操作限制：new java.lang.String(...)、new BigInteger(...) 这类构造目前会报
//    TypeError: not an object（带参构造对这两类不生效），十六进制转换请在 JS 侧自己做（如上面的 toHex）。
