package com.jdkshen.aijspro.external.tile;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import androidx.annotation.RequiresApi;
import android.util.Log;
import android.widget.Toast;

import com.stardust.app.GlobalAppContext;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.floating.FullScreenFloatyWindow;
import com.jdkshen.aijspro.ui.floating.layoutinspector.LayoutInspectorDiagnostics;

@RequiresApi(api = Build.VERSION_CODES.N)
public abstract class LayoutInspectTileService extends TileService implements LayoutInspector.CaptureAvailableListener {

    private boolean mCapturing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(getClass().getName(), "onCreate");
        AutoJs.getInstance().getLayoutInspector().addCaptureAvailableListener(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.d(getClass().getName(), "onStartListening");
        inactive();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(getClass().getName(), "onDestroy");
        AutoJs.getInstance().getLayoutInspector().removeCaptureAvailableListener(this);
    }

    @Override
    @android.annotation.SuppressLint("MissingPermission")
    public void onClick() {
        super.onClick();
        Log.d(getClass().getName(), "onClick");
        try {
            // Android 12+ may reject this protected broadcast for ordinary apps. It is only a
            // best-effort request to collapse the shade, so never let it abort layout capture.
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (SecurityException error) {
            Log.w(getClass().getName(), "System did not allow collapsing quick settings", error);
        }
        if (!com.jdkshen.aijspro.automation.AutomationConnectionState.isReady(this)) {
            AutoJs.getInstance().getLayoutInspector().captureCurrentWindow();
            LayoutInspectorDiagnostics.show(this, AutoJs.getInstance().getLayoutInspector());
            inactive();
            return;
        }
        mCapturing = true;
        GlobalAppContext.postDelayed(() -> {
            if (!AutoJs.getInstance().getLayoutInspector().captureCurrentWindow()) {
                onCaptureAvailable(null);
            }
        }, 1000);
    }

    protected void inactive() {
        Tile qsTile = getQsTile();
        if (qsTile == null)
            return;
        qsTile.setState(Tile.STATE_INACTIVE);
        qsTile.updateTile();
    }

    @Override
    public void onCaptureAvailable(NodeInfo capture) {
        Log.d(getClass().getName(), "onCaptureAvailable: capturing = " + mCapturing);
        if (!mCapturing) {
            return;
        }
        mCapturing = false;
        GlobalAppContext.post(() -> {
            if (capture == null) {
                LayoutInspectorDiagnostics.show(this, AutoJs.getInstance().getLayoutInspector());
                inactive();
                return;
            }
            FullScreenFloatyWindow window = onCreateWindow(capture);
            if (!FloatyWindowManger.addWindow(getApplicationContext(), window)) {
                inactive();
            }
        });

    }

    protected abstract FullScreenFloatyWindow onCreateWindow(NodeInfo capture);
}
