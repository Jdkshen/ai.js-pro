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

import javax.microedition.khronos.opengles.GL;

/**
 * Created by Stardust on 2017/8/14.
 */

public class GlobalKeyObserver implements OnKeyListener, ShellKeyObserver.KeyListener {


    public interface OnVolumeDownListener {
        void onVolumeDown();
    }

    private static final EventDispatcher.Event<OnVolumeDownListener> VOLUME_DOWN_EVENT = OnVolumeDownListener::onVolumeDown;
    private static final String LOG_TAG = "GlobalKeyObserver";
    private static final long EVENT_TIMEOUT = 200;
    private static GlobalKeyObserver sSingleton;
    private EventDispatcher<OnVolumeDownListener> mVolumeDownEventDispatcher = new EventDispatcher<>();
    private boolean mVolumeDownFromShell, mVolumeDownFromAccessibility;
    private boolean mVolumeUpFromShell, mVolumeUpFromAccessibility;

    GlobalKeyObserver() {
        AccessibilityService.Companion.getStickOnKeyObserver()
                .addListener(this);
        ShellKeyObserver observer = new ShellKeyObserver();
        observer.setKeyListener(this);
        InputEventObserver.getGlobal(GlobalAppContext.get()).addListener(observer);
    }

    public static GlobalKeyObserver getSingleton() {
        if (sSingleton == null) {
            sSingleton = new GlobalKeyObserver();
        }
        return sSingleton;
    }

    public static void init() {
        getSingleton();
    }

    public void onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis());
        // Phase1：脚本默认在 :script 宿主进程里跑，主进程引擎的 stopAll 停不到它们。
        // 全局按键通道只在主进程无障碍/root 里，所以保留主进程监听，先停宿主进程任务
        // （:script + Phase0 :native），再停主进程旧引擎任务兜底（toast 计数只反映后者）。
        // 音量上键停止宿主任务不依赖"音量键控制运行"设置（默认关闭），否则 Phase1 下按键无效；
        // 旧主进程引擎的停止仍尊重该设置（避免改变原行为）。
        // 音量上键 = 停止全部脚本：宿主进程（:script + :native）和主进程引擎（Rhino/UI 脚本，
        // 如 ScriptExecuteActivity）都要停，且不依赖「音量键控制运行」设置（默认关闭）。
        HostScripts.stopAllHostScripts();
        try {
            int stopped = AutoJs.getInstance().getScriptEngineService().stopAll();
            Log.d(LOG_TAG, "stopAll main scripts: " + stopped);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "stop main scripts failed", error);
        }
    }

    public void onVolumeDown() {
        Log.d(LOG_TAG, "onVolumeDown at " + System.currentTimeMillis());
        mVolumeDownEventDispatcher.dispatchEvent(VOLUME_DOWN_EVENT);
    }

    public void addVolumeDownListener(OnVolumeDownListener listener) {
        mVolumeDownEventDispatcher.addListener(listener);
    }

    public boolean removeVolumeDownListener(OnVolumeDownListener listener) {
        return mVolumeDownEventDispatcher.removeListener(listener);
    }

    @Override
    public void onKeyEvent(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP)
            return;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (mVolumeDownFromShell) {
                mVolumeDownFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeDown();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mVolumeUpFromShell) {
                mVolumeUpFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeUp();
        }
    }


    @Override
    public void onKeyDown(String keyName) {

    }

    @Override
    public void onKeyUp(String keyName) {
        if ("KEY_VOLUMEUP".equals(keyName)) {
            if (mVolumeUpFromAccessibility) {
                mVolumeUpFromAccessibility = false;
                return;
            }
            mVolumeUpFromShell = true;
            onVolumeUp();
        } else if ("KEY_VOLUMEDOWN".equals(keyName)) {
            if (mVolumeDownFromAccessibility) {
                mVolumeDownFromAccessibility = false;
                return;
            }
            mVolumeDownFromShell = true;
            onVolumeDown();
        }
    }
}
