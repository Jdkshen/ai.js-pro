package com.stardust.autojs.core.io;

import android.os.FileObserver;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.runtime.ScriptRuntime;

/**
 * Auto.js Pro 的 `$files.observe(path)`：用 {@link FileObserver} 监听目录/文件变化，
 * 事件按 `watcher.on("any"|"create"|"delete"|"modify"|"move"|"attrib", function(event, path){})`
 * 派发给脚本，回调在线程池事件后投递到脚本线程执行。
 */
public class ScriptFileObserver extends EventEmitter {

    private final String mPath;
    private final FileObserver mObserver;
    private volatile boolean mClosed = false;

    public ScriptFileObserver(ScriptRuntime runtime, String path) {
        super(runtime.bridges, runtime.timers.getMainTimer());
        mPath = path;
        mObserver = new FileObserver(path, FileObserver.ALL_EVENTS) {
            @Override
            public void onEvent(int event, @Nullable String relativePath) {
                dispatch(event, relativePath);
            }
        };
        mObserver.startWatching();
    }

    private void dispatch(int event, String relativePath) {
        if (mClosed) {
            return;
        }
        String name = eventName(event);
        if (name == null) {
            // OPEN/ACCESS/CLOSE_* 这类噪声事件不派发，与 Auto.js Pro 的语义保持一致
            return;
        }
        String child = relativePath == null ? mPath : relativePath;
        emit("any", name, child);
        emit(name, child);
    }

    @Nullable
    private static String eventName(int event) {
        int mask = event & FileObserver.ALL_EVENTS;
        if ((mask & FileObserver.CREATE) != 0) {
            return "create";
        }
        if ((mask & FileObserver.DELETE) != 0) {
            return "delete";
        }
        if ((mask & FileObserver.MOVED_FROM) != 0 || (mask & FileObserver.MOVED_TO) != 0) {
            return "move";
        }
        if ((mask & FileObserver.MODIFY) != 0) {
            return "modify";
        }
        if ((mask & FileObserver.ATTRIB) != 0) {
            return "attrib";
        }
        return null;
    }

    public void startWatching() {
        mClosed = false;
        mObserver.startWatching();
    }

    public void stopWatching() {
        mClosed = true;
        mObserver.stopWatching();
    }

    public boolean isWatching() {
        return !mClosed;
    }

    public String getPath() {
        return mPath;
    }
}
