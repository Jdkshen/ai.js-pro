package com.jdkshen.aijspro.autojs.key;

import android.util.Log;
import android.view.KeyEvent;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.core.inputevent.InputEventObserver;
import com.stardust.autojs.core.inputevent.ShellKeyObserver;
import com.stardust.event.EventDispatcher;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.HostScripts;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.OnKeyListener;


/**
 * Created by Stardust on 2017/8/14.
 */

public class GlobalKeyObserver implements OnKeyListener, ShellKeyObserver.KeyListener {


    public interface OnVolumeDownListener {
        void onVolumeDown();
    }

    private static final EventDispatcher.Event<OnVolumeDownListener> VOLUME_DOWN_EVENT = OnVolumeDownListener::onVolumeDown;
    private static final String LOG_TAG = "GlobalKeyObserver";
    private static GlobalKeyObserver sSingleton;
    private EventDispatcher<OnVolumeDownListener> mVolumeDownEventDispatcher = new EventDispatcher<>();
    private final VolumeKeyDeduplicator mKeys = new VolumeKeyDeduplicator();
    private boolean mShellInstalled;

    GlobalKeyObserver() {
        AccessibilityService.Companion.getStickOnKeyObserver()
                .addListener(this);
    }

    public static synchronized GlobalKeyObserver getSingleton() {
        if (sSingleton == null) {
            sSingleton = new GlobalKeyObserver();
        }
        return sSingleton;
    }

    public static void init() {
        GlobalKeyObserver observer = getSingleton();
        // Register accessibility unconditionally, but do not request a root getevent shell
        // just because the app started. The opt-in setting retains the legacy root path.
        if (Pref.isRunningVolumeControlEnabled() || Pref.isRecordingVolumeControlEnabled()) observer.ensureShellObserver();
    }

    private synchronized void ensureShellObserver() {
        if (mShellInstalled) return;
        ShellKeyObserver observer = new ShellKeyObserver();
        observer.setKeyListener(this);
        InputEventObserver.getGlobal(GlobalAppContext.get()).addListener(observer);
        mShellInstalled = true;
    }

    public void onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis());
        // Emergency stop is always observed through accessibility; the optional setting
        // controls only the root getevent fallback and volume-down run/record behavior.
        HostScripts.stopAllScripts(GlobalAppContext.get());
    }

    public void onVolumeDown() {
        android.content.Context context=GlobalAppContext.get();
        if (!com.jdkshen.aijspro.external.HostProcess.isMain(context)) {
            context.sendBroadcast(new android.content.Intent(context,VolumeKeyRelayReceiver.class)
                    .putExtra("eventTime",android.os.SystemClock.uptimeMillis()));
            return;
        }
        Log.d(LOG_TAG, "onVolumeDown at " + System.currentTimeMillis());
        mVolumeDownEventDispatcher.dispatchEvent(VOLUME_DOWN_EVENT);
    }

    public void addVolumeDownListener(OnVolumeDownListener listener) {
        mVolumeDownEventDispatcher.addListener(listener);
    }

    public void onRelayedVolumeDown(long eventTime) {
        if (mKeys.accept(KeyEvent.KEYCODE_VOLUME_DOWN,"accessibility",eventTime)) onVolumeDown();
    }

    public boolean removeVolumeDownListener(OnVolumeDownListener listener) {
        return mVolumeDownEventDispatcher.removeListener(listener);
    }

    @Override
    public void onKeyEvent(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP || event.isCanceled())
            return;
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return;
        if (!mKeys.accept(keyCode, "accessibility", android.os.SystemClock.uptimeMillis())) return;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onVolumeDown();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onVolumeUp();
        }
    }


    @Override
    public void onKeyDown(String keyName) {

    }

    @Override
    public void onKeyUp(String keyName) {
        if ("KEY_VOLUMEUP".equals(keyName)) {
            if (mKeys.accept(KeyEvent.KEYCODE_VOLUME_UP, "shell", android.os.SystemClock.uptimeMillis())) onVolumeUp();
        } else if ("KEY_VOLUMEDOWN".equals(keyName)) {
            if (mKeys.accept(KeyEvent.KEYCODE_VOLUME_DOWN, "shell", android.os.SystemClock.uptimeMillis())) onVolumeDown();
        }
    }
}
