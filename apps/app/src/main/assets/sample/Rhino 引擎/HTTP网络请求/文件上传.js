"rhino";
// 只上传本示例生成的测试文本，不读取用户存储根目录中的文件。
var uploadFile = files.path('./upload-demo.txt');
var uploadUrl = 'https://httpbin.org/post';
files.write(uploadFile, 'AI.js Pro multipart upload demo\n');

console.show();
example1();
example2();
example3();
example4();

function example1() {
    var res = http.postMultipart(uploadUrl, {
        "file": open(uploadFile)
    });
    log("例子1:");
    log(res.body.string());
}

function example2() {
    var res = http.postMultipart(uploadUrl, {
        "file": ["upload-demo.txt", uploadFile]
    });
    log("例子2:");
    log(res.body.string());
}

function example3() {
    var res = http.postMultipart(uploadUrl, {
        "file": ["upload-demo.txt", "text/plain", uploadFile]
    });
    log("例子3:");
    log(res.body.string());
}

function example4() {
    var res = http.postMultipart(uploadUrl, {
        "file": open(uploadFile),
        "aKey": "aValue"
    });
    log("例子4:");
    log(res.body.string());
}
