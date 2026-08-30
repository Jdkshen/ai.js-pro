// @engine quickjs

// 引擎信息
console.log("QUICKJS_ENGINE", __engine__);

// ---- timers ----
let ticks = 0;
const intervalId = setInterval(function () {
    ticks++;
    console.info("QUICKJS_TIMER_TICK", ticks);
}, 250);

setTimeout(function () {
    clearInterval(intervalId);
    console.log("QUICKJS_TIMER_OK", ticks);
    toast("定时器测试完成：共 " + ticks + " 次");
}, 1100);

// ---- files ----
const file = files.cwd() + "/quickjs_io_test.txt";
files.write(file, "AI.js Pro QuickJS files 桥接\n");
files.append(file, "第二行\n");
const readBack = files.read(file);
console.log("QUICKJS_FILES_OK", readBack, "exists=" + files.exists(file), "isFile=" + files.isFile(file));
console.log("QUICKJS_LISTDIR_OK", files.listDir(".").slice(0, 5));
files.remove(file);
console.log("QUICKJS_REMOVE_OK", files.exists(file) === false);

// ---- http ----
try {
    const response = http.get("https://example.com/", {
        headers: { "User-Agent": "AI.jsPro-QuickJS" }
    });
    console.log("QUICKJS_HTTP_OK", response.statusCode, response.body.contentType,
        response.body.string.substring(0, 40));
} catch (error) {
    console.error("QUICKJS_HTTP_FAIL", error.message);
}

try {
    const json = http.postJson("https://httpbin.org/post", { hello: "QuickJS" });
    console.log("QUICKJS_HTTP_JSON_OK", json.statusCode, json.body.json().data);
} catch (error) {
    console.error("QUICKJS_HTTP_JSON_FAIL", error.message);
}

"done";
