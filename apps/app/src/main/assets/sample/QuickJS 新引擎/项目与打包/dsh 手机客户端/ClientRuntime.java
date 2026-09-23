package com.dsh.client;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.webkit.*;
import android.widget.FrameLayout;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android callback adapter for the QuickJS sample, with no JavaScript interpreter. */
@SuppressWarnings("deprecation")
public final class ClientRuntime implements Application.ActivityLifecycleCallbacks {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Activity activity;
    private final WebView web;
    private final FrameLayout box;
    private SurfaceView surface;
    private Camera camera;
    private boolean scanning, paused, closed;
    private volatile int generation;
    private long lastFrame, permissionSince;
    private String origin = "", pin = "";

    public ClientRuntime(WebView web, FrameLayout box) {
        this.web = web;
        this.box = box;
        Context owner = web.getContext();
        while (!(owner instanceof Activity) && owner instanceof ContextWrapper)
            owner = ((ContextWrapper) owner).getBaseContext();
        if (!(owner instanceof Activity)) throw new IllegalArgumentException("Activity required");
        activity = (Activity) owner;
        main.post(() -> {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap icon) { emit("started", url); }
                @Override public void onPageFinished(WebView view, String url) {
                    CookieManager.getInstance().flush(); emit("finished", url);
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    if (trusted(error)) { handler.proceed(); return; }
                    handler.cancel(); emit("ssl", "");
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) emit("network", "");
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (request.isForMainFrame()) emit("http", String.valueOf(response.getStatusCode()));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return block(request.getUrl().toString());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return block(url); }
                private boolean block(String url) { return !url.matches("(?i)^https?://.*"); }
            });
            surface = new SurfaceView(activity);
            box.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) { openCamera(); }
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
                public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }
            });
        });
    }
    private void emit(String type, String value) {
        if (closed || queue.size() >= 64) return;
        try { queue.add(new JSONObject().put("type", type).put("value", value).toString()); }
        catch (Exception ignored) { }
    }
    public String poll() { String item = queue.poll(); return item == null ? "" : item; }
    public void connect(String server, String fingerprint, String url) {
        main.post(() -> { origin = server; pin = fingerprint; web.loadUrl(url); });
    }
    private boolean trusted(SslError error) {
        try {
            if (pin.isEmpty() || !origin.equals(originOf(error.getUrl()))) return false;
            byte[] der = SslCertificate.saveState(error.getCertificate()).getByteArray("x509-certificate");
            if (der == null || error.hasError(SslError.SSL_INVALID) || error.hasError(SslError.SSL_DATE_INVALID)) return false;
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            cert.checkValidity();
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(der))
                hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            return pin.equals(hash.toString());
        } catch (Exception ignored) { return false; }
    }
    private static String originOf(String url) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return scheme + "://" + host + (port == -1 || port == 443 ? "" : ":" + port);
    }
    public void startScan() {
        main.post(() -> {
            if (closed) return;
            scanning = true; generation++; permissionSince = System.currentTimeMillis();
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(new String[] { Manifest.permission.CAMERA }, 49375);
            main.removeCallbacks(permissionPoll); main.post(permissionPoll);
        });
    }
    private final Runnable permissionPoll = new Runnable() {
        @Override public void run() {
            if (!scanning || closed) return;
            openCamera();
            if (!paused && camera == null && System.currentTimeMillis() - permissionSince > 20000) {
                stopInternal(); emit("cameraError", "未获得相机权限，可在应用设置中授权或手动输入。"); return;
            }
            main.postDelayed(this, 300);
        }
    };
    private void openCamera() {
        if (!scanning || paused || closed || camera != null || surface == null
                || !surface.getHolder().getSurface().isValid()
                || activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            camera = Camera.open();
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);
            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);
            Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(0, info);
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
            camera.setDisplayOrientation((info.orientation - rotation + 360) % 360);
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback((data, source) -> {
                long now = System.currentTimeMillis();
                if (!scanning || now - lastFrame < 400 || !busy.compareAndSet(false, true)) return;
                lastFrame = now;
                Camera.Size size = source.getParameters().getPreviewSize();
                byte[] frame = Arrays.copyOf(data, size.width * size.height);
                int current = generation;
                decoder.execute(() -> {
                    try {
                        LuminanceSource luminance = new PlanarYUVLuminanceSource(frame, size.width, size.height,
                                0, 0, size.width, size.height, false);
                        String result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(luminance))).getText();
                        if (current == generation) emit("qr", result);
                    } catch (Exception ignored) { } finally { busy.set(false); }
                });
            });
            camera.startPreview(); emit("cameraReady", "");
        } catch (Exception error) {
            stopInternal(); emit("cameraError", "相机无法打开，请关闭其他占用相机的应用或手动输入。");
        }
    }
    public void stopScan() { main.post(this::stopInternal); }
    private void stopInternal() { scanning = false; generation++; main.removeCallbacks(permissionPoll); releaseCamera(); }
    private void releaseCamera() {
        if (camera == null) return;
        try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (Exception ignored) { }
        camera.release(); camera = null;
    }
    public void close() {
        main.post(() -> {
            if (closed) return;
            closed = true; stopInternal(); decoder.shutdownNow(); queue.clear();
            CookieManager.getInstance().flush();
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        });
    }
    public void onActivityPaused(Activity owner) { if (owner == activity) { paused = true; generation++; releaseCamera(); CookieManager.getInstance().flush(); } }
    public void onActivityResumed(Activity owner) { if (owner == activity) { paused = false; permissionSince = System.currentTimeMillis(); openCamera(); } }
    public void onActivityDestroyed(Activity owner) { if (owner == activity) close(); }
    public void onActivityCreated(Activity owner, Bundle state) { }
    public void onActivityStarted(Activity owner) { }
    public void onActivityStopped(Activity owner) { }
    public void onActivitySaveInstanceState(Activity owner, Bundle state) { }
}
