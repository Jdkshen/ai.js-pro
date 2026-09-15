// @engine rhino
// $zip 行为验收（对照 335 页 PDF §2.35 第 257~263 页）
// 跑法见 tools/pro-api/README.md：push 后 am start 打开，日志里看 OK/FAIL 与 ZIP_SUMMARY。
//
// 注意两个文档细节：
//   - 条目名可能带父目录前缀（`includeRootFolder` 默认 true，与 zip4j 一致），所以断言用后缀匹配；
//   - 建目录要用 $files.createWithDirs（$files.create 建的是文件，$files.ensureDir 的返回值不可靠）。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function exists(path) {
    return $files.exists(path);
}

function entries(zf) {
    var headers = zf.getFileHeaders();
    var names = [];
    for (var i = 0; i < headers.length; i++) { names.push(headers[i].getFileName()); }
    return names;
}

/** 压缩包里以 suffix 结尾的条目名（含父目录前缀时也能命中）。 */
function entry(nameList, suffix) {
    for (var i = 0; i < nameList.length; i++) {
        var name = nameList[i];
        if (name === suffix || name.substring(name.length - suffix.length - 1) === '/' + suffix) {
            return name;
        }
    }
    return null;
}

var dir = $files.join(files.cwd(), "zip-demo");
if (exists(dir)) { $files.removeDir(dir); }
$files.createWithDirs($files.join(dir, "a.txt"));
$files.write($files.join(dir, "a.txt"), "AAA");
var sub = $files.join(dir, "sub");
$files.createWithDirs($files.join(sub, "b.txt"));
$files.write($files.join(sub, "b.txt"), "BBB");

// 1) zipDir：默认选项（文档：includeRootFolder 默认 true → 条目带父目录名）
var dirZip = $files.join(files.cwd(), "zip-dir.zip");
if (exists(dirZip)) { $files.remove(dirZip); }
$zip.zipDir(dir, dirZip);
check("zipDir 生成压缩包", exists(dirZip), true);

var zf = $zip.open(dirZip);
check("ZipFile.getPath", zf.getPath() === dirZip, true);
check("ZipFile.isValidZipFile", zf.isValidZipFile(), true);
check("ZipFile.isEncrypted(未加密)", zf.isEncrypted(), false);
var names = entries(zf);
var aName = entry(names, "a.txt");
check("默认 includeRootFolder 带父目录名", String(aName).indexOf("/") > 0, true);
check("getFileHeaders 含子目录文件", entry(names, "sub/b.txt") !== null, true);
check("getFileHeader 单个", zf.getFileHeader(aName).getFileName(), aName);
check("getFileHeader 内容长度 > 0", zf.getFileHeader(aName).getUncompressedSize() > 0, true);

var outDir = $files.join(files.cwd(), "zip-out");
if (exists(outDir)) { $files.removeDir(outDir); }
zf.extractAll(outDir);
var rootName = aName.substring(0, aName.length - "a.txt".length);   // 例如 "zip-demo/"
check("extractAll 解出 a.txt", $files.read($files.join(outDir, rootName + "a.txt")), "AAA");
check("extractAll 解出 sub/b.txt", $files.read($files.join(outDir, rootName + "sub/b.txt")), "BBB");

// 1.1) includeRootFolder: false → 条目名不带父目录
var flatZip = $files.join(files.cwd(), "zip-flat.zip");
if (exists(flatZip)) { $files.remove(flatZip); }
$zip.zipDir(dir, flatZip, { includeRootFolder: false });
check("includeRootFolder:false 后条目为 a.txt", entry(entries($zip.open(flatZip)), "a.txt"), "a.txt");

// 2) extractFile
var single = $files.join(files.cwd(), "zip-single");
if (exists(single)) { $files.removeDir(single); }
zf.extractFile(aName, single);
check("extractFile 到目录", $files.read($files.join(single, rootName + "a.txt")), "AAA");
zf.extractFile(aName, single, null, "renamed.txt");
check("extractFile 指定新文件名", $files.read($files.join(single, "renamed.txt")), "AAA");

// 3) addFile / addFiles / addFolder / removeFile
var growZip = $files.join(files.cwd(), "zip-grow.zip");
if (exists(growZip)) { $files.remove(growZip); }
var grow = new ZipFile(growZip);
grow.addFile($files.join(dir, "a.txt"));
check("addFile 后条目数", grow.getFileHeaders().length, 1);
var extra = $files.join(files.cwd(), "extra.txt");
$files.createWithDirs(extra);
$files.write(extra, "EXTRA");
grow.addFiles([extra, $files.join(sub, "b.txt")]);
check("addFiles 后条目数", grow.getFileHeaders().length, 3);
grow.removeFile("b.txt");
check("removeFile 后条目数", grow.getFileHeaders().length, 2);
grow.addFolder(sub);
check("addFolder 后含 sub/b.txt", entry(entries(grow), "sub/b.txt") !== null, true);

// 4) 加密压缩（文档：设置 password 但没写 encryptionMethod → 默认 AES）
var encZip = $files.join(files.cwd(), "zip-encrypted.zip");
if (exists(encZip)) { $files.remove(encZip); }
$zip.zipDir(dir, encZip, { password: "Auto.js Pro" });
check("加密压缩包生成", exists(encZip), true);
check("isEncrypted", $zip.open(encZip).isEncrypted(), true);

var encOut = $files.join(files.cwd(), "zip-enc-out");
if (exists(encOut)) { $files.removeDir(encOut); }
$zip.unzip(encZip, encOut, { password: "Auto.js Pro" });
check("带密码解压 a.txt", $files.read($files.join(encOut, rootName + "a.txt")), "AAA");

// 4.1) 显式 AES-256 与标准（ZipCrypto）加密
var encZip256 = $files.join(files.cwd(), "zip-enc256.zip");
if (exists(encZip256)) { $files.remove(encZip256); }
$zip.zipFile($files.join(dir, "a.txt"), encZip256,
    { password: "Auto.js Pro", aesKeyStrength: "AES_STRENGTH_256", encryptionMethod: "ENC_METHOD_AES" });
check("AES-256 单文件压缩包", exists(encZip256), true);
check("AES-256 包 isEncrypted", $zip.open(encZip256).isEncrypted(), true);

var encZipStd = $files.join(files.cwd(), "zip-enc-std.zip");
if (exists(encZipStd)) { $files.remove(encZipStd); }
$zip.zipFile($files.join(dir, "a.txt"), encZipStd,
    { password: "Auto.js Pro", encryptionMethod: "ENC_METHOD_STANDARD" });
check("ENC_METHOD_STANDARD 包 isEncrypted", $zip.open(encZipStd).isEncrypted(), true);

// 5) zipFile / zipFiles
var oneZip = $files.join(files.cwd(), "zip-one.zip");
if (exists(oneZip)) { $files.remove(oneZip); }
$zip.zipFile($files.join(dir, "a.txt"), oneZip);
check("zipFile 条目数", $zip.open(oneZip).getFileHeaders().length, 1);

var manyZip = $files.join(files.cwd(), "zip-many.zip");
if (exists(manyZip)) { $files.remove(manyZip); }
$zip.zipFiles([$files.join(dir, "a.txt"), extra], manyZip, { compressionMethod: "COMP_STORE" });
check("zipFiles 条目数(COMP_STORE)", $zip.open(manyZip).getFileHeaders().length, 2);

var manyOut = $files.join(files.cwd(), "zip-many-out");
if (exists(manyOut)) { $files.removeDir(manyOut); }
$zip.unzip(manyZip, manyOut);
check("unzip 解出 extra.txt", $files.read($files.join(manyOut, "extra.txt")), "EXTRA");

// 6) setPassword 链式调用 + 无效压缩包
check("setPassword 返回自身", $zip.open(encZip).setPassword("Auto.js Pro").isValidZipFile(), true);
var badZip = $files.join(files.cwd(), "zip-bad.zip");
$files.createWithDirs(badZip);
$files.write(badZip, "not a zip");
check("isValidZipFile(坏包)", $zip.open(badZip).isValidZipFile(), false);

// 清理
var cleanup = [dirZip, flatZip, growZip, encZip, encZip256, encZipStd, oneZip, manyZip, badZip, dir,
    outDir, single, encOut, manyOut, extra];
for (var k = 0; k < cleanup.length; k++) {
    if (exists(cleanup[k])) {
        try { $files.removeDir(cleanup[k]); } catch (e) { }
    }
}

console.log('ZIP_SUMMARY|pass=' + pass + ' failed=' + fail);
