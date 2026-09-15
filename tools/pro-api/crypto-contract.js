// @engine rhino
// $crypto 行为验收（对照 335 页 PDF §2.8 第 73~80 页）
// 跑法：push 到设备后 am start 打开（见 tools/pro-api/README.md），或直接在主界面运行。
// 黄金向量取自文档第 74~75 页：message="Hello Autojs"、key=new $crypto.Key("a"*16)、AES。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

var MESSAGE = "Hello Autojs";
var STR16 = "aaaaaaaaaaaaaaaa";
var GOLDEN_B64 = "7hu7UQKpjBeOqpEoOoHjxQ==";
var GOLDEN_BYTES = "-18,27,-69,81,2,-87,-116,23,-114,-86,-111,40,58,-127,-29,-59";

var key = new $crypto.Key(STR16);

// 1) 黄金向量：与文档完全一致
check("AES 黄金向量(base64)", $crypto.encrypt(MESSAGE, key, "AES", { output: "base64" }), GOLDEN_B64);
check("AES/ECB/PKCS5Padding 与 AES 等价",
    $crypto.encrypt(MESSAGE, key, "AES/ECB/PKCS5Padding", { output: "base64" }), GOLDEN_B64);

var bytes = $crypto.encrypt(MESSAGE, key, "AES");
var joined = [];
for (var i = 0; i < bytes.length; i++) { joined.push(bytes[i]); }
check("默认输出 bytes(数组)", joined.join(","), GOLDEN_BYTES);

// 2) 往返
check("decrypt(base64 -> string)",
    $crypto.decrypt($crypto.encrypt(MESSAGE, key, "AES", { output: "base64" }), key, "AES",
        { input: "base64", output: "string" }), MESSAGE);
check("decrypt(hex -> string)",
    $crypto.decrypt($crypto.encrypt(MESSAGE, key, "AES", { output: "hex" }), key, "AES",
        { input: "hex", output: "string" }), MESSAGE);

// 3) key 直接用字符串（不是 Key 对象）
check("字符串 key", $crypto.encrypt(MESSAGE, STR16, "AES", { output: "base64" }), GOLDEN_B64);

// 4) AES/CBC + iv（Pro 9.2.12 新增的 iv 选项）
var iv = "0123456789abcdef";
var cbc = $crypto.encrypt(MESSAGE, key, "AES/CBC/PKCS5Padding", { output: "base64", iv: iv });
check("AES/CBC 往返",
    $crypto.decrypt(cbc, key, "AES/CBC/PKCS5Padding", { input: "base64", output: "string", iv: iv }), MESSAGE);

// 5) RSA 密钥对
var pair = $crypto.generateKeyPair("RSA", 2048);
check("generateKeyPair 返回 publicKey/privateKey", (pair.publicKey && pair.privateKey) ? "yes" : "no", "yes");
check("Key 带 data/algorithm/format",
    (pair.publicKey.data && pair.publicKey.algorithm === "RSA" && pair.publicKey.format) ? "yes" : "no", "yes");
var rsaB64 = $crypto.encrypt(MESSAGE, pair.publicKey, "RSA/ECB/PKCS1Padding", { output: "base64" });
check("RSA 往返",
    $crypto.decrypt(rsaB64, pair.privateKey, "RSA/ECB/PKCS1Padding", { input: "base64", output: "string" }), MESSAGE);

// 6) 用 base64 重建 KeyPair（PDF 第 79 页示例）
var enc = android.util.Base64.encodeToString;
var pubB64 = enc(pair.publicKey.data, android.util.Base64.NO_WRAP);
var privB64 = enc(pair.privateKey.data, android.util.Base64.NO_WRAP);
var copied = new $crypto.KeyPair(pubB64, privB64, { input: "base64" });
check("重建 KeyPair 后可解密",
    $crypto.decrypt($crypto.encrypt(MESSAGE, copied.publicKey, "RSA/ECB/PKCS1Padding", { output: "base64" }),
        copied.privateKey, "RSA/ECB/PKCS1Padding", { input: "base64", output: "string" }), MESSAGE);

// 7) 文件输入 / 文件输出
var inPath = files.join(files.cwd(), "crypto-plain.txt");
var outPath = files.join(files.cwd(), "crypto-cipher.bin");
files.write(inPath, MESSAGE);
$crypto.encrypt(inPath, key, "AES", { input: "file", output: "file", dest: outPath });
check("file -> file",
    $crypto.decrypt(outPath, key, "AES", { input: "file", output: "string" }), MESSAGE);
files.remove(inPath);
files.remove(outPath);

console.log('CRYPTO_SUMMARY|pass=' + pass + ' failed=' + fail);
