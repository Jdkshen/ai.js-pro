package com.jdkshen.aijspro.engine;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.stardust.autojs.core.ui.inflater.ProjectResourceBundle;
import com.stardust.autojs.engine.node24.NodeUiHost;
import com.stardust.autojs.engine.node24.NodeUiProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/**
 * task-76：Node UI 的主进程真实 Activity（替代 overlay 的第一阶段宿主）。
 *
 * <p>由主进程 NodeUiBridgeService 按 NodeUiProtocol.ACTIVITY_CLASS 启动，extras 使用
 * NodeUiProtocol.K_*；layout 用 engine 的 ProjectResourceBundle + DynamicLayoutInflater inflate，
 * Activity 自带标题栏/系统返回键/生命周期，不再需要 overlay 关闭按钮。
 *
 * <p>关闭通知：onDestroy 调 CloseListener（由 NodeUiBridgeService 设置），service 再经
 * :node24 NodeUiActivityClient 回传 closed，让 keep-alive 结束。
 */
public class NodeUiActivity extends AppCompatActivity {

    private static final String TAG = "NodeUiActivity";

    /** 主进程 service 注册的关闭回调（onDestroy 时回调一次）。 */
    public interface CloseListener {
        void onNodeUiActivityClosed(String sessionId);
    }

    private static volatile CloseListener sCloseListener;
    private static WeakReference<NodeUiActivity> sCurrent;

    public static void setCloseListener(CloseListener listener) {
        sCloseListener = listener;
    }

    public static NodeUiActivity getCurrent() {
        return sCurrent == null ? null : sCurrent.get();
    }

    private String mSessionId = "";
    private String mProjectDir = "";
    /** 工程声明的标题（NodeUiHost 从 app:title/android:title 提取后经 intent 传入）。 */
    private String mTitle = "";
    private boolean mCloseNotified;
    /** 动态 inflater 路径才允许做布局兜底；framework 原生路径完全尊重工程布局。 */
    private boolean mDynamicLayout;
    /** task-76：Activity 模式下 setPager/setAdapter 更新需要的资源/主题/内容视图。 */
    private ProjectResourceBundle mBundle;
    private Context mThemedContext;
    private View mContentView;
    private JSONObject mViews;
    /** Activity.layoutXml 入口：先放占位 content，等 setContentViewXml 更新真正字符串布局。 */
    private boolean mInlineLayout;
    /** 主进程 click 事件队列，NodeUiHost.pollEvents 通过 drainViewEvents 拉走。 */
    private final java.util.ArrayDeque<JSONObject> mViewEvents = new java.util.ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mSessionId = intent == null ? "" : intent.getStringExtra(NodeUiProtocol.K_SESSION);
        mProjectDir = intent == null ? "" : intent.getStringExtra(NodeUiProtocol.K_PROJECT);
        String layoutName = intent == null ? "" : intent.getStringExtra(NodeUiProtocol.K_LAYOUT_NAME);
        int layoutId = intent == null ? 0 : intent.getIntExtra(NodeUiProtocol.K_LAYOUT_ID, 0);
        String title = intent == null ? "" : intent.getStringExtra(NodeUiProtocol.K_TITLE);
        mTitle = title == null ? "" : title;
        if (!mTitle.isEmpty()) {
            setTitle(mTitle);
        }
        sCurrent = new WeakReference<>(this);
        try {
            if (NodeUiHost.INLINE_LAYOUT_TOKEN.equals(layoutName)) {
                // Activity.layoutXml：不要求 project.json/res/build。先占位，宿主随后的
                // MSG_UPDATE(setContentViewXml) 会 inflate 字符串布局并替换 content。
                mInlineLayout = true;
                mDynamicLayout = true;
                mThemedContext = NodeUiHost.wrapThemedContext(this, null);
                FrameLayout placeholder = new FrameLayout(this);
                setContentView(placeholder);
                mContentView = placeholder;
                mViews = new JSONObject();
                Log.i(TAG, "NodeUiActivity: inline layoutXml placeholder session=" + mSessionId);
                return;
            }
            ProjectResourceBundle bundle = ProjectResourceBundle.load(this, new File(mProjectDir));
            if (bundle.getError() != null) {
                throw new IllegalStateException(bundle.getError());
            }
            mBundle = bundle;
            String layoutPath = layoutId != 0 ? bundle.layoutFileById(layoutId) : null;
            if (layoutPath == null && layoutName != null && !layoutName.isEmpty()) {
                layoutPath = bundle.layoutFile(layoutName);
            }
            if (layoutPath == null) {
                throw new IllegalStateException("找不到布局：layoutName=" + layoutName + ", layoutId=" + layoutId);
            }
            String xml = readTextFile(new File(layoutPath));
            if (xml == null || xml.isEmpty()) {
                throw new IllegalStateException("读取布局失败：" + layoutPath);
            }
            Context themed = NodeUiHost.wrapThemedContext(this, bundle);
            View framework = NodeUiRenderFix.inflateFramework(this, bundle, themed, layoutName, layoutId);
            mDynamicLayout = framework == null;
            View content = framework;
            if (content == null) {
                content = bundle.newLayoutInflater(themed).inflate(xml);
                Log.i(TAG, "C-INFLATE: dynamic fallback layout=" + layoutName);
            }
            if (content == null) {
                throw new IllegalStateException("inflate 结果为空：" + layoutPath);
            }
            final View contentView = content;
            // framework 原生 inflate 成功时不要再套动态标题栏/绘制修补；布局声明的 Toolbar 就是标题栏。
            View shown = mDynamicLayout
                    ? NodeUiRenderFix.wrapWithTitleBar(this, contentView, mTitle)
                    : contentView;
            setContentView(shown);
            mThemedContext = themed;
            mContentView = contentView;
            JSONObject views = NodeUiHost.buildViewsForScale(contentView, bundle);
            mViews = views;
            NodeUiHost.applyToolbarTitleFor(contentView, xml, views);
            NodeUiHost.wireNavigationFor(contentView, bundle, views);
            // measure/layout 完成后再判断 AppBar 是否被滚动内容盖住。
            scheduleFixAppBar(contentView);
            Log.i(TAG, "NodeUiActivity: show layout=" + (layoutName == null ? "" : layoutName)
                    + " project=" + mProjectDir + " session=" + mSessionId
                    + " dynamic=" + mDynamicLayout);
        } catch (Throwable error) {
            Log.e(TAG, "NodeUiActivity: inflate 失败 project=" + mProjectDir
                    + " layout=" + layoutName, error);
            try {
                android.widget.Toast.makeText(this, "Node UI 界面加载失败：" + error.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // toast 失败不影响 finish
            }
            finish();
        }
    }

    /**
     * 布局完成后再修正。旧 content 的延迟回调不能作用到别的实例/新内容上，
     * 所以执行前确认它仍是当前 content 且 Activity 还活着。
     */
    private void scheduleFixAppBar(final View contentView) {
        if (contentView == null) {
            return;
        }
        contentView.post(() -> {
            if (isCurrentContent(contentView)) {
                fixAppBar(contentView);
            }
        });
        contentView.postDelayed(() -> {
            if (isCurrentContent(contentView)) {
                fixAppBar(contentView);
            }
        }, 500L);
    }

    private boolean isCurrentContent(View contentView) {
        return contentView != null && contentView == mContentView
                && !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // task-97：onResume 再校正一次（measure/layout 完成后 toolbar 才能可靠判断是否被内容盖住）。
        final View content = mContentView;
        if (content != null) {
            content.post(() -> {
                if (isCurrentContent(content)) {
                    fixAppBar(content);
                }
            });
        }
    }

    /**
     * 只做两件事：
     * 1. 工程 Toolbar 没有标题时，用 intent 带回的工程标题兜底；
     * 2. 动态 inflater 路径下，若 CoordinatorLayout 的滚动内容因为 layout_behavior 丢失而
     *    盖住 AppBar，则按布局后的实际重叠量增量下移它的 topMargin。
     *
     * <p>不再强制 AppBar 高度/标题、不再移除 behavior/scrollFlags，也不改 fitsSystemWindows；
     * framework 原生路径（mDynamicLayout=false）不在这里改任何布局。
     */
    private void fixAppBar(View root) {
        try {
            View content = mContentView;
            if (content == null || root != content || isFinishing() || isDestroyed()) {
                return;
            }
            View appbar = findViewByClass(content, "com.google.android.material.appbar.AppBarLayout");
            Toolbar toolbar = appbar == null ? null : findToolbar(appbar);
            if (toolbar == null) {
                toolbar = findToolbar(content);
            }
            if (toolbar == null && getWindow() != null) {
                toolbar = findToolbar(getWindow().getDecorView());
            }
            if (toolbar != null) {
                CharSequence text = toolbar.getTitle();
                if ((text == null || text.length() == 0) && !mTitle.isEmpty()) {
                    toolbar.setTitle(mTitle);
                    Log.i(TAG, "NodeUiActivity: C-TITLE fallback title=" + mTitle);
                }
            }
            if (mDynamicLayout && appbar != null) {
                moveScrollingContentBelowAppBar(appbar);
            }

            // task-99 诊断信息保留；只读，不再据此改 behavior/scrollFlags。
            View pager = findViewByClass(content, "com.stardust.autojs.core.ui.widget.JsViewPager");
            if (pager == null) {
                pager = findViewByClass(content, "androidx.viewpager.widget.ViewPager");
            }
            String pagerBehavior = "-";
            int pagerTop = -1;
            int pagerTopMargin = -1;
            if (pager != null) {
                pagerTop = pager.getTop();
                if (pager.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    pagerTopMargin = ((ViewGroup.MarginLayoutParams) pager.getLayoutParams()).topMargin;
                }
                if (pager.getLayoutParams()
                        instanceof CoordinatorLayout.LayoutParams) {
                    Object behavior = ((CoordinatorLayout.LayoutParams)
                            pager.getLayoutParams()).getBehavior();
                    pagerBehavior = behavior == null ? "null" : behavior.getClass().getName();
                }
            }
            Log.i(TAG, "NodeUiActivity: appbar top=" + (appbar == null ? -1 : appbar.getTop())
                    + " h=" + (appbar == null ? -1 : appbar.getHeight())
                    + " translateY=" + (appbar == null ? -1f : appbar.getTranslationY())
                    + " scrollFlags=" + scrollFlagsOf(toolbar)
                    + " pagerTop=" + pagerTop + " pagerTopMargin=" + pagerTopMargin
                    + " pagerBehavior=" + pagerBehavior + " dynamic=" + mDynamicLayout
                    + " session=" + mSessionId);
            Log.i(TAG, "NodeUiActivity: appbar h=" + (appbar == null ? -1 : appbar.getHeight())
                    + " toolbar h=" + (toolbar == null ? -1 : toolbar.getHeight())
                    + " title=" + (toolbar == null ? "" : toolbar.getTitle())
                    + " toolbarFound=" + (toolbar != null)
                    + " appbarFound=" + (appbar != null) + " session=" + mSessionId);
        } catch (Throwable error) {
            Log.w(TAG, "NodeUiActivity: fixAppBar failed", error);
        }
    }

    /**
     * 动态 inflater 会丢掉 CoordinatorLayout 子 View 的 app:layout_behavior，示例里的
     * JsViewPager 因此也在 top=0，整块盖住 AppBar。只在滚动内容没有 behavior 且布局后
     * 确实与 AppBar 重叠时，按实际重叠量增量下移 topMargin：child.getTop 已含 padding/inset
     * 和现有 margin，不把 appbar.getBottom 直接当 topMargin，避免重复推下。
     */
    private void moveScrollingContentBelowAppBar(View appbar) {
        if (appbar.getVisibility() != View.VISIBLE || appbar.getHeight() <= 0
                || !appbar.isLaidOut()) {
            return;
        }
        ViewGroup parent = appbar.getParent() instanceof ViewGroup
                ? (ViewGroup) appbar.getParent() : null;
        if (!(parent instanceof CoordinatorLayout)) {
            return;
        }
        int appbarBottom = appbar.getBottom();
        if (appbarBottom <= 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == appbar || !isScrollingContent(child) || !child.isLaidOut()) {
                continue;
            }
            int overlap = appbarBottom - child.getTop();
            if (overlap <= 0) {
                continue;
            }
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }
            if (params instanceof CoordinatorLayout.LayoutParams
                    && ((CoordinatorLayout.LayoutParams) params).getBehavior() != null) {
                continue;
            }
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            // 等上一次布局请求消化后再按 getTop 差值增量调整；否则同一帧里
            // setLayoutParams 尚未 layout，再叠加会把内容重复推下。
            if (child.isLayoutRequested()) {
                continue;
            }
            margins.topMargin += overlap;
            child.setLayoutParams(margins);
            Log.i(TAG, "NodeUiActivity: C-TOPBAR move content below appbar overlap="
                    + overlap + " topMargin=" + margins.topMargin
                    + " view=" + child.getClass().getName());
        }
    }

    private static boolean isScrollingContent(View view) {
        return view instanceof ViewPager
                || view instanceof RecyclerView
                || view instanceof NestedScrollView
                || view instanceof android.widget.ScrollView
                || view instanceof android.widget.ListView;
    }

    private static Toolbar findToolbar(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof Toolbar) {
            return (Toolbar) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Toolbar found = findToolbar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int scrollFlagsOf(View view) {
        try {
            if (view != null && view.getLayoutParams()
                    instanceof com.google.android.material.appbar.AppBarLayout.LayoutParams) {
                return ((com.google.android.material.appbar.AppBarLayout.LayoutParams)
                        view.getLayoutParams()).getScrollFlags();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static View findViewByClass(View view, String className) {
        if (view == null) return null;
        if (view.getClass().getName().equals(className)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByClass(group.getChildAt(i), className);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** task-135 Phase2：宿主 View 句柄命令（主进程执行；返回 JSON 字符串）。 */
    private final java.util.Map<String, View> mViewHandles = new java.util.HashMap<>();
    private int mViewHandleSeq = 1;

    public String handleView(String requestJson) {
        try {
            JSONObject request = new JSONObject(requestJson == null ? "{}" : requestJson);
            String op = request.optString("op", "");
            if (mContentView == null || mThemedContext == null) {
                return new JSONObject().put("ok", false).put("error", "NodeUiActivity 布局尚未完成").toString();
            }
            switch (op) {
                case "setContentView":
                case "inflate":
                case "layout": {
                    if (mBundle == null) {
                        return new JSONObject().put("ok", false)
                                .put("error", "layoutXml 模式暂不支持按 res 名 inflate：" + request.optString("name", ""))
                                .toString();
                    }
                    String name = request.optString("name", "");
                    int layoutId = name.isEmpty() ? 0 : mBundle.layoutId(name);
                    String file = layoutId == 0 ? mBundle.layoutFile(name) : mBundle.layoutFileById(layoutId);
                    if (file == null) return new JSONObject().put("ok", false)
                            .put("error", "找不到布局：" + name).toString();
                    String xml = readTextFile(new File(file));
                    View view = mBundle.newLayoutInflater(mThemedContext).inflate(xml);
                    if ("setContentView".equals(op)) {
                        setContentView(view);
                        mContentView = view;
                    }
                    String handle = "view-" + (mViewHandleSeq++);
                    mViewHandles.put(handle, view);
                    return new JSONObject().put("ok", true).put("handleId", handle)
                            .put("name", name).put("handleCount", mViewHandles.size()).toString();
                }
                case "findViewById": {
                    int id = resolveViewId(request);
                    View view = id == 0 ? null : mContentView.findViewById(id);
                    if (view == null) {
                        return new JSONObject().put("ok", false)
                            .put("error", "findViewById 未命中：" + request.optString("name", "")
                                    + " id=" + id).toString();
                    }
                    String handle = "view-" + (mViewHandleSeq++);
                    mViewHandles.put(handle, view);
                    return new JSONObject().put("ok", true).put("handleId", handle)
                            .put("viewId", id).toString();
                }
                case "bindClick": {
                    int id = resolveViewId(request);
                    View view = id == 0 ? null : mContentView.findViewById(id);
                    if (view == null) {
                        return new JSONObject().put("ok", false)
                                .put("error", "bindClick 未命中：" + request.optString("name", "")
                                        + " id=" + id).toString();
                    }
                    final int clickId = id;
                    view.setOnClickListener(v -> {
                        try {
                            enqueueViewEvent(new JSONObject()
                                    .put("type", "click").put("viewId", clickId));
                        } catch (Throwable error) {
                            Log.w(TAG, "click 事件入队失败", error);
                        }
                    });
                    view.setClickable(true);
                    return new JSONObject().put("ok", true).put("viewId", id).toString();
                }
                case "viewCommand": {
                    View view = mViewHandles.get(request.optString("handleId", ""));
                    if (view == null) return new JSONObject().put("ok", false).put("error", "无效的 View handle").toString();
                    String action = request.optString("action", "");
                    Object value = request.opt("value");
                    switch (action) {
                        case "setText":
                            if (!(view instanceof TextView)) return viewTypeError("setText", view);
                            ((TextView) view).setText(String.valueOf(value));
                            break;
                        case "getText":
                            if (!(view instanceof TextView)) return viewTypeError("getText", view);
                            return new JSONObject().put("ok", true)
                                    .put("value", ((TextView) view).getText().toString()).toString();
                        case "setVisibility":
                            view.setVisibility(value instanceof Number
                                    ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
                            break;
                        case "getVisibility":
                            return new JSONObject().put("ok", true).put("value", view.getVisibility()).toString();
                        case "setEnabled":
                            view.setEnabled(Boolean.parseBoolean(String.valueOf(value)));
                            break;
                        case "setChecked":
                            if (!(view instanceof CompoundButton)) return viewTypeError("setChecked", view);
                            ((CompoundButton) view).setChecked(Boolean.parseBoolean(String.valueOf(value)));
                            break;
                        case "isChecked":
                            if (!(view instanceof CompoundButton)) return viewTypeError("isChecked", view);
                            return new JSONObject().put("ok", true)
                                    .put("value", ((CompoundButton) view).isChecked()).toString();
                        case "toggle":
                            if (!(view instanceof CompoundButton)) return viewTypeError("toggle", view);
                            ((CompoundButton) view).toggle();
                            break;
                        case "click":
                            return new JSONObject().put("ok", true)
                                    .put("value", view.performClick()).toString();
                        default: return new JSONObject().put("ok", false)
                                .put("error", "viewCommand 不支持：" + action).toString();
                    }
                    return "{\"ok\":true}";
                }
                default:
                    return new JSONObject().put("ok", false)
                            .put("error", "handleView 不支持：" + op).toString();
            }
        } catch (Throwable error) {
            Log.w(TAG, "handleView failed", error);
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(error.getMessage())) + "\"}";
        }
    }

    /** id / name → 真实 viewId：inline 用 mViews，res 布局用资源表。 */
    private int resolveViewId(JSONObject request) {
        int id = request.optInt("id", 0);
        String name = request.optString("name", "");
        if (id == 0 && !name.isEmpty() && mViews != null) {
            id = mViews.optInt(name, 0);
        }
        if (id == 0 && !name.isEmpty() && mBundle != null) {
            id = mBundle.idOf("id", name);
        }
        return id;
    }

    private static String viewTypeError(String action, View view) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", action + " 不支持该控件：" + view.getClass().getName()).toString();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"" + action + " 控件类型不匹配\"}";
        }
    }

    private void enqueueViewEvent(JSONObject event) {
        if (event == null) {
            return;
        }
        synchronized (mViewEvents) {
            mViewEvents.addLast(event);
            while (mViewEvents.size() > 64) {
                mViewEvents.removeFirst();
            }
        }
    }

    private String drainViewEventsJson() {
        JSONArray events = new JSONArray();
        synchronized (mViewEvents) {
            while (!mViewEvents.isEmpty()) {
                events.put(mViewEvents.removeFirst());
            }
        }
        try {
            return new JSONObject().put("ok", true).put("events", events).toString();
        } catch (Throwable error) {
            return "{\"ok\":false,\"error\":\"读取 View 事件失败\"}";
        }
    }

    /**
     * task-76：主进程 NodeUiBridgeService 反射调用（实例方法，参数 JSONObject，返回 JSON 字符串）。
     * 处理 Activity 模式下的 setPager/setAdapter（页面/卡片在 Activity 自己的 content 上）。
     */
    public String handleUpdate(JSONObject request) {
        if (mContentView == null || mThemedContext == null) {
            return "{\"ok\":false,\"error\":\"NodeUiActivity 尚未完成布局 inflate\"}";
        }
        String op = request == null ? "" : request.optString("op", "");
        try {
            if ("drainViewEvents".equals(op)) {
                return drainViewEventsJson();
            }
            if ("setContentViewXml".equals(op)) {
                return applyInlineContentView(request);
            }
            if ("getViews".equals(op)) {
                return new JSONObject().put("ok", true)
                        .put("mode", mInlineLayout ? "inline" : "activity")
                        .put("views", mViews == null ? new JSONObject() : mViews)
                        .toString();
            }
            if (mBundle == null) {
                return new JSONObject().put("ok", false)
                        .put("error", "layoutXml 模式暂不支持该更新：" + op).toString();
            }
            JSONObject result = NodeUiHost.applyUpdateForActivity(
                    mContentView, mThemedContext, mBundle, request);
            Log.i(TAG, "NodeUiActivity: handleUpdate op="
                    + (request == null ? "" : request.optString("op", ""))
                    + " result=" + result);
            // task-97：setPager/setAdapter 之后（页面/卡片 inflate 完）再校正一次 AppBar。
            final View content = mContentView;
            if (content != null) {
                content.post(() -> {
                    if (isCurrentContent(content)) {
                        fixAppBar(content);
                    }
                });
            }
            return result == null ? "{\"ok\":false}" : result.toString();
        } catch (Throwable error) {
            Log.w(TAG, "NodeUiActivity: handleUpdate 失败", error);
            return "{\"ok\":false,\"error\":\"handleUpdate 失败：" + escape(String.valueOf(error.getMessage())) + "\"}";
        }
    }

    /**
     * Activity.layoutXml 的真实 inflate：不读 project.json/res/build，直接 inflate 宿主传来的
     * XML 字符串，并把 id 名→viewId 映射回 :node24 给 ui.js binding。
     */
    private String applyInlineContentView(JSONObject request) {
        try {
            if (!mInlineLayout) {
                return new JSONObject().put("ok", false)
                        .put("error", "当前 Activity 不是 layoutXml 模式").toString();
            }
            String xml = request == null ? "" : request.optString("xml", "");
            if (xml.trim().isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "layoutXml 为空").toString();
            }
            View content = NodeUiHost.inflateInlineLayout(mThemedContext, xml);
            if (content == null) {
                return new JSONObject().put("ok", false)
                        .put("error", "layoutXml inflate 结果为空").toString();
            }
            setContentView(content);
            mContentView = content;
            mDynamicLayout = true;
            mViewHandles.clear();
            mViews = NodeUiHost.buildInlineViews(content, xml);
            NodeUiHost.applyToolbarTitleFor(content, xml, mViews);
            scheduleFixAppBar(content);
            Log.i(TAG, "NodeUiActivity: C-INLINE layoutXml inflated session=" + mSessionId
                    + " views=" + mViews);
            return new JSONObject().put("ok", true).put("mode", "inline")
                    .put("views", mViews).toString();
        } catch (Throwable error) {
            Log.w(TAG, "NodeUiActivity: layoutXml inflate 失败", error);
            try {
                return new JSONObject().put("ok", false)
                        .put("error", "layoutXml inflate 失败：" + String.valueOf(error.getMessage()))
                        .toString();
            } catch (Throwable ignored) {
                return "{\"ok\":false,\"error\":\"layoutXml inflate 失败\"}";
            }
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected void onDestroy() {
        if (sCurrent != null && sCurrent.get() == this) {
            sCurrent.clear();
        }
        notifyClosed();
        super.onDestroy();
    }

    private void notifyClosed() {
        if (mCloseNotified) {
            return;
        }
        mCloseNotified = true;
        CloseListener listener = sCloseListener;
        if (listener != null) {
            try {
                listener.onNodeUiActivityClosed(mSessionId);
            } catch (Throwable error) {
                Log.w(TAG, "onNodeUiActivityClosed 回调失败", error);
            }
        }
        Log.i(TAG, "NodeUiActivity: closed session=" + mSessionId);
    }

    private static String readTextFile(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable error) {
            Log.w(TAG, "读取文件失败: " + file, error);
            return null;
        }
    }
}
