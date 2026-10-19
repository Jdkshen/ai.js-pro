package com.stardust.autojs.runtime.api;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.RequiresApi;

import android.view.KeyEvent;

import com.stardust.autojs.R;
import com.stardust.autojs.core.accessibility.AccessibilityBridge;
import com.stardust.autojs.core.boardcast.BroadcastEmitter;
import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.core.looper.Loopers;
import com.stardust.autojs.core.looper.MainThreadProxy;
import com.stardust.autojs.core.looper.Timer;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.notification.Notification;
import com.stardust.notification.NotificationListenerService;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.core.inputevent.InputEventObserver;
import com.stardust.autojs.core.inputevent.TouchObserver;
import com.stardust.util.MapBuilder;
import com.stardust.view.accessibility.AccessibilityNotificationObserver;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.FingerprintGestureDispatcher;
import com.stardust.view.accessibility.KeyInterceptor;
import com.stardust.view.accessibility.NotificationListener;
import com.stardust.view.accessibility.OnKeyListener;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by Stardust on 2017/7/18.
 */

public class Events extends EventEmitter implements OnKeyListener, TouchObserver.OnTouchEventListener, NotificationListener, AccessibilityNotificationObserver.ToastListener, AccessibilityService.GestureListener, ClipboardManager.OnPrimaryClipChangedListener {

    private static final String PREFIX_KEY_DOWN = "__key_down__#";
    private static final String PREFIX_KEY_UP = "__key_up__#";
    private static final String EVENT_CLIP_CHANGED = "clip_changed";
    private static final Map<Integer, String> GESTURES = new MapBuilder<Integer, String>()
            .put(AccessibilityService.GESTURE_SWIPE_UP, "up")
            .put(AccessibilityService.GESTURE_SWIPE_DOWN, "down")
            .put(AccessibilityService.GESTURE_SWIPE_LEFT, "left")
            .put(AccessibilityService.GESTURE_SWIPE_RIGHT, "right")
            .put(AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT, "left_right")
            .put(AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT, "right_left")
            .put(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN, "up_down")
            .put(AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP, "down_up")
            .put(AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP, "left_up")
            .put(AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN, "left_down")
            .put(AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP, "right_up")
            .put(AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN, "right_down")
            .put(AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT, "up_left")
            .put(AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT, "up_right")
            .put(AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT, "down_left")
            .put(AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT, "down_right")
            .build();

    private AccessibilityBridge mAccessibilityBridge;
    private Context mContext;
    private TouchObserver mTouchObserver;
    private long mLastTouchEventMillis;
    private long mTouchEventTimeout = 10;
    private boolean mListeningKey = false;
    private Loopers mLoopers;
    private Handler mHandler;
    private boolean mListeningNotification = false;
    private boolean mListeningGesture = false;
    private boolean mListeningToast = false;

    // ---- task-163：:script 隔离进程的远程无障碍事件订阅 ----
    /** 远程事件类型（Pro 语义：events.on('accessibility_event'/'view_clicked'/…） */
    private static final java.util.Set<String> REMOTE_ACCESSIBILITY_EVENT_TYPES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "accessibility_event", "view_clicked", "view_long_clicked",
                    "windows_changed", "window_state_changed", "window_content_changed",
                    "toast", "notification"));
    private com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge.AccessibilityEventListener
            mRemoteAccessibilityListener;
    private ScriptRuntime mScriptRuntime;
    private volatile boolean mInterceptsAllKey = false;
    private KeyInterceptor mKeyInterceptor;
    private Set<String> mInterceptedKeys = new HashSet<>();
    private boolean mListeningClip = false;
    private volatile boolean mListeningFingerprint;
    private volatile int mFingerprintGeneration;
    private int mFingerprintWaitId;
    private Handler mFingerprintHandler;
    private final FingerprintGestureDispatcher.Listener mFingerprintListener =
            new FingerprintGestureDispatcher.Listener() {
                @Override
                public void onFingerprintGesture(String direction) {
                    postFingerprintEvent("fingerprint_gesture", direction);
                }

                @Override
                public void onFingerprintGestureAvailabilityChanged(boolean available) {
                    postFingerprintEvent("fingerprint_gesture_available", available);
                }
            };

    public final BroadcastEmitter broadcast;

    public Events(Context context, AccessibilityBridge accessibilityBridge, ScriptRuntime runtime) {
        super(runtime.bridges);
        mAccessibilityBridge = accessibilityBridge;
        mContext = context;
        mLoopers = runtime.loopers;
        mScriptRuntime = runtime;
        broadcast = new BroadcastEmitter(runtime.bridges, runtime.timers.getMainTimer());
    }

    public EventEmitter emitter() {
        return new EventEmitter(mBridges);
    }

    public EventEmitter emitter(Thread thread) {
        Timer timer = mScriptRuntime.timers.getTimerForThread(thread);
        return new EventEmitter(mBridges, timer);
    }

    public EventEmitter emitter(MainThreadProxy mainThreadProxy) {
        return new EventEmitter(mBridges, mScriptRuntime.timers.getMainTimer());
    }

    /**
     * task-163：:script 进程里第一次订阅无障碍事件时，经 RemoteAutomationBridge 向主进程注册回调。
     * 主进程事件 -> JSON -> 这里 emit('accessibility_event' + kind 事件)。
     */
    private void maybeSubscribeRemoteAccessibility(String eventName) {
        if (eventName == null || !REMOTE_ACCESSIBILITY_EVENT_TYPES.contains(eventName)) {
            return;
        }
        ensureRemoteAccessibilityEvents();
    }

    private void ensureRemoteAccessibilityEvents() {
        if (mRemoteAccessibilityListener != null) {
            return;
        }
        if (!com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge.isActive()) {
            return;
        }
        // 订阅发生在脚本线程：先建好脚本线程的 Handler，主进程回调从 binder 线程 post 回来，
        // 保证 emit 在脚本线程执行（回调线程没有 Looper，不能在那里 new Handler）。
        try {
            ensureHandler();
        } catch (Throwable ignored) {
            // 脚本线程没有 Looper 时回退到主 Looper（见 postRemoteEvent）。
        }
        final com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge.AccessibilityEventListener
                listener = new com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge.AccessibilityEventListener() {
            @Override
            public void onAccessibilityEvent(String eventJson) {
                postRemoteAccessibilityEvent(eventJson);
            }

            @Override
            public void onServiceStateChanged(boolean ready) {
                postRemoteEvent(() -> emit("accessibility_service_state_changed", ready));
            }
        };
        if (com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge
                .subscribeAccessibilityEvents(listener)) {
            mRemoteAccessibilityListener = listener;
        }
    }

    private void postRemoteAccessibilityEvent(String eventJson) {
        final com.stardust.autojs.runtime.api.remote.RemoteAccessibilityEvent event =
                com.stardust.autojs.runtime.api.remote.RemoteAccessibilityEvent.fromJson(eventJson);
        if (event == null) {
            return;
        }
        postRemoteEvent(() -> {
            emit("accessibility_event", event);
            String kind = event.getKind();
            if (kind != null && !kind.isEmpty() && !"accessibility_event".equals(kind)) {
                emit(kind, event);
            }
        });
    }

    /** 远程回调线程没有 Looper：优先用脚本线程 Handler；没有时退回主 Looper。 */
    private void postRemoteEvent(Runnable runnable) {
        android.os.Handler handler = mHandler;
        if (handler == null) {
            try {
                handler = new android.os.Handler(android.os.Looper.getMainLooper());
            } catch (Throwable error) {
                return;
            }
        }
        handler.post(runnable);
    }

    private void releaseRemoteAccessibilityEvents() {
        com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge.AccessibilityEventListener
                listener = mRemoteAccessibilityListener;
        if (listener == null) {
            return;
        }
        mRemoteAccessibilityListener = null;
        com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge
                .unsubscribeAccessibilityEvents(listener);
    }

    public void observeKey() {
        if (mListeningKey)
            return;
        AccessibilityService service = getAccessibilityService();
        if ((service.getServiceInfo().flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0) {
            throw new ScriptException(mContext.getString(R.string.text_should_enable_key_observing));
        }
        ensureHandler();
        mLoopers.waitWhenIdle(true);
        mListeningKey = true;
        mAccessibilityBridge.ensureServiceEnabled();
        service.getOnKeyObserver().addListener(this);
    }

    private void ensureHandler() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
    }

    /** Enables sensor swipes without enabling touchscreen exploration. */
    public boolean observeFingerprintGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        if (mListeningFingerprint) return true;
        getAccessibilityService();
        mFingerprintHandler = new Handler();
        mFingerprintWaitId = mLoopers.waitWhenIdle();
        mListeningFingerprint = true;
        AccessibilityService.fingerprintGestureDispatcher.addListener(mFingerprintListener);
        return true;
    }

    /** Current availability, including temporary suspension during fingerprint authentication. */
    public boolean isFingerprintGestureAvailable() {
        return AccessibilityService.fingerprintGestureDispatcher.isAvailable();
    }

    public void stopObservingFingerprintGesture() {
        if (!mListeningFingerprint) return;
        mListeningFingerprint = false;
        mFingerprintGeneration++;
        AccessibilityService.fingerprintGestureDispatcher.removeListener(mFingerprintListener);
        final int waitId = mFingerprintWaitId;
        Handler handler = mFingerprintHandler;
        if (Looper.myLooper() == handler.getLooper()) mLoopers.doNotWaitWhenIdle(waitId);
        else handler.post(() -> mLoopers.doNotWaitWhenIdle(waitId));
    }

    private void postFingerprintEvent(String event, Object value) {
        if (!mListeningFingerprint) return;
        int generation = mFingerprintGeneration;
        mFingerprintHandler.post(() -> {
            if (mListeningFingerprint && generation == mFingerprintGeneration) emit(event, value);
        });
    }

    public void observeTouch() {
        if (mTouchObserver != null)
            return;
        ensureHandler();
        mLoopers.waitWhenIdle(true);
        mTouchObserver = new TouchObserver(InputEventObserver.getGlobal(mContext));
        mTouchObserver.setOnTouchEventListener(this);
        mTouchObserver.observe();
    }

    public void setKeyInterceptionEnabled(boolean enabled) {
        mInterceptsAllKey = enabled;
        if (mInterceptsAllKey) {
            ensureKeyInterceptor();
        }
    }

    public void setKeyInterceptionEnabled(String key, boolean enabled) {
        if (enabled) {
            mInterceptedKeys.add(key);
            ensureKeyInterceptor();
        } else {
            mInterceptedKeys.remove(key);
        }
    }

    private void ensureKeyInterceptor() {
        if (mKeyInterceptor != null)
            return;
        mKeyInterceptor = event -> {
            if (mInterceptsAllKey)
                return true;
            String keyName = KeyEvent.keyCodeToString(event.getKeyCode()).substring(8).toLowerCase();
            return mInterceptedKeys.contains(keyName);
        };
        getAccessibilityService().getKeyInterrupterObserver().addKeyInterrupter(mKeyInterceptor);
    }

    private AccessibilityService getAccessibilityService() {
        mScriptRuntime.ensureAccessibilityServiceEnabled();
        AccessibilityService service = mAccessibilityBridge.getService();
        if (service == null)
            throw new ScriptException("AccessibilityService = null");
        return service;
    }


    public Events onKeyDown(String keyName, Object listener) {
        on(PREFIX_KEY_DOWN + keyName, listener);
        return this;
    }

    public Events onceKeyDown(String keyName, Object listener) {
        once(PREFIX_KEY_DOWN + keyName, listener);
        return this;
    }

    public Events removeAllKeyDownListeners(String keyName) {
        removeAllListeners(PREFIX_KEY_DOWN + keyName);
        return this;
    }

    public Events onKeyUp(String keyName, Object listener) {
        on(PREFIX_KEY_UP + keyName, listener);
        return this;
    }

    public Events onceKeyUp(String keyName, Object listener) {
        once(PREFIX_KEY_UP + keyName, listener);
        return this;
    }

    public Events removeAllKeyUpListeners(String keyName) {
        removeAllListeners(PREFIX_KEY_UP + keyName);
        return this;
    }

    public Events onTouch(Object listener) {
        on("touch", listener);
        return this;
    }

    public Events removeAllTouchListeners() {
        removeAllListeners("touch");
        return this;
    }

    public long getTouchEventTimeout() {
        return mTouchEventTimeout;
    }

    public void setTouchEventTimeout(long touchEventTimeout) {
        mTouchEventTimeout = touchEventTimeout;
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    public void observeNotification() {
        ScriptRuntime.requiresApi(18);
        if (mListeningNotification)
            return;
        mListeningNotification = true;
        ensureHandler();
        mLoopers.waitWhenIdle(true);
        if (NotificationListenerService.Companion.getInstance() == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // application context 启动 Activity 必须带 NEW_TASK，否则抛
                // AndroidRuntimeException: Calling startActivity() from outside of an Activity context...
                mContext.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            throw new ScriptException(mContext.getString(R.string.exception_notification_service_disabled));
        }
        NotificationListenerService.Companion.getInstance().addListener(this);
    }

    public void observeToast() {
        if (mListeningToast)
            return;
        mAccessibilityBridge.ensureServiceEnabled();
        mListeningToast = true;
        ensureHandler();
        mLoopers.waitWhenIdle(true);
        mAccessibilityBridge.getNotificationObserver().addToastListener(this);
    }

    public void observeGesture() {
        ScriptRuntime.requiresApi(Build.VERSION_CODES.O);
        if (mListeningGesture) {
            return;
        }
        AccessibilityService service = getAccessibilityService();
        if ((service.getServiceInfo().flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) == 0) {
            throw new ScriptException(mContext.getString(R.string.text_should_enable_gesture_observing));
        }
        service.getGestureEventDispatcher().addListener(this);
        ensureHandler();
        mLoopers.waitWhenIdle(true);
        mListeningGesture = true;
    }

    public Events onNotification(Object listener) {
        on("notification", listener);
        return this;
    }

    public Events onToast(Object listener) {
        on("toast", listener);
        return this;
    }

    // ------------------------------------------------------------------
    // clip_changed（对齐 Auto.js Pro 9.3.11 的 Events）：脚本订阅 clip_changed
    // 时自动挂上剪贴板监听，监听被删空/脚本结束时自动移除。
    // ------------------------------------------------------------------

    private ClipboardManager clipboardManager() {
        if (mContext == null) return null;
        return (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private void addPrimaryClipChangedListener() {
        if (mListeningClip) return;
        ClipboardManager manager = clipboardManager();
        if (manager == null) return;
        manager.addPrimaryClipChangedListener(this);
        mListeningClip = true;
    }

    private void removePrimaryClipChangedListener() {
        if (!mListeningClip) return;
        ClipboardManager manager = clipboardManager();
        if (manager != null) {
            manager.removePrimaryClipChangedListener(this);
        }
        mListeningClip = false;
    }

    @Override
    public void onPrimaryClipChanged() {
        ClipboardManager manager = clipboardManager();
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        if (clip == null) return;
        CharSequence text = (clip.getItemCount() == 0 || clip.getItemAt(0) == null) ? "" : clip.getItemAt(0).getText();
        final String content = text == null ? "" : text.toString();
        ensureHandler();
        mHandler.post(() -> emit(EVENT_CLIP_CHANGED, content, clip));
    }

    private void updateClipListener(String eventName) {
        if (!EVENT_CLIP_CHANGED.equals(eventName)) return;
        if (listenerCount(EVENT_CLIP_CHANGED) > 0) {
            addPrimaryClipChangedListener();
        } else {
            removePrimaryClipChangedListener();
        }
    }

    @Override
    public EventEmitter on(String eventName, Object listener) {
        super.on(eventName, listener);
        updateClipListener(eventName);
        maybeSubscribeRemoteAccessibility(eventName);
        return this;
    }

    @Override
    public EventEmitter once(String eventName, Object listener) {
        super.once(eventName, listener);
        updateClipListener(eventName);
        maybeSubscribeRemoteAccessibility(eventName);
        return this;
    }

    @Override
    public EventEmitter prependListener(String eventName, Object listener) {
        super.prependListener(eventName, listener);
        updateClipListener(eventName);
        maybeSubscribeRemoteAccessibility(eventName);
        return this;
    }

    @Override
    public EventEmitter prependOnceListener(String eventName, Object listener) {
        super.prependOnceListener(eventName, listener);
        updateClipListener(eventName);
        maybeSubscribeRemoteAccessibility(eventName);
        return this;
    }

    @Override
    public EventEmitter removeListener(String eventName, Object listener) {
        super.removeListener(eventName, listener);
        updateClipListener(eventName);
        return this;
    }

    @Override
    public EventEmitter removeAllListeners(String eventName) {
        super.removeAllListeners(eventName);
        updateClipListener(eventName);
        return this;
    }

    @Override
    public EventEmitter removeAllListeners() {
        super.removeAllListeners();
        updateClipListener(EVENT_CLIP_CHANGED);
        return this;
    }


    public void recycle() {
        releaseRemoteAccessibilityEvents();
        stopObservingFingerprintGesture();
        broadcast.unregister();
        removePrimaryClipChangedListener();
        if (mListeningKey) {
            AccessibilityService service = mAccessibilityBridge.getService();
            if (service != null) {
                service.getOnKeyObserver().removeListener(this);
                mListeningKey = false;
            }
        }
        if (mTouchObserver != null) {
            mTouchObserver.stop();
        }
        if (mListeningNotification) {
            mAccessibilityBridge.getNotificationObserver().removeNotificationListener(this);
            mAccessibilityBridge.getNotificationObserver().removeToastListener(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                    && NotificationListenerService.Companion.getInstance() != null) {
                NotificationListenerService.Companion.getInstance().removeListener(this);
            }
        }
        if (mKeyInterceptor != null) {
            AccessibilityService service = mAccessibilityBridge.getService();
            if (service != null) {
                service.getKeyInterrupterObserver().removeKeyInterrupter(mKeyInterceptor);
            }
            mKeyInterceptor = null;
        }
        if (mListeningGesture) {
            AccessibilityService service = mAccessibilityBridge.getService();
            if (service != null) {
                service.getGestureEventDispatcher().removeListener(this);
            }
        }
    }

    @Override
    public void onKeyEvent(final int keyCode, final KeyEvent event) {
        mHandler.post(() -> {
            String keyName = KeyEvent.keyCodeToString(keyCode).substring(8).toLowerCase();
            emit(keyName, event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                emit(PREFIX_KEY_DOWN + keyName, event);
                emit("key_down", keyCode, event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                emit(PREFIX_KEY_UP + keyName, event);
                emit("key_up", keyCode, event);
            }
            emit("key", keyCode, event);
        });
    }

    @Override
    public void onTouch(final int x, final int y) {
        if (System.currentTimeMillis() - mLastTouchEventMillis < mTouchEventTimeout) {
            return;
        }
        mLastTouchEventMillis = System.currentTimeMillis();
        mHandler.post(() -> emit("touch", new Point(x, y)));
    }

    public void onNotification(final Notification notification) {
        mHandler.post(() -> emit("notification", notification));

    }

    @Override
    public void onToast(final AccessibilityNotificationObserver.Toast toast) {
        mHandler.post(() -> emit("toast", toast));
    }

    @Override
    public void onGesture(int gestureId) {
        mHandler.post(() -> {
            String gesture = GESTURES.get(gestureId);
            if (gesture != null) {
                emit("gesture", gesture);
            }
        });
    }
}
