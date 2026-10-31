package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node UI 的轻量 RecyclerView：不依赖 ScriptRuntime，item 模板只做
 * {@code {{this.xxx}}} / {@code {{this.enabled ? a : b}}} 的只读绑定。
 *
 * <p>线程模型：所有 Adapter 操作都在主线程。{@code item_created} 通过
 * {@link ItemCreatedListener} 在单线程后台执行器回调，避免 Node 侧在回调里
 * findViewById/设点击时与主线程布局互相等待；回调前用 bindVersion 丢弃已回收的旧项。
 * holder 的 position/data 每次访问都读 Adapter 的最新值，不用创建时快照。
 */
public abstract class NodeUiRecyclerView extends RecyclerView {

    private static final String TAG = "NodeUiRecyclerView";
    private static final Pattern DYNAMIC = Pattern.compile("\\{\\{(.*?)\\}\\}");

    /** item_created 回调（由 ui.js 的真实 Java 接口桥注册）。 */
    public interface ItemCreatedListener {
        void onItemCreated(View itemView, NodeUiItemHolder holder);
    }

    /** JS 侧 holder.position / holder.data 的数据源：访问时取最新，不缓存。 */
    public static final class NodeUiItemHolder {
        private final NodeUiRecyclerView owner;
        private final RecyclerView.ViewHolder holder;

        NodeUiItemHolder(NodeUiRecyclerView owner, RecyclerView.ViewHolder holder) {
            this.owner = owner;
            this.holder = holder;
        }

        public int getPosition() {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                position = holder.getAbsoluteAdapterPosition();
            }
            return position;
        }

        public String getDataJson() {
            return owner.itemJsonAt(getPosition());
        }

        /** 方便脚本/调试；ui.js 会包成 holder.data。 */
        public Object getData() {
            return owner.itemAt(getPosition());
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private static ExecutorService newItemExecutor() { return Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NodeUiItemCreated");
        thread.setDaemon(true);
        return thread;
    }); }
    private ExecutorService itemCallbacks = newItemExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private String itemTemplate;
    private JSONArray data = new JSONArray();
    private ItemCreatedListener itemCreatedListener;
    private NodeAdapter adapter;
    /**
     * bug 2.6：{@code initAdapterFromChildren()} 采到的子 View。
     *
     * <p>{@code <include>} 由 inflater 展开成真实子 View 后挂在 list/grid/viewPager 上，
     * 所以这里的 child 数量天然包含 include 展开的页面；adapter 的 item 数随之正确。
     */
    private final List<View> childItems = new ArrayList<>();
    private boolean childrenMode;

    protected NodeUiRecyclerView(Context context) {
        super(context);
        init();
    }

    protected NodeUiRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    protected NodeUiRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setItemAnimator(null);
        setClipToPadding(false);
        adapter = new NodeAdapter();
        setAdapter(adapter);
    }

    public void setOrientation(int orientation) {
        LayoutManager manager = getLayoutManager();
        if (manager instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            ((androidx.recyclerview.widget.LinearLayoutManager) manager).setOrientation(orientation);
        }
    }

    /** 真实 Java 入口；ui.js 若已拿到属性 hook 会改用 nativeSetItemTemplate。 */
    public void setItemTemplate(String xml) {
        // Keep source names until bind: a script can register a Widget after
        // setting the template, and row/switch/etc may be custom components.
        final String normalized = xml == null ? "" : xml;
        runOnMain(() -> {
            itemTemplate = normalized;
            exitChildrenMode();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    /** ui.js 通过 __ajsJavaViewMember 的 setItemTemplate hook 调用，避免再次经过属性分发。 */
    public void nativeSetItemTemplate(String xml) {
        setItemTemplate(xml);
    }

    public String getItemTemplate() {
        return itemTemplate;
    }

    /** 真实 Java 入口：接受 Object[]（Node 数组桥）或 JSON 文本。 */
    public void setDataSource(Object source) {
        final JSONArray next = toDataArray(source);
        runOnMain(() -> {
            data = next;
            exitChildrenMode();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    public void nativeSetDataSource(Object source) {
        setDataSource(source);
    }

    public void setItemCreatedListener(ItemCreatedListener listener) {
        runOnMain(() -> itemCreatedListener = listener);
    }

    // ---- JS adapter facade 的 native 同步入口（数据先更新，再发粒度通知） ----

    public void nativeNotifyItemInserted(Object source, int index) {
        final JSONArray next = toDataArray(source);
        runOnMain(() -> {
            data = next;
            exitChildrenMode();
            if (adapter == null) return;
            try {
                if (index < 0 || index >= next.length()) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemInserted(index);
                }
            } catch (Throwable error) {
                Log.w(TAG, "notifyItemInserted 失败，回退 notifyDataSetChanged", error);
                adapter.notifyDataSetChanged();
            }
        });
    }

    public void nativeNotifyItemRemoved(Object source, int index) {
        final JSONArray next = toDataArray(source);
        runOnMain(() -> {
            data = next;
            exitChildrenMode();
            if (adapter == null) return;
            try {
                // 删除最后一项后 index 已等于新长度，这时粒度通知越界，直接整表刷新。
                if (index < 0 || index >= next.length()) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemRemoved(index);
                }
            } catch (Throwable error) {
                Log.w(TAG, "notifyItemRemoved 失败，回退 notifyDataSetChanged", error);
                adapter.notifyDataSetChanged();
            }
        });
    }

    public void nativeNotifyItemChanged(Object source, int index) {
        final JSONArray next = toDataArray(source);
        runOnMain(() -> {
            data = next;
            exitChildrenMode();
            if (adapter == null) return;
            try {
                if (index < 0 || index >= next.length()) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemChanged(index);
                }
            } catch (Throwable error) {
                Log.w(TAG, "notifyItemChanged 失败，回退 notifyDataSetChanged", error);
                adapter.notifyDataSetChanged();
            }
        });
    }

    public void nativeNotifyDataSetChanged(Object source) {
        final JSONArray next = toDataArray(source);
        runOnMain(() -> {
            data = next;
            exitChildrenMode();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    public int getNodeItemCount() {
        return childrenMode ? childItems.size() : (data == null ? 0 : data.length());
    }

    /**
     * Auto.js Pro {@code initAdapterFromChildren()} 的 list/grid 版本：把当前已经 inflate 出来的
     * 子 View 当作 adapter 的 item。
     *
     * <p>{@code <include>} 已经在 inflater 阶段展开成真实子 View 并挂到本控件上，所以每个 include
     * 页面都会计入 child/adapter 数量（bug 2.6 的「展开的页面要计入」）。幂等：已有显式
     * dataSource / itemTemplate 时不覆盖；重复调用不会重复采集。
     */
    public void initAdapterFromChildren() {
        runOnMain(this::adoptChildren);
    }

    /** 由子 View 生成的 item 数（诊断/断言用）。 */
    public int getChildItemCount() {
        return childItems.size();
    }

    /** 当前是否处于「子 View 当 item」模式。 */
    public boolean isChildrenMode() {
        return childrenMode;
    }

    private void adoptChildren() {
        if (childrenMode || hasExplicitItems() || getChildCount() == 0) {
            return;
        }
        List<View> collected = new ArrayList<>(getChildCount());
        for (int i = 0; i < getChildCount(); i++) {
            collected.add(getChildAt(i));
        }
        // RecyclerView 自己管理子 View，直接挂着会被布局管理器当成脏子节点；先摘下来，再由
        // adapter 的 ViewHolder 容器按 position 挂回，item 数 = 采集到的子 View 数。
        removeAllViews();
        childItems.clear();
        childItems.addAll(collected);
        childrenMode = true;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        Log.i(TAG, "initAdapterFromChildren: " + childItems.size() + " 个子 View 转为 item");
    }

    private boolean hasExplicitItems() {
        return (itemTemplate != null && !itemTemplate.trim().isEmpty())
                || (data != null && data.length() > 0);
    }

    /** 显式 dataSource / itemTemplate 优先：一旦设置就退出 children 模式。 */
    private void exitChildrenMode() {
        if (!childrenMode) {
            return;
        }
        childrenMode = false;
        childItems.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        destroyed.set(true);
        itemCallbacks.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        destroyed.set(false);
        if (itemCallbacks.isShutdown()) itemCallbacks = newItemExecutor();
        super.onAttachedToWindow();
        // RecyclerView 本不该有裸子 View。脚本用子标签/&lt;include&gt; 给了 item、又没显式
        // dataSource/itemTemplate 时，直接接管为 children 模式，避免 adapter item 数是 0。
        adoptChildren();
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            main.post(runnable);
        }
    }

    private Object itemAt(int position) {
        if (data == null || position < 0 || position >= data.length()) return null;
        return data.opt(position);
    }

    private String itemJsonAt(int position) {
        Object item = itemAt(position);
        return item == null || item == JSONObject.NULL ? "{}" : String.valueOf(item);
    }

    private void bindItem(NodeViewHolder holder, int position) {
        if (childrenMode) {
            bindChildItem(holder, position);
            return;
        }
        JSONObject item = data.optJSONObject(position);
        if (item == null) {
            Object raw = data.opt(position);
            if (raw instanceof JSONObject) item = (JSONObject) raw;
        }
        JSONObject safeItem = item == null ? new JSONObject() : item;
        String rendered = itemTemplate == null ? "" : bindTemplate(itemTemplate, safeItem);
        holder.container.removeAllViews();
        if (!rendered.trim().isEmpty()) {
            try {
                View child = NodeUiHost.inflateItemTemplate(getContext(), rendered, holder.container);
                if (child != null) {
                    // inflate(xml, parent, false) already creates the correct LayoutParams,
                    // including the template's margins. Do not discard them here.
                    holder.container.addView(child);
                }
            } catch (Throwable error) {
                Log.w(TAG, "item 模板 inflate 失败：" + rendered, error);
                View fallback = new View(getContext());
                fallback.setBackgroundColor(Color.TRANSPARENT);
                holder.container.addView(fallback, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
        notifyItemCreated(holder);
    }

    /** children 模式：第 position 个 item 就是第 position 个原始子 View（含 include 展开的页面）。 */
    private void bindChildItem(NodeViewHolder holder, int position) {
        holder.container.removeAllViews();
        View item = position >= 0 && position < childItems.size() ? childItems.get(position) : null;
        if (item == null) {
            return;
        }
        if (item.getParent() instanceof ViewGroup) {
            ((ViewGroup) item.getParent()).removeView(item);
        }
        ViewGroup.LayoutParams params = item.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        holder.container.addView(item, params);
        notifyItemCreated(holder);
    }

    /** item 绑定完成后的 item_created 回调（bindVersion 丢弃已回收的旧项）。 */
    private void notifyItemCreated(NodeViewHolder holder) {
        int version = ++holder.bindVersion;
        ItemCreatedListener listener = itemCreatedListener;
        if (listener != null && !destroyed.get()) {
            final NodeUiItemHolder itemHolder = new NodeUiItemHolder(this, holder);
            try {
                itemCallbacks.execute(() -> {
                    if (destroyed.get() || holder.bindVersion != version) return;
                    try {
                        listener.onItemCreated(holder.container, itemHolder);
                    } catch (Throwable error) {
                        Log.w(TAG, "item_created 回调失败", error);
                    }
                });
            } catch (Throwable ignored) {
                // 视图已 detached / executor 已停：丢弃迟到回调。
            }
        }
    }

    /** 只支持 this.xxx 路径与 this.xxx ? A : B；未知表达式替换为空串。 */
    static String bindTemplate(String template, JSONObject item) {
        Matcher matcher = DYNAMIC.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = evaluate(matcher.group(1).trim(), item);
            matcher.appendReplacement(output, Matcher.quoteReplacement(xmlEscape(replacement)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String evaluate(String expression, JSONObject item) {
        if (expression == null || !expression.startsWith("this.")) {
            return "";
        }
        String rest = expression.substring(5).trim();
        int question = rest.indexOf('?');
        if (question > 0) {
            int colon = rest.indexOf(':', question + 1);
            if (colon > question) {
                String field = rest.substring(0, question).trim();
                boolean condition = truthy(getPath(item, field));
                String branch = condition
                        ? rest.substring(question + 1, colon).trim()
                        : rest.substring(colon + 1).trim();
                return unquote(branch);
            }
        }
        Object value = getPath(item, rest);
        if (value == null || value == JSONObject.NULL) return "";
        return String.valueOf(value);
    }

    private static Object getPath(JSONObject item, String path) {
        if (item == null || path == null || path.isEmpty()) return null;
        Object current = item;
        for (String part : path.split("\\.")) {
            if (!(current instanceof JSONObject)) return null;
            current = ((JSONObject) current).opt(part);
        }
        return current;
    }

    private static boolean truthy(Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        return true;
    }

    private static String unquote(String value) {
        if (value == null) return "";
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '\'' || first == '"') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static JSONArray toDataArray(Object source) {
        if (source == null) return new JSONArray();
        if (source instanceof JSONArray) return (JSONArray) source;
        if (source instanceof String) {
            try {
                return new JSONArray((String) source);
            } catch (Throwable ignored) {
                return new JSONArray();
            }
        }
        JSONArray result = new JSONArray();
        if (source instanceof Object[]) {
            for (Object value : (Object[]) source) result.put(toJsonValue(value));
        } else if (source instanceof Collection) {
            for (Object value : (Collection<?>) source) result.put(toJsonValue(value));
        } else if (source instanceof JSONObject) {
            result.put(source);
        } else {
            result.put(toJsonValue(source));
        }
        return result;
    }

    private static Object toJsonValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            try {
                return new JSONObject((Map<?, ?>) value);
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
        if (value instanceof Object[]) return toDataArray(value);
        return value;
    }

    private final class NodeViewHolder extends ViewHolder {
        final FrameLayout container;
        int bindVersion;

        NodeViewHolder(FrameLayout itemView) {
            super(itemView);
            this.container = itemView;
        }
    }

    private final class NodeAdapter extends Adapter<NodeViewHolder> {

        @NonNull
        @Override
        public NodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new NodeViewHolder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull NodeViewHolder holder, int position) {
            bindItem(holder, position);
        }

        @Override
        public int getItemCount() {
            return childrenMode ? childItems.size() : (data == null ? 0 : data.length());
        }
    }
}
