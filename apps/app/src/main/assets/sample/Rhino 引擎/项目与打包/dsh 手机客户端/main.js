// @engine rhino
"ui";
/* dsh 手机客户端：首次粘贴配对链接；后续使用持久 Cookie 和设备 ID。
 * 在 AI.js Pro 中导入整个项目并打包，引擎选择 Rhino。
 * 自签证书仅信任已固定指纹且仍有效的服务证书；其他证书错误拒绝。
 */
var store = storages.create("dsh-client");
var server = String(store.get("server", ""));
var deviceId = String(store.get("deviceId", ""));
var cookies = android.webkit.CookieManager.getInstance();
var loadingFailed = false;

ui.layout(
    <vertical bg="#0f1115">
        <horizontal gravity="center_vertical" padding="8 0">
            <text id="status" w="0" layout_weight="1" textColor="#9aa0a6" textSize="12sp" text="尚未连接"/>
            <button id="refresh" text="刷新" w="64"/>
            <button id="settings" text="设置" w="64"/>
        </horizontal>
        <frame w="*" h="0" layout_weight="1">
            <webview id="web" w="*" h="*"/>
            <scroll id="setup" w="*" h="*" bg="#0f1115" visibility="gone">
                <vertical padding="24">
                    <text textSize="20sp" textColor="#ffffff" textStyle="bold" text="dsh 手机客户端"/>
                    <text textSize="13sp" textColor="#9aa0a6" marginTop="12" text="粘贴桌面端远程控制面板的配对链接，或填写已配对的服务器地址。"/>
                    <input id="input" w="*" marginTop="18" hint="https://你的地址/pair-accept?pair=…" textColor="#ffffff"/>
                    <button id="paste" w="*" marginTop="10" text="粘贴链接"/>
                    <button id="scan" w="*" marginTop="10" text="扫码填入"/>
                    <button id="ok" w="*" marginTop="14" text="保存并连接"/>
                    <button id="cancel" w="*" text="返回页面"/>
                    <text id="tip" textSize="12sp" textColor="#ff6b6b" marginTop="10"/>
                </vertical>
            </scroll>
            <vertical id="scanner" w="*" h="*" bg="#0f1115" visibility="gone">
                <text text="将二维码放入相机画面" textColor="#ffffff" textSize="16sp" padding="16"/>
                <frame id="previewBox" w="*" h="0" layout_weight="1"/>
                <button id="stopScan" text="取消扫码" w="*"/>
            </vertical>
        </frame>
    </vertical>
);
cookies.setAcceptCookie(true);
ui.web.getSettings().setUseWideViewPort(true);
ui.web.getSettings().setLoadWithOverviewMode(true);

function parseInput(value) {
    var text = String(value || "").replace(/^\s+|\s+$/g, "");
    if (!text || /\s/.test(text)) return null;
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = "https://" + text;
    try {
        var uri = new java.net.URI(text);
        var scheme = String(uri.getScheme() || "").toLowerCase();
        var host = uri.getHost();
        var port = uri.getPort();
        if ((scheme !== "https" && scheme !== "http") || !host || uri.getRawUserInfo() !== null
                || port > 65535 || port === 0 || uri.getRawFragment() !== null) return null;
        var authority = String(host).toLowerCase();
        if (authority.indexOf(":") >= 0 && authority.charAt(0) !== "[") authority = "[" + authority + "]";
        if (port !== -1 && !((scheme === "https" && port === 443) || (scheme === "http" && port === 80))) authority += ":" + port;
        var androidUri = android.net.Uri.parse(text);
        var id = androidUri.getQueryParameter("device");
        var path = String(uri.getPath() || "");
        if (path !== "" && path !== "/" && path !== "/pair-app" && path !== "/pair-accept") return null;
        return {
            server: scheme + "://" + authority,
            deviceId: id && /^[0-9a-f]{32}$/i.test(String(id)) ? String(id) : "",
            pair: path === "/pair-accept" && !!androidUri.getQueryParameter("pair"),
            url: text
        };
    } catch (error) {
        return null;
    }
}
function showSetup(message) {
    ui.input.setText(server);
    ui.tip.setText(message || "");
    ui.setup.setVisibility(0);
}
function hideSetup() {
    ui.setup.setVisibility(8);
    ui.input.clearFocus();
    var keyboard = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    keyboard.hideSoftInputFromWindow(ui.input.getWindowToken(), 0);
}
function openClient() {
    if (!server) return showSetup();
    hideSetup();
    ui.web.loadUrl(server + "/pair-app" + (deviceId ? "?device=" + encodeURIComponent(deviceId) : ""));
}
// 此服务的公开证书指纹；服务器换证书后需更新。地址仍由用户输入。
var certificatePins = {
    "https://106.55.18.198": "3277bde9abbccead55ccc68b487195ff8809b319d2462ac08b048e12dcb79dc9"
};
function matchesPinnedCertificate(error) {
    try {
        var uri = new java.net.URI(String(error.getUrl()));
        var port = uri.getPort();
        var origin = String(uri.getScheme()).toLowerCase() + "://" + String(uri.getHost()).toLowerCase()
            + (port !== -1 && port !== 443 ? ":" + port : "");
        if (origin !== server || !certificatePins[origin]) return false;
        var state = android.net.http.SslCertificate.saveState(error.getCertificate());
        var bytes = state.getByteArray("x509-certificate");
        if (!bytes) return false;
        var certificate = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(bytes));
        certificate.checkValidity();
        if (error.hasError(android.net.http.SslError.SSL_INVALID)
                || error.hasError(android.net.http.SslError.SSL_DATE_INVALID)) return false;
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        var fingerprint = "";
        for (var i = 0; i < digest.length; i++) fingerprint += ("0" + (digest[i] & 255).toString(16)).slice(-2);
        return fingerprint === certificatePins[origin];
    } catch (failure) { return false; }
}
// 设备 ID 由配对后的重定向给出；仅保存同一服务器返回的身份。
ui.web.setWebViewClient(new JavaAdapter(android.webkit.WebViewClient, {
    onPageStarted: function (view, url, favicon) {
        loadingFailed = false;
        ui.status.setText("正在连接…");
        var parsed = parseInput(String(url));
        if (parsed && parsed.server === server && parsed.deviceId) {
            deviceId = parsed.deviceId;
            store.put("deviceId", deviceId);
        }
    },
    onPageFinished: function (view, url) {
        // Android CookieManager 将登录 Cookie 写入持久存储。
        cookies.flush();
        if (!loadingFailed) ui.status.setText("页面已加载");
    },
    onReceivedSslError: function (view, handler, error) {
        if (matchesPinnedCertificate(error)) { handler.proceed(); return; }
        handler.cancel();
        loadingFailed = true;
        ui.status.setText("证书校验失败");
        showSetup("证书未通过校验，请检查手机日期时间、证书有效期或固定证书指纹是否匹配。");
    },
    onReceivedError: function (view, request, error) {
        // 兼容 Android 的旧版四参数和新版三参数回调。
        if (arguments.length === 3 && !request.isForMainFrame()) return;
        loadingFailed = true;
        ui.status.setText("连接失败");
        showSetup("连接失败，请检查服务器地址、手机网络及服务是否运行。");
    },
    onReceivedHttpError: function (view, request, response) {
        if (!request.isForMainFrame()) return;
        loadingFailed = true;
        ui.status.setText("HTTP " + response.getStatusCode());
        showSetup("服务器返回 HTTP " + response.getStatusCode() + "，可重新粘贴配对链接后连接。");
    },
    shouldOverrideUrlLoading: function (view, request) {
        var url = typeof request === "string" || request instanceof java.lang.String
            ? String(request) : String(request.getUrl());
        // 非网页协议不在 WebView 内执行。
        return !/^https?:\/\//i.test(url);
    }
}));
ui.ok.on("click", function () {
    var parsed = parseInput(String(ui.input.getText()));
    if (!parsed) {
        ui.tip.setText("请填写有效服务器地址或 /pair-accept、/pair-app 链接。");
        return;
    }
    // 更换服务器或重新配对时丢弃旧设备 ID。
    if (parsed.server !== server || parsed.pair) deviceId = "";
    server = parsed.server;
    if (parsed.deviceId) deviceId = parsed.deviceId;
    store.put("server", server);
    store.put("deviceId", deviceId);
    hideSetup();
    if (parsed.pair) ui.web.loadUrl(parsed.url);
    else openClient();
});
ui.paste.on("click", function () {
    var value = String(getClip() || "");
    if (!parseInput(value)) { ui.tip.setText("剪贴板里没有有效的服务器或配对链接。"); return; }
    ui.input.setText(value);
    ui.tip.setText("已粘贴，请点击保存并连接。");
});
// 解码库跟随项目打包，相机预览和识别均由本脚本实现。
var camera = null;
var scanning = false;
var scanPaused = false;
var scanTimer = null;
var scanBusy = false;
var lastScan = 0;
var qrLoaded = false;
var preview = new android.view.SurfaceView(activity);
ui.previewBox.addView(preview);
function releaseCamera() {
    if (camera) {
        try { camera.setPreviewCallback(null); camera.stopPreview(); camera.release(); } catch (error) {}
        camera = null;
    }
}
function stopScanner() {
    scanning = false;
    if (scanTimer !== null) { clearInterval(scanTimer); scanTimer = null; }
    releaseCamera();
    ui.scanner.setVisibility(8);
}
function acceptQr(text) {
    if (!scanning) return;
    if (!parseInput(text)) {
        ui.status.setText("二维码不是有效链接，请继续扫描");
        return;
    }
    stopScanner();
    ui.input.setText(text);
    ui.tip.setText("已识别，请点击保存并连接。");
    ui.status.setText("扫码完成");
}
function openCamera() {
    if (!scanning || scanPaused || camera || !preview.getHolder().getSurface().isValid()) return;
    if (activity.checkSelfPermission("android.permission.CAMERA") !== android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    try {
        camera = android.hardware.Camera.open();
        var parameters = camera.getParameters();
        parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
        camera.setParameters(parameters);
        var info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(0, info);
        var degrees = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
        camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
        camera.setPreviewDisplay(preview.getHolder());
        camera.setPreviewCallback(new JavaAdapter(android.hardware.Camera.PreviewCallback, {
            onPreviewFrame: function (data, sourceCamera) {
                var now = Date.now();
                if (!scanning || scanBusy || now - lastScan < 400) return;
                lastScan = now;
                scanBusy = true;
                // 只复制亮度平面，后台识别，不阻塞页面触摸和取消按钮。
                var size = sourceCamera.getParameters().getPreviewSize();
                var width = size.width;
                var height = size.height;
                var frame = java.util.Arrays.copyOf(data, width * height);
                threads.start(function () {
                    try {
                        var luminance = new com.google.zxing.PlanarYUVLuminanceSource(frame,
                            width, height, 0, 0, width, height, false);
                        var bitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(luminance));
                        var result = new com.google.zxing.qrcode.QRCodeReader().decode(bitmap);
                        var text = String(result.getText());
                        ui.run(function () { acceptQr(text); });
                    } catch (error) {
                        // 未识别到二维码时继续预览，不显示每一帧的失败。
                    } finally { scanBusy = false; }
                });
            }
        }));
        camera.startPreview();
        var focusModes = parameters.getSupportedFocusModes();
        if (focusModes && focusModes.contains("continuous-picture")) {
            parameters.setFocusMode("continuous-picture");
            camera.setParameters(parameters);
        }
        ui.status.setText("正在扫码…");
    } catch (error) {
        stopScanner();
        ui.tip.setText("相机无法打开，请关闭其他占用相机的应用或手动输入。");
    }
}
preview.getHolder().addCallback(new JavaAdapter(android.view.SurfaceHolder.Callback, {
    surfaceCreated: function (holder) { openCamera(); },
    surfaceChanged: function (holder, format, width, height) {},
    surfaceDestroyed: function (holder) { releaseCamera(); }
}));
ui.scan.on("click", function () {
    try {
        if (!qrLoaded) {
            runtime.loadDex(files.path("./zxing.jar"));
            qrLoaded = true;
        }
        scanning = true;
        scanPaused = false;
        ui.scanner.setVisibility(0);
        ui.status.setText("等待相机权限…");
        if (activity.checkSelfPermission("android.permission.CAMERA") !== android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(["android.permission.CAMERA"], 49375);
        }
        var waitingSince = Date.now();
        if (scanTimer !== null) clearInterval(scanTimer);
        scanTimer = setInterval(function () {
            ui.run(function () {
                if (!scanning) return;
                openCamera();
                if (!camera && !scanPaused && Date.now() - waitingSince > 20000) {
                    stopScanner();
                    ui.tip.setText("未获得相机权限，可在应用设置中授权，或继续手动输入。");
                }
            });
        }, 300);
        openCamera();
    } catch (error) {
        stopScanner();
        ui.tip.setText("无法加载扫码库，请确认整个项目已打包，或使用手动输入。");
    }
});
ui.stopScan.on("click", function () { stopScanner(); ui.status.setText("已取消扫码"); });
ui.emitter.on("pause", function () { scanPaused = true; releaseCamera(); });
ui.emitter.on("resume", function () { scanPaused = false; if (scanning) openCamera(); });
ui.settings.on("click", function () { showSetup(); });
ui.refresh.on("click", function () { openClient(); });
ui.cancel.on("click", function () { if (server) hideSetup(); });
ui.emitter.on("back_pressed", function (event) {
    if (scanning) {
        event.consumed = true;
        stopScanner();
    } else if (ui.setup.getVisibility() === 0 && server) {
        event.consumed = true;
        hideSetup();
    } else if (ui.setup.getVisibility() !== 0 && ui.web.canGoBack()) {
        event.consumed = true;
        ui.web.goBack();
    }
});
events.on("exit", function () { releaseCamera(); cookies.flush(); });
openClient();