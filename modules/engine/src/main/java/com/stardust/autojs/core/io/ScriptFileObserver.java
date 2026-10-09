package com.stardust.autojs.core.io;

import android.os.FileObserver;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto.js Pro 的 `$files.observe(path)`：用 {@link FileObserver} 监听目录/文件变化，
 * 事件按 `watcher.on("any"|"create"|"delete"|"modify"|"move"|"attrib", function(event, path){})`
 * 派发给脚本，回调在线程池事件后投递到脚本线程执行。
 *
 * <p>2026-09-30 对齐：事件名与 Pro 的 Files.fileEventToString 完全一致
 * （access/modify/attrib/close_write/close_nowrite/open/moved_from/moved_to/create/delete/
 * delete_self/move_self），并在监听期间 waitWhenIdle（脚本不因结尾而退出，与 Pro 相同）。
 */
public class ScriptFileObserver extends EventEmitter {

    private final String mPath;
    private final ScriptRuntime mRuntime;
    private final FileObserver mObserver;
    private volatile boolean mClosed = false;
    private int mWaitId = -1;

    public ScriptFileObserver(ScriptRuntime runtime, String path) {
        super(runtime.bridges, runtime.timers.getMainTimer());
        mRuntime = runtime;
        mPath = path;
        mObserver = new FileObserver(path, FileObserver.ALL_EVENTS) {
            @Override
            public void onEvent(int event, @Nullable String relativePath) {
                dispatch(event, relativePath);
            }
        };
        startWatching();
    }

    private void dispatch(int event, String relativePath) {
        if (mClosed) {
            return;
        }
        for (String name : eventNames(event)) {
            emit(name, relativePath);
            emit("any", name, relativePath);
        }
    }

    /**
     * 与 Auto.js Pro 的 {@code Files.fileEventToString} 同一套事件名（inotify 位到名字）。
     * QuickJS 的 `files.observe` 也复用这张表，保证两个引擎事件名一致。
     */
    public static String[] eventNames(int event) {
        List<String> names = new ArrayList<>(2);
        if ((event & FileObserver.ACCESS) != 0) names.add("access");
        if ((event & FileObserver.MODIFY) != 0) names.add("modify");
        if ((event & FileObserver.ATTRIB) != 0) names.add("attrib");
        if ((event & FileObserver.CLOSE_WRITE) != 0) names.add("close_write");
        if ((event & FileObserver.CLOSE_NOWRITE) != 0) names.add("close_nowrite");
        if ((event & FileObserver.OPEN) != 0) names.add("open");
        if ((event & FileObserver.MOVED_FROM) != 0) names.add("moved_from");
        if ((event & FileObserver.MOVED_TO) != 0) names.add("moved_to");
        if ((event & FileObserver.CREATE) != 0) names.add("create");
        if ((event & FileObserver.DELETE) != 0) names.add("delete");
        if ((event & FileObserver.DELETE_SELF) != 0) names.add("delete_self");
        if ((event & FileObserver.MOVE_SELF) != 0) names.add("move_self");
        return names.toArray(new String[0]);
    }

    /**
     * 与 Pro 的 FileEventEmitter 一致：监听期间脚本保持运行（waitWhenIdle），stopWatching 后释放。
     */
    public void startWatching() {
        if (!mClosed && mWaitId != -1) {
            return;
        }
        mClosed = false;
        mObserver.startWatching();
        if (mWaitId == -1) {
            mWaitId = mRuntime.loopers.waitWhenIdle();
        }
    }

    public void stopWatching() {
        mObserver.stopWatching();
        mClosed = true;
        if (mWaitId != -1) {
            mRuntime.loopers.doNotWaitWhenIdle(mWaitId);
            mWaitId = -1;
        }
    }

    public boolean isWatching() {
        return !mClosed;
    }

    public String getPath() {
        return mPath;
    }
}
