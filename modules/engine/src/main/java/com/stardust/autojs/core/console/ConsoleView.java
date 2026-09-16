package com.stardust.autojs.core.console;

import android.content.Context;
import android.content.res.TypedArray;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stardust.enhancedfloaty.ResizableExpandableFloatyWindow;
import com.stardust.autojs.R;
import com.stardust.autojs.runtime.api.Console;
import com.stardust.util.MapBuilder;
import com.stardust.util.SparseArrayEntries;

import java.util.ArrayList;
import java.util.Map;

/**
 * Created by Stardust on 2017/5/2.
 * <p>
 * TODO: 优化为无锁形式
 */
public class ConsoleView extends FrameLayout implements ConsoleImpl.LogListener {

    private static final Map<Integer, Integer> ATTRS = new MapBuilder<Integer, Integer>()
            .put(R.styleable.ConsoleView_color_verbose, Log.VERBOSE)
            .put(R.styleable.ConsoleView_color_debug, Log.DEBUG)
            .put(R.styleable.ConsoleView_color_info, Log.INFO)
            .put(R.styleable.ConsoleView_color_warn, Log.WARN)
            .put(R.styleable.ConsoleView_color_error, Log.ERROR)
            .put(R.styleable.ConsoleView_color_assert, Log.ASSERT)
            .build();

    static final SparseArray<Integer> COLORS = new SparseArrayEntries<Integer>()
            .entry(Log.VERBOSE, 0xdfc0c0c0)
            .entry(Log.DEBUG, 0xdfffffff)
            .entry(Log.INFO, 0xff64dd17)
            .entry(Log.WARN, 0xff2962ff)
            .entry(Log.ERROR, 0xffd50000)
            .entry(Log.ASSERT, 0xffff534e)
            .sparseArray();

    private static final int REFRESH_INTERVAL = 100;
    private SparseArray<Integer> mColors = COLORS.clone();
    private ConsoleImpl mConsole;
    private RecyclerView mLogListRecyclerView;
    private EditText mEditText;
    private ResizableExpandableFloatyWindow mWindow;
    private LinearLayout mInputContainer;
    private boolean mShouldStopRefresh = false;
    private ArrayList<ConsoleImpl.LogEntry> mLogEntries = new ArrayList<>();
    private ArrayList<ConsoleImpl.LogEntry> mFilteredEntries = new ArrayList<>();
    private String mSearchQuery = "";
    private int mSeenLogCount = 0;
    private int mMinimumLogLevel = Log.VERBOSE;
    /** 0 表示用布局默认字号（见 setTextSize）。 */
    private float mTextSizeSp = 0f;

    public ConsoleView(Context context) {
        super(context);
        init(null);
    }

    public ConsoleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public ConsoleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    public void setColors(SparseArray<Integer> colors) {
        mColors = colors == null ? COLORS.clone() : colors.clone();
        if (mLogListRecyclerView != null && mLogListRecyclerView.getAdapter() != null) {
            mLogListRecyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    private void init(AttributeSet attrs) {
        inflate(getContext(), R.layout.console_view, this);
        if (attrs != null) {
            TypedArray typedArray = getContext().obtainStyledAttributes(attrs, R.styleable.ConsoleView);
            for (Map.Entry<Integer, Integer> attr : ATTRS.entrySet()) {
                int styleable = attr.getKey();
                int logLevel = attr.getValue();
                mColors.put(logLevel, typedArray.getColor(styleable, mColors.get(logLevel)));
            }
        }
        mLogListRecyclerView = findViewById(R.id.log_list);
        LinearLayoutManager manager = new LinearLayoutManager(getContext());
        mLogListRecyclerView.setLayoutManager(manager);
        mLogListRecyclerView.setAdapter(new Adapter());
        initEditText();
        initSubmitButton();
    }

    private void initSubmitButton() {
        final Button submit = findViewById(R.id.submit);
        submit.setOnClickListener(v -> {
            CharSequence input = mEditText.getText();
            submitInput(input);
        });
    }

    private void submitInput(CharSequence input) {
        if (android.text.TextUtils.isEmpty(input)) {
            return;
        }
        if (mConsole.submitInput(input)) {
            mEditText.setText("");
        }
    }

    private void initEditText() {
        mEditText = findViewById(R.id.input);
        mEditText.setFocusableInTouchMode(true);
        mInputContainer = findViewById(R.id.input_container);
        OnClickListener listener = v -> {
            if (mWindow != null) {
                mWindow.requestWindowFocus();
                mEditText.requestFocus();
            }
        };
        mEditText.setOnClickListener(listener);
        mInputContainer.setOnClickListener(listener);
    }

    public void setConsole(ConsoleImpl console) {
        mConsole = console;
        mConsole.setConsoleView(this);
    }

    /**
     * Auto.js 的 {@code ui.console.setConsole(runtime.console)}。
     *
     * <p>形参写成接口 {@link Console} 而不是实现类：Rhino / QuickJS 拿到的 Java 对象
     * （{@code runtime.console}）能按声明类型正确转换过来。注意脚本里那个全局 {@code console}
     * 是 JS 包装对象（见 {@code __console__.js}），要用 {@code runtime.console}。
     * 不匹配实现类时保持原样 —— 总比整个脚本抛异常好。
     */
    public void setConsole(Console console) {
        if (console instanceof ConsoleImpl) {
            setConsole((ConsoleImpl) console);
        }
    }

    /** Auto.js 的 {@code ui.console.input}：底部那个输入框。 */
    public EditText getInput() {
        return mEditText;
    }

    /** Auto.js 的 {@code setInputEnabled(false)}：只用来看日志时把输入区收起来。 */
    public void setInputEnabled(final boolean enabled) {
        post(() -> mInputContainer.setVisibility(enabled ? VISIBLE : GONE));
    }

    /** Auto.js 的 {@code setColor("V", "#bdbdbd")}：按日志级别改颜色。 */
    public void setColor(String level, String color) {
        Integer logLevel = logLevelOf(level);
        if (logLevel == null || android.text.TextUtils.isEmpty(color)) {
            return;
        }
        final int parsed;
        try {
            parsed = android.graphics.Color.parseColor(color.trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        mColors.put(logLevel, parsed);
        post(() -> {
            if (mLogListRecyclerView != null && mLogListRecyclerView.getAdapter() != null) {
                mLogListRecyclerView.getAdapter().notifyDataSetChanged();
            }
        });
    }

    /** Auto.js 的 {@code setTextSize(12)}（单位 sp）；≤0 表示不管。 */
    public void setTextSize(float sizeSp) {
        mTextSizeSp = sizeSp > 0 ? sizeSp : 0f;
        post(() -> {
            if (mLogListRecyclerView != null && mLogListRecyclerView.getAdapter() != null) {
                mLogListRecyclerView.getAdapter().notifyDataSetChanged();
            }
        });
    }

    private static Integer logLevelOf(String level) {
        if (level == null || level.isEmpty()) {
            return null;
        }
        switch (Character.toUpperCase(level.trim().charAt(0))) {
            case 'V': return Log.VERBOSE;
            case 'D': return Log.DEBUG;
            case 'I': return Log.INFO;
            case 'W': return Log.WARN;
            case 'E': return Log.ERROR;
            case 'A': return Log.ASSERT;
            default: return null;
        }
    }

    /** Filters the embedded console without deleting entries from the global console. */
    public void setMinimumLogLevel(int level) {
        mMinimumLogLevel = level;
        post(() -> {
            mLogEntries.clear();
            mSeenLogCount = 0;
            appendNewLogs();
            rebuildFiltered();
            mLogListRecyclerView.getAdapter().notifyDataSetChanged();
            if (!mFilteredEntries.isEmpty()) {
                mLogListRecyclerView.scrollToPosition(mFilteredEntries.size() - 1);
            }
        });
    }

    /** Filters the visible log entries by a plain-text keyword (display only). */
    public void setSearchQuery(String query) {
        mSearchQuery = query == null ? "" : query.trim().toLowerCase();
        post(() -> {
            rebuildFiltered();
            mLogListRecyclerView.getAdapter().notifyDataSetChanged();
            if (!mFilteredEntries.isEmpty()) {
                mLogListRecyclerView.scrollToPosition(mFilteredEntries.size() - 1);
            }
        });
    }

    private void rebuildFiltered() {
        mFilteredEntries.clear();
        for (ConsoleImpl.LogEntry entry : mLogEntries) {
            String content = entry.content == null ? "" : entry.content.toString();
            if (mSearchQuery.isEmpty() || content.toLowerCase().contains(mSearchQuery)) {
                mFilteredEntries.add(entry);
            }
        }
    }

    @Override
    public void onNewLog(ConsoleImpl.LogEntry logEntry) {

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mShouldStopRefresh = false;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshLog();
                if (!mShouldStopRefresh) {
                    postDelayed(this, REFRESH_INTERVAL);
                }
            }
        }, REFRESH_INTERVAL);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mShouldStopRefresh = true;
    }


    @Override
    public void onLogClear() {
        post(() -> {
            mLogEntries.clear();
            mFilteredEntries.clear();
            mSeenLogCount = 0;
            mLogListRecyclerView.getAdapter().notifyDataSetChanged();
        });
    }

    private void refreshLog() {
        int oldVisibleSize = mFilteredEntries.size();
        appendNewLogs();
        rebuildFiltered();
        int inserted = mFilteredEntries.size() - oldVisibleSize;
        if (inserted > 0) {
            mLogListRecyclerView.getAdapter().notifyDataSetChanged();
            mLogListRecyclerView.scrollToPosition(mFilteredEntries.size() - 1);
        }
    }

    private void appendNewLogs() {
        if (mConsole == null)
            return;
        ArrayList<ConsoleImpl.LogEntry> logEntries = mConsole.getAllLogs();
        synchronized (logEntries) {
            final int size = logEntries.size();
            if (mSeenLogCount > size) mSeenLogCount = 0;
            for (int i = mSeenLogCount; i < size; i++) {
                ConsoleImpl.LogEntry entry = logEntries.get(i);
                if (entry.level >= mMinimumLogLevel) mLogEntries.add(entry);
            }
            mSeenLogCount = size;
        }
    }

    public void setWindow(ResizableExpandableFloatyWindow window) {
        mWindow = window;
    }

    public void showEditText() {
        post(() -> {
            mWindow.requestWindowFocus();
            //mInputContainer.setVisibility(VISIBLE);
            mEditText.requestFocus();
        });
    }

    private class ViewHolder extends RecyclerView.ViewHolder {

        TextView textView;

        public ViewHolder(View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.console_view_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ConsoleImpl.LogEntry logEntry = mFilteredEntries.get(position);
            holder.textView.setText(logEntry.content);
            holder.textView.setTextColor(mColors.get(logEntry.level));
            if (mTextSizeSp > 0) {
                holder.textView.setTextSize(mTextSizeSp);
            }
            // 长按复制单条日志；悬浮控制台与日志页共用这段逻辑。
            holder.itemView.setOnLongClickListener(v -> copyLogEntry(v.getContext(), logEntry.content));
        }

        @Override
        public int getItemCount() {
            return mFilteredEntries.size();
        }
    }

    /** 长按复制单条日志（悬浮控制台与日志页共用）。 */
    private static boolean copyLogEntry(android.content.Context context, CharSequence content) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return false;
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("log", content));
        android.widget.Toast.makeText(context, "已复制该条日志", android.widget.Toast.LENGTH_SHORT).show();
        return true;
    }
}
