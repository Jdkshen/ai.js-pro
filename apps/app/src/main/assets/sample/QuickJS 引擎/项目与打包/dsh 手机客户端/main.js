"quickjs";
"ui";
// QuickJS 负责界面、配置和连接流程；client-runtime.jar 提供 Android 相机与 TLS 回调。
const store = storages.create("dsh-client");
let server = String(store.get("server", ""));
let deviceId = String(store.get("deviceId", ""));
let loadingFailed = false;
let scanning = false;
let setupVisible = false;
const certificatePins = {
    "https://106.55.18.198": "3277bde9abbccead55ccc68b487195ff8809b319d2462ac08b048e12dcb79dc9"
};
ui.layout(files.read("./layout.xml"));
// 使用 QuickJS Java 互操作加载随项目附带的 dex，不依赖 Rhino runtime.loadDex/JavaAdapter。
const loader = new dalvik.system.DexClassLoader(files.path("./client-runtime.jar"),
    String(context.getCacheDir().getPath()), null, context.getClassLoader());
java.lang.Thread.currentThread().setContextClassLoader(loader);
const ClientRuntime = Java.type("com.dsh.client.ClientRuntime");
// ⚠️ 这两个参数按「子节点下标」取容器，layout.xml 的结构不能随意加/删同级节点：
//   ui.web.getParent()        = 内容 frame，其父（根垂直布局）的 [0] 是顶栏、[1] 才是 frame
//   ui.previewBox.getParent() = 扫码视图，其 [1] 必须是 previewBox 本身
// 改错了 SurfaceView 会挂到别的容器上，相机直接黑屏且没有报错。
const nativeClient = new ClientRuntime(ui.web.getParent().getChildAt(0),
    ui.previewBox.getParent().getChildAt(1));

function parseInput(value) {
    const text = String(value || "").trim();
    if (!text || /\s/.test(text)) return null;
    const url = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : "https://" + text;
    try {
        const uri = new java.net.URI(url);
        const scheme = String(uri.getScheme() || "").toLowerCase();
        const host = uri.getHost();
        const port = uri.getPort();
        if ((scheme !== "https" && scheme !== "http") || !host || uri.getRawUserInfo() !== null
                || port > 65535 || port === 0 || uri.getRawFragment() !== null) return null;
        let authority = String(host).toLowerCase();
        if (authority.includes(":") && authority.charAt(0) !== "[") authority = "[" + authority + "]";
        if (port !== -1 && !((scheme === "https" && port === 443) || (scheme === "http" && port === 80))) authority += ":" + port;
        const androidUri = android.net.Uri.parse(url);
        const id = androidUri.getQueryParameter("device");
        const path = String(uri.getPath() || "");
        if (!["", "/", "/pair-app", "/pair-accept"].includes(path)) return null;
        return {
            server: scheme + "://" + authority,
            deviceId: id && /^[0-9a-f]{32}$/i.test(String(id)) ? String(id) : "",
            pair: path === "/pair-accept" && !!androidUri.getQueryParameter("pair"),
            url: url
        };
    } catch (error) { return null; }
}
function showSetup(message) {
    ui.input.setText(server);
    ui.tip.setText(message || "");
    setupVisible = true; ui.setup.attr("visibility", "visible");
}
function hideSetup() { setupVisible = false; ui.setup.attr("visibility", "gone"); ui.input.clearFocus(); }
function load(url) { nativeClient.connect(server, certificatePins[server] || "", url); }
function openClient() {
    if (!server) return showSetup();
    hideSetup();
    load(server + "/pair-app" + (deviceId ? "?device=" + encodeURIComponent(deviceId) : ""));
}
function stopScanner() { scanning = false; nativeClient.stopScan(); ui.scanner.attr("visibility", "gone"); }
ui.ok.on("click", () => {
    const parsed = parseInput(ui.input.getText());
    if (!parsed) { ui.tip.setText("请填写有效服务器地址或 /pair-accept、/pair-app 链接。"); return; }
    if (parsed.server !== server || parsed.pair) deviceId = "";
    server = parsed.server;
    if (parsed.deviceId) deviceId = parsed.deviceId;
    store.put("server", server); store.put("deviceId", deviceId);
    hideSetup();
    if (parsed.pair) load(parsed.url); else openClient();
});
ui.paste.on("click", () => {
    const value = String(getClip() || "");
    if (!parseInput(value)) { ui.tip.setText("剪贴板里没有有效的服务器或配对链接。"); return; }
    ui.input.setText(value); ui.tip.setText("已粘贴，请点击保存并连接。");
});
ui.scan.on("click", () => {
    scanning = true; ui.scanner.attr("visibility", "visible");
    ui.status.setText("等待相机权限…"); nativeClient.startScan();
});
ui.stopScan.on("click", () => { stopScanner(); ui.status.setText("已取消扫码"); });
ui.settings.on("click", () => showSetup());
ui.refresh.on("click", () => openClient());
ui.cancel.on("click", () => { if (server) hideSetup(); });
// Android 回调通过队列交给引擎线程，避免 UI/相机线程同步调用 JS 导致死锁。
const eventTimer = setInterval(() => {
    for (let count = 0; count < 20; count++) {
        const item = nativeClient.poll();
        if (!item) break;
        const event = JSON.parse(item);
        switch (event.type) {
            case "started": {
                loadingFailed = false; ui.status.setText("正在连接…");
                const parsed = parseInput(event.value);
                if (parsed && parsed.server === server && parsed.deviceId) {
                    deviceId = parsed.deviceId; store.put("deviceId", deviceId);
                }
                break;
            }
            case "finished": if (!loadingFailed) ui.status.setText("页面已加载"); break;
            case "ssl":
                loadingFailed = true; ui.status.setText("证书校验失败");
                showSetup("证书未通过校验，请检查手机日期时间、证书有效期或固定证书指纹。"); break;
            case "network":
                loadingFailed = true; ui.status.setText("连接失败");
                showSetup("连接失败，请检查服务器地址、手机网络及服务是否运行。"); break;
            case "http":
                loadingFailed = true; ui.status.setText("HTTP " + event.value);
                showSetup("服务器返回 HTTP " + event.value + "，可重新粘贴配对链接后连接。"); break;
            case "cameraReady": if (scanning) ui.status.setText("正在扫码…"); break;
            case "cameraError":
                if (scanning) { stopScanner(); ui.tip.setText(event.value); ui.status.setText("扫码停止"); } break;
            case "qr":
                if (!scanning) break;
                if (!parseInput(event.value)) { ui.status.setText("二维码不是有效链接，请继续扫描"); break; }
                stopScanner(); ui.input.setText(event.value);
                ui.tip.setText("已识别，请点击保存并连接。"); ui.status.setText("扫码完成"); break;
        }
    }
}, 150);
ui.emitter.on("back_pressed", event => {
    if (scanning) { event.consumed = true; stopScanner(); }
    else if (setupVisible && server) { event.consumed = true; hideSetup(); }
    else if (!setupVisible && ui.web.canGoBack()) { event.consumed = true; ui.web.goBack(); }
});
events.on("exit", () => { clearInterval(eventTimer); nativeClient.close(); });
openClient();
