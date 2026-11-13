package com.stardust.autojs.core.console;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.WindowManager;

import com.stardust.autojs.R;
import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.AbstractConsole;
import com.stardust.autojs.runtime.api.Console;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.util.FloatingPermission;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.ResizableExpandableFloatyWindow;
import com.stardust.util.UiHandler;
import com.stardust.util.ViewUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Stardust on 2017/5/2.
 */

public class ConsoleImpl extends AbstractConsole {

    public static class LogEntry implements Comparable<LogEntry> {

        public int id;
        public int level;
        public CharSequence content;
        public boolean newLine = false;
        public final long time;

        public LogEntry(int id, int level, CharSequence content) {
            this(id, level, content, false);
        }

        public LogEntry(int id, int level, CharSequence content, boolean newLine) {
            this(id, level, content, newLine, System.currentTimeMillis());
        }

        public LogEntry(int id, int level, CharSequence content, boolean newLine, long time) {
            this.id = id;
            this.level = level;
            this.content = content;
            this.newLine = newLine;
            this.time = time;
        }

        @Override
        public int compareTo(@NonNull LogEntry o) {
            return 0;
        }
    }

    public interface LogListener {
        void onNewLog(LogEntry logEntry);

        void onLogClear();
    }

    private final Object WINDOW_SHOW_LOCK = new Object();
    private final Console mGlobalConsole;
    private final ArrayList<LogEntry> mLogEntries = new ArrayList<>();
    private AtomicInteger mIdCounter = new AtomicInteger(0);
    private ResizableExpandableFloatyWindow mFloatyWindow;
    private ConsoleFloaty mConsoleFloaty;
    private WeakReference<LogListener> mLogListener;
    private final java.util.concurrent.CopyOnWriteArrayList<WeakReference<LogListener>> mObservers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private UiHandler mUiHandler;
    private BlockingQueue<String> mInput = new ArrayBlockingQueue<>(1);
    private WeakReference<ConsoleView> mConsoleView;
    private volatile boolean mShown = false;
    private int mX, mY;
    /** show() 之前调用的 setSize 也要生效：记住尺寸，窗口创建后补应用（与 setPosition 一致）。 */
    private int mWidth = -1, mHeight = -1;

    /** 这次运行的落盘日志（见 {@link ScriptRunLog}）；null = 不落盘（如全局控制台）。 */
    private volatile ScriptRunLog mRunLog;

    public ConsoleImpl(UiHandler uiHandler) {
        this(uiHandler, null);
    }

    public ConsoleImpl(UiHandler uiHandler, Console globalConsole) {
        mUiHandler = uiHandler;
        mConsoleFloaty = new ConsoleFloaty(this);
        mGlobalConsole = globalConsole;
        mFloatyWindow = new ResizableExpandableFloatyWindow(mConsoleFloaty) {
            @Override
            public void onCreate(Context context, WindowManager manager) {
                super.onCreate(context, manager);
                expand();
                mFloatyWindow.getWindowBridge().updatePosition(mX, mY);
                // 尺寸也要补应用（见 applyPendingSize 注释）。
                mUiHandler.post(() -> applyPendingSize());
                mUiHandler.postDelayed(() -> applyPendingSize(), 100);
                synchronized (WINDOW_SHOW_LOCK) {
                    mShown = true;
                    WINDOW_SHOW_LOCK.notifyAll();
                }
            }
        };
    }

    public void setConsoleView(ConsoleView consoleView) {
        mConsoleView = new WeakReference<>(consoleView);
        addLogListener(consoleView);
        synchronized (this) {
            this.notify();
        }
    }


    public void setLogListener(LogListener logListener) {
        mLogListener = new WeakReference<>(logListener);
    }

    /** Views subscribe independently; a new console must not steal another view's updates. */
    public synchronized void addLogListener(LogListener listener) {
        if (listener == null) return;
        for (WeakReference<LogListener> ref : mObservers) {
            LogListener existing = ref.get();
            if (existing == listener) return;
            if (existing == null) mObservers.remove(ref);
        }
        mObservers.add(new WeakReference<>(listener));
    }

    public void removeLogListener(LogListener listener) {
        for (WeakReference<LogListener> ref : mObservers) {
            if (ref.get() == null || ref.get() == listener) mObservers.remove(ref);
        }
    }

    private void notifyLogListeners(LogEntry entry) {
        LogListener legacy = mLogListener == null ? null : mLogListener.get();
        if (legacy != null) {
            notifyLogListener(legacy, entry);
        }
        for (WeakReference<LogListener> ref : mObservers) {
            LogListener listener = ref.get();
            if (listener == null) mObservers.remove(ref);
            else if (listener != legacy) {
                notifyLogListener(listener, entry);
            }
        }
    }

    private static void notifyLogListener(LogListener listener, LogEntry entry) {
        try {
            if (entry == null) listener.onLogClear(); else listener.onNewLog(entry);
        } catch (Throwable error) {
            android.util.Log.w("Console", "log observer failed", error);
        }
    }

    public ArrayList<LogEntry> getAllLogs() {
        return mLogEntries;
    }

    public ArrayList<LogEntry> snapshotLogs() {
        synchronized (mLogEntries) { return new ArrayList<>(mLogEntries); }
    }

    /** 挂上落盘日志；只给「这次运行自己的控制台」挂（见 {@link ScriptRunLog#attach}）。 */
    public void setRunLog(ScriptRunLog runLog) {
        mRunLog = runLog;
    }

    public void printAllStackTrace(Throwable t) {
        println(android.util.Log.ERROR, ScriptRuntime.getStackTrace(t, true));
    }

    public String getStackTrace(Throwable t) {
        return ScriptRuntime.getStackTrace(t, false);
    }

    @Override
    public String println(int level, CharSequence charSequence) {
        return printLine(level, charSequence, System.currentTimeMillis());
    }

    protected String printLine(int level, CharSequence charSequence, long time) {
        ScriptRunLog runLog = mRunLog;
        if (runLog != null) {
            runLog.line(level, charSequence);
        }
        LogEntry logEntry = new LogEntry(mIdCounter.getAndIncrement(), level, charSequence, true, time);
        synchronized (mLogEntries) {
            mLogEntries.add(logEntry);
        }
        if (mGlobalConsole != null) {
            mGlobalConsole.println(level, charSequence);
        }
        notifyLogListeners(logEntry);
        return null;
    }


    @Override
    public void write(int level, CharSequence charSequence) {
        println(level, charSequence);
    }


    @Override
    public void clear() {
        synchronized (mLogEntries) {
            mLogEntries.clear();
        }
        notifyLogListeners(null);
    }

    @Override
    public void show() {
        if (mShown) {
            return;
        }
        if (!FloatingPermission.canDrawOverlays(mUiHandler.getContext())) {
            FloatingPermission.manageDrawOverlays(mUiHandler.getContext());
            mUiHandler.toast(R.string.text_no_floating_window_permission);
            return;
        }
        startFloatyService();
        mUiHandler.post(() -> {
            try {
                FloatyService.addWindow(mFloatyWindow);
                // SecurityException: https://github.com/hyb1996-guest/AutoJsIssueReport/issues/4781
            } catch (WindowManager.BadTokenException | SecurityException e) {
                // task-134：裸 printStackTrace 在 release 里只进 stdout ⇒ 改 Log.w，保留异常链（不改控制流）。
                android.util.Log.w("ConsoleImpl", "public void show() failed", e);
                mUiHandler.toast(R.string.text_no_floating_window_permission);
            }
        });
        synchronized (WINDOW_SHOW_LOCK) {
            if (mShown) {
                return;
            }
            try {
                WINDOW_SHOW_LOCK.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void startFloatyService() {
        Context context = mUiHandler.getContext();
        context.startService(FloatyService.getServiceIntent(context));
    }

    @Override
    public void hide() {
        mUiHandler.post(() -> {
            synchronized (WINDOW_SHOW_LOCK) {
                if (!mShown)
                    return;
                try {
                    mFloatyWindow.close();
                } catch (IllegalArgumentException ignored) {

                }
                mShown = false;
            }
        });
    }


    public void setSize(int w, int h) {
        mWidth = w;
        mHeight = h;
        if (mShown) {
            mUiHandler.post(() -> {
                if (mShown) applyPendingSize();
            });
        }
    }

    /**
     * 应用最近一次 setSize 的尺寸。
     * `.4` 实测「日志窗口一片空白」根因：脚本常常先 setSize/setPosition 再 show()，而旧实现只在
     * mShown 时才应用尺寸 → 尺寸被丢弃 → 展开视图保持 WRAP_CONTENT，带 weight 的日志列表被量成 0 高，
     * 于是窗口里什么都看不到（数据其实一直在）。setPosition 早就用 mX/mY 做了这件事，这里补齐。
     */
    void applyPendingSize() {
        if (mWidth <= 0 || mHeight <= 0) return;
        android.view.View view = mConsoleFloaty == null ? null : mConsoleFloaty.getExpandedView();
        if (view == null) return;
        ViewUtil.setViewMeasure(view, mWidth, mHeight);
    }

    public void setPosition(int x, int y) {
        mX = x;
        mY = y;
        if (mShown) {
            mUiHandler.post(() -> {
                if (mShown)
                    mFloatyWindow.getWindowBridge().updatePosition(x, y);
            });
        }
    }

    @ScriptInterface
    public String rawInput() {
        if (mConsoleView == null || mConsoleView.get() == null) {
            if (!mShown) {
                show();
            }
            waitForConsoleView();
        }
        mConsoleView.get().showEditText();
        try {
            return mInput.take();
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
    }

    private void waitForConsoleView() {
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                throw new ScriptInterruptedException();
            }
        }
    }

    @ScriptInterface
    public String rawInput(Object data, Object... param) {
        log(data, param);
        return rawInput();
    }

    boolean submitInput(@NonNull CharSequence input) {
        return mInput.offer(input.toString());
    }

    @Override
    public void setTitle(CharSequence title) {
        mConsoleFloaty.setTitle(title);
    }

    @Override
    public void error(@Nullable Object data, Object... options) {
        if (data instanceof Throwable) {
            data = getStackTrace((Throwable) data);
        }
        if (options != null && options.length > 0) {
            StringBuilder sb = new StringBuilder(data == null ? "" : data.toString());
            ArrayList<Object> newOptions = new ArrayList<>();
            for (Object option : options) {
                if (option instanceof Throwable) {
                    sb.append(getStackTrace((Throwable) option)).append(" ");
                } else {
                    newOptions.add(option);
                }
            }
            data = sb.toString();
            if (newOptions.isEmpty()) {
                super.error(data, newOptions.toArray());
            } else {
                super.error(data);
            }
        } else {
            super.error(data, options);
        }
    }
}
