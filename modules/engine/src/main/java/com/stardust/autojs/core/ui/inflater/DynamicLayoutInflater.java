package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;

import com.google.android.material.appbar.AppBarLayout;
import com.stardust.autojs.core.ui.inflater.inflaters.AppBarInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.BaseViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.DatePickerInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.FrameLayoutInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ImageViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.LinearLayoutInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ProgressBarInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.RadioGroupInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.SpinnerInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.TabLayoutInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.TextViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.TimePickerInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ToolbarInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ViewGroupInflater;
import com.stardust.autojs.core.ui.inflater.util.Res;
import com.stardust.autojs.core.ui.widget.JsSpinner;
import com.stardust.autojs.core.ui.widget.JsTabLayout;
import com.stardust.autojs.core.ui.widget.JsToolbar;
import com.stardust.autojs.core.ui.xml.XmlConverter;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.File;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;


public class DynamicLayoutInflater {

    public static final int FLAG_DEFAULT = 0;
    public static final int FLAG_IGNORES_DYNAMIC_ATTRS = 1;
    public static final int FLAG_JUST_DYNAMIC_ATTRS = 2;

    private static final String LOG_TAG = "DynamicLayoutInflater";

    private Map<String, ViewInflater<?>> mViewAttrSetters = new HashMap<>();
    private Map<String, ViewCreator<?>> mViewCreators = new HashMap<>();
    private Context mContext;
    private ResourceParser mResourceParser;
    @NonNull
    private LayoutInflaterDelegate mLayoutInflaterDelegate = LayoutInflaterDelegate.NO_OP;
    private int mInflateFlags;
    /**
     * 工程动态 dex（Pro 项目 {@code .codecache/*.dex}）的 ClassLoader。
     * 为 null 时保持原来的 {@code Class.forName(name)} 行为（宿主 ClassLoader）。
     */
    @Nullable
    private ClassLoader mClassLoader;

    public DynamicLayoutInflater(ResourceParser resourceParser) {
        mResourceParser = resourceParser;
        registerViewAttrSetters();
    }

    @SuppressWarnings("IncompleteCopyConstructor")
    public DynamicLayoutInflater(DynamicLayoutInflater inflater) {
        this.mResourceParser = inflater.mResourceParser;
        this.mContext = inflater.mContext;
        this.mViewAttrSetters = new HashMap<>(inflater.mViewAttrSetters);
        this.mViewCreators = new HashMap<>(inflater.mViewCreators);
        this.mClassLoader = inflater.mClassLoader;
    }

    public int getInflateFlags() {
        return mInflateFlags;
    }

    public void setInflateFlags(int inflateFlags) {
        mInflateFlags = inflateFlags;
    }

    public ResourceParser getResourceParser() {
        return mResourceParser;
    }

    public Context getContext() {
        return mContext;
    }

    public void setContext(Context context) {
        mContext = context;
    }

    /**
     * 设置工程动态 dex 的 ClassLoader：布局里出现工程自己编译出来的 View 类时，用它在
     * {@code .codecache/*.dex} 里查找（见 {@link ProjectCodeCache}）；找不到再回退宿主 ClassLoader。
     */
    public void setClassLoader(@Nullable ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    @Nullable
    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    @NonNull
    public LayoutInflaterDelegate getLayoutInflaterDelegate() {
        return mLayoutInflaterDelegate;
    }

    public void setLayoutInflaterDelegate(@Nullable LayoutInflaterDelegate layoutInflaterDelegate) {
        if (layoutInflaterDelegate == null)
            layoutInflaterDelegate = LayoutInflaterDelegate.NO_OP;
        mLayoutInflaterDelegate = layoutInflaterDelegate;
    }

    protected void registerViewAttrSetters() {
        registerViewAttrSetter(TextView.class.getName(), new TextViewInflater<>(mResourceParser));
        registerViewAttrSetter(EditText.class.getName(), new TextViewInflater<>(mResourceParser));
        registerViewAttrSetter(ImageView.class.getName(), new ImageViewInflater<>(mResourceParser));
        registerViewAttrSetter(LinearLayout.class.getName(), new LinearLayoutInflater<>(mResourceParser));
        registerViewAttrSetter(FrameLayout.class.getName(), new FrameLayoutInflater<>(mResourceParser));
        registerViewAttrSetter(View.class.getName(), new BaseViewInflater<>(mResourceParser));
        registerViewAttrSetter(JsToolbar.class.getName(), new ToolbarInflater<>(mResourceParser));
        registerViewAttrSetter(DatePicker.class.getName(), new DatePickerInflater(mResourceParser));
        registerViewAttrSetter(RadioGroup.class.getName(), new RadioGroupInflater<>(mResourceParser));
        registerViewAttrSetter(ProgressBar.class.getName(), new ProgressBarInflater<>(mResourceParser));
        registerViewAttrSetter(JsSpinner.class.getName(), new SpinnerInflater(mResourceParser));
        registerViewAttrSetter(TimePicker.class.getName(), new TimePickerInflater(mResourceParser));
        registerViewAttrSetter(AppBarLayout.class.getName(), new AppBarInflater<>(mResourceParser));
        registerViewAttrSetter(JsTabLayout.class.getName(), new TabLayoutInflater<>(mResourceParser));
    }

    public void registerViewAttrSetter(String fullName, ViewInflater<?> inflater) {
        mViewAttrSetters.put(fullName, inflater);
        ViewCreator<?> creator = inflater.getCreator();
        if (creator != null) {
            mViewCreators.put(fullName, creator);
        }
    }

    public View inflate(String xml) {
        return inflate(xml, null);
    }

    public View inflate(String xml, @Nullable ViewGroup parent) {
        return inflate(xml, parent, parent != null);
    }

    public View inflate(String xml, @Nullable ViewGroup parent, boolean attachToParent) {
        InflateContext context = newInflateContext();
        return inflate(context, xml, parent, attachToParent);
    }

    public View inflate(InflateContext context, String xml, @Nullable ViewGroup parent, boolean attachToParent) {
        View view = mLayoutInflaterDelegate.beforeInflation(context, xml, parent);
        if (view != null)
            return view;
        xml = convertXml(context, xml);
        return mLayoutInflaterDelegate.afterInflation(context, doInflation(context, xml, parent, attachToParent), xml, parent);
    }

    public InflateContext newInflateContext(){
        return new InflateContext();
    }


    protected View doInflation(InflateContext context, String xml, @Nullable ViewGroup parent, boolean attachToParent) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(new ByteArrayInputStream(xml.getBytes()));
            return inflate(context, document.getDocumentElement(), parent, attachToParent);
        } catch (Exception e) {
            throw new InflateException(e);
        }
    }

    protected String convertXml(InflateContext context, String xml) {
        String str = mLayoutInflaterDelegate.beforeConvertXml(context, xml);
        if (str != null)
            return str;
        try {
            return mLayoutInflaterDelegate.afterConvertXml(context, XmlConverter.convertToAndroidLayout(xml));
        } catch (Exception e) {
            throw new InflateException(e);
        }
    }

    public View inflate(InflateContext context, Node node, @Nullable ViewGroup parent, boolean attachToParent) {
        if ("include".equals(node.getNodeName())) {
            return inflateInclude(context, node, parent, attachToParent);
        }
        View view = doInflation(context, node, parent, attachToParent);
        if (view instanceof ShouldCallOnFinishInflate) {
            ((ShouldCallOnFinishInflate) view).onFinishDynamicInflate();
        }
        return view;
    }

    protected View doInflation(InflateContext context, Node node, @Nullable ViewGroup parent, boolean attachToParent) {
        View view = mLayoutInflaterDelegate.beforeInflateView(context, node, parent, attachToParent);
        if (view != null)
            return view;
        HashMap<String, String> attrs = getAttributesMap(node);
        view = doCreateView(context, node, node.getNodeName(), parent, attrs);
        // dev-992：parent 类型正确的 LayoutParams 与 layout_gravity 等属性必须在 attach 之前就位
        // （DrawerLayout 的 NavigationView 在 onAttachedToWindow 里立刻用 LP.gravity 判 drawer）。
        if (parent != null) {
            prepareLayoutParamsForAttach(context, view, attrs, parent);
        }
        if (parent != null && attachToParent) {
            parent.addView(view);
        }
        ViewInflater<View> inflater = applyAttributes(context, view, attrs, parent);
        if (view instanceof ViewGroup && node.hasChildNodes()) {
            inflateChildren(context, inflater, node, (ViewGroup) view);
            if (inflater instanceof ViewGroupInflater) {
                applyPendingAttributesOfChildren(context, (ViewGroupInflater) inflater, (ViewGroup) view);
            }
        }
        return mLayoutInflaterDelegate.afterInflateView(context, view, node, parent, attachToParent);
    }

    /**
     * {@code <include layout="@layout/x"/>}：工程模式（androidResources）下按名字找到
     * {@code res/layout/x.xml} 再递归 inflate。
     *
     * <p>原生 res/ 布局（Pro 工程）几乎都会用 include（Node UI 示例的 activity_main.xml 就 include 了
     * page_main/page_console/page_settings）。以前没有这个分支，include 会被当成未知 View 类
     * （{@code Class.forName("android.widget.include")}）直接 inflate 失败。这里做最小语义：
     * 解析 layout 引用 → 读 xml → 递归 inflate → 把 include 节点上显式写的属性（layout_width/height、
     * margin 等）再应用到 included root 上。
     */
    protected View inflateInclude(InflateContext context, Node node, @Nullable ViewGroup parent, boolean attachToParent) {
        HashMap<String, String> attrs = getAttributesMap(node);
        String layoutRef = attrs.get("layout");
        if (layoutRef == null) {
            for (String key : attrs.keySet()) {
                if (key.endsWith(":layout")) {
                    layoutRef = attrs.get(key);
                    break;
                }
            }
        }
        if (layoutRef == null || layoutRef.isEmpty()) {
            throw new InflateException("include 缺少 layout 属性：" + node + "（需要工程模式 androidResources）");
        }
        String name = ProjectResources.nameOfReference(layoutRef);
        ProjectResources resources = mResourceParser.getProjectResources();
        File file = resources == null ? null
                : resources.fileOf(ProjectResources.TYPE_LAYOUT, ProjectResources.stripExtension(name));
        if (file == null || !file.isFile()) {
            throw new InflateException("include 找不到工程布局：" + layoutRef
                    + "（需要 project.json 声明 androidResources；当前资源表=" + resources + "）");
        }
        String xml;
        try {
            xml = readFileText(file);
        } catch (java.io.IOException error) {
            throw new InflateException(error);
        }
        // 先按「不 attach」inflate 出 included root，把 include 上显式写的 layout_* 预套到 root 的
        // LayoutParams 上，再挂到 parent —— 挂的瞬间会触发 onAttachedToWindow（DrawerLayout/
        // NavigationView 这类控件会读 LP，dev-992 的崩溃就是晚了这一步）。
        View view = inflate(context, xml, parent, false);
        if (view == null) {
            return null;
        }
        prepareLayoutParamsForAttach(context, view, attrs, parent);
        if (parent != null && attachToParent) {
            parent.addView(view);
        }
        if (!attrs.isEmpty()) {
            // include 上显式写的 layout_* 会覆盖被包含布局 root 自己的值（Android 原生语义）。
            applyAttributes(context, view, attrs, parent);
        }
        return mLayoutInflaterDelegate.afterInflateView(context, view, node, parent, attachToParent);
    }

    /**
     * dev-992：子 View / include 根 View 的 LayoutParams 必须在 addView（attach）之前按 parent 类型生成，
     * 并预套 layout_* 属性。
     *
     * <p>旧实现是 addView 之后才 applyAttributes；DrawerLayout 的 NavigationView 在
     * {@code onAttachedToWindow} 里立刻调 {@code DrawerLayout.isDrawerOpen(this)}，而该方法要求
     * LP 是 {@code DrawerLayout.LayoutParams} 且 {@code gravity} 带 START/LEFT 位，否则直接抛
     * {@code IllegalArgumentException: View ... is not a drawer}（dev-992 :node24 FATAL）。
     *
     * <p>这里用 {@code parent.generateDefaultLayoutParams()} 生成正确类型（DrawerLayout/
     * CoordinatorLayout/LinearLayout… 各自重写过），不依赖 AttributeSet；然后把 {@code layout_*}
     * 属性先套一遍（宽度/高度/gravity/margin/weight/RelativeLayout 规则）。attach 之后原来的
     * {@code applyAttributes} 仍会再套一遍（幂等），非 layout 属性语义不变。
     */
    protected void prepareLayoutParamsForAttach(InflateContext context, View view, HashMap<String, String> attrs, @Nullable ViewGroup parent) {
        if (parent == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        boolean generatedLayoutParams = false;
        if (layoutParams == null) {
            layoutParams = generateDefaultLayoutParams(parent);
            generatedLayoutParams = true;
        } else if (!checkLayoutParams(parent, layoutParams)) {
            layoutParams = generateLayoutParams(parent, layoutParams);
            generatedLayoutParams = true;
            if (layoutParams == null || !checkLayoutParams(parent, layoutParams)) {
                layoutParams = generateDefaultLayoutParams(parent);
            }
        }
        if (layoutParams == null) {
            layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // dev-1032：AppBarLayout 子 View 未声明 layout_scrollFlags 时，XML 构造器的缺省值是 0
        // （LayoutParams(Context, AttributeSet) 里 getInt(..., 0)）；但这里只能走程序式
        // generateDefaultLayoutParams()，其 (int, int) 构造器硬编码 SCROLL_FLAG_SCROLL(1)，
        // 导致未声明 flags 的 MaterialToolbar 跟着内容滚出屏幕。仅在本次新生成 LP 时还原 XML
        // 缺省值，不动已有 LP（include 根等可能已按自己的 XML 显式设过 flags）；显式
        // app:layout_scrollFlags 由下面的属性循环 / attach 后的 applyAttributes 覆盖。
        if (generatedLayoutParams && parent instanceof AppBarLayout
                && layoutParams instanceof AppBarLayout.LayoutParams) {
            ((AppBarLayout.LayoutParams) layoutParams).setScrollFlags(
                    AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL);
        }
        view.setLayoutParams(layoutParams);
        @SuppressWarnings("unchecked")
        ViewInflater<View> setter = (ViewInflater<View>) getViewInflater(view);
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            String key = entry.getKey();
            String local = key;
            String ns = null;
            int colon = key.indexOf(':');
            if (colon >= 0) {
                ns = key.substring(0, colon);
                local = key.substring(colon + 1);
            }
            if (!local.startsWith("layout_")) {
                continue;
            }
            if ("layout_gravity".equals(local)) {
                // dev-993：layout_gravity 直接写 LP.gravity，不经过 ViewInflater。
                // 原因：BaseViewInflater 的 layout_gravity 分支只认 parent instanceof LinearLayout/FrameLayout，
                // 其它 parent 走反射 setLayoutGravity；且 FrameLayoutInflater 那条路会 cast 成
                // FrameLayout.LayoutParams —— 对 DrawerLayout.LayoutParams 直接 ClassCastException（被吞）。
                // 另外 Gravities.parse 以前不支持 start/end，值本身就是 0（已一并修）。
                int gravity = parseLayoutGravity(entry.getValue());
                boolean written = writeLayoutGravity(layoutParams, gravity);
                Log.i(LOG_TAG, "预套 layout_gravity 命中: " + view.getClass().getName()
                        + " parent=" + parent.getClass().getName()
                        + " value=" + entry.getValue() + " -> 0x" + Integer.toHexString(gravity)
                        + " (" + gravity + ") lp=" + layoutParams.getClass().getName()
                        + (written ? "" : " [LP 无 gravity 字段，跳过]"));
                if (written) {
                    continue;
                }
            }
            if (setter == null) {
                continue;
            }
            try {
                // 直接落到具体 ViewInflater，不经过 mLayoutInflaterDelegate：避免同一属性被 delegate
                // 观察两次（attach 后 applyAttributes 还会再走一次 delegate 路径）。
                setter.setAttr(view, ns, local, entry.getValue(), parent, attrs);
            } catch (Throwable error) {
                Log.w(LOG_TAG, "预套布局属性失败（继续）：" + key + "=" + entry.getValue(), error);
            }
        }
        // 最终 LP 调试日志放在预套 layout_* 之后，dev-994 才能看到真实的 gravity 值。
        // DrawerLayout/CoordinatorLayout 这类会读 LP 的 parent 打 I 级，其它打 D 级。
        String parentName = parent.getClass().getName();
        if (parentName.contains("DrawerLayout") || parentName.contains("CoordinatorLayout")) {
            Log.i(LOG_TAG, "LP 就位: " + view.getClass().getName() + " parent=" + parentName
                    + " lp=" + layoutParams.getClass().getName() + " gravity=" + gravityOf(layoutParams)
                    + " (0x" + Integer.toHexString(gravityOf(layoutParams)) + ")");
        } else {
            Log.d(LOG_TAG, "LP 就位: " + view.getClass().getName() + " parent=" + parentName
                    + " lp=" + layoutParams.getClass().getName() + " gravity=" + gravityOf(layoutParams));
        }
    }

    /** layout_gravity 值解析：支持 start/end（Gravities 已补）、"top|start" 组合与 0x 数值形式。 */
    private static int parseLayoutGravity(String value) {
        if (value == null) {
            return 0;
        }
        String text = value.trim();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            try {
                return (int) Long.parseLong(text.substring(2), 16);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return com.stardust.autojs.core.ui.inflater.util.Gravities.parse(text);
    }

    /** 把 gravity 直接写进 LP 的 gravity 字段（DrawerLayout/LinearLayout/FrameLayout 的 LayoutParams 都有）。 */
    private static boolean writeLayoutGravity(Object layoutParams, int gravity) {
        for (Class<?> type = layoutParams.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("gravity");
                field.setAccessible(true);
                field.setInt(layoutParams, gravity);
                return true;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return false;
    }

    /**
     * 生成 parent 类型正确的默认 LayoutParams。
     *
     * <p>{@code ViewGroup.generateDefaultLayoutParams()} 是 protected（public SDK 里的 protected 成员），
     * inflater 不是 ViewGroup 子类，只能用反射调用；父类实现会被虚分派到具体 ViewGroup
     * （DrawerLayout → DrawerLayout.LayoutParams、CoordinatorLayout → CoordinatorLayout.LayoutParams）。
     */
    @Nullable
    private static ViewGroup.LayoutParams generateDefaultLayoutParams(ViewGroup parent) {
        try {
            java.lang.reflect.Method method = ViewGroup.class.getDeclaredMethod("generateDefaultLayoutParams");
            method.setAccessible(true);
            return (ViewGroup.LayoutParams) method.invoke(parent);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "generateDefaultLayoutParams 反射失败：" + parent.getClass().getName(), error);
            return null;
        }
    }

    /** 反射调用 protected 的 {@code ViewGroup.checkLayoutParams(LayoutParams)}。 */
    private static boolean checkLayoutParams(ViewGroup parent, ViewGroup.LayoutParams params) {
        try {
            java.lang.reflect.Method method = ViewGroup.class.getDeclaredMethod(
                    "checkLayoutParams", ViewGroup.LayoutParams.class);
            method.setAccessible(true);
            Object result = method.invoke(parent, params);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable error) {
            // 反射失败时保守按「不匹配」处理，让调用方生成 parent 默认 LP。
            return false;
        }
    }

    /**
     * 把已有 LP 转成 parent 类型（等价于 protected 的 {@code generateLayoutParams(LayoutParams)}）。
     */
    @Nullable
    private static ViewGroup.LayoutParams generateLayoutParams(ViewGroup parent, ViewGroup.LayoutParams params) {
        try {
            java.lang.reflect.Method method = ViewGroup.class.getDeclaredMethod(
                    "generateLayoutParams", ViewGroup.LayoutParams.class);
            method.setAccessible(true);
            return (ViewGroup.LayoutParams) method.invoke(parent, params);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "generateLayoutParams 反射失败：" + parent.getClass().getName(), error);
            return null;
        }
    }

    /** 反射读 LP 的 gravity（DrawerLayout/LinearLayout/FrameLayout 的 LayoutParams 都有这个字段）。 */
    private static int gravityOf(Object layoutParams) {
        for (Class<?> type = layoutParams.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("gravity");
                field.setAccessible(true);
                return field.getInt(layoutParams);
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return 0;
    }

    private static String readFileText(File file) throws java.io.IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) > 0) {
                offset += read;
            }
            return new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    protected void applyPendingAttributesOfChildren(InflateContext context, ViewGroupInflater inflater, ViewGroup view) {
        if (mLayoutInflaterDelegate.beforeApplyPendingAttributesOfChildren(context, inflater, view)) {
            return;
        }
        inflater.applyPendingAttributesOfChildren(view);
        mLayoutInflaterDelegate.afterApplyPendingAttributesOfChildren(context, inflater, view);

    }


    @SuppressWarnings("unchecked")
    public ViewInflater<View> applyAttributes(InflateContext context, View view, HashMap<String, String> attrs, @Nullable ViewGroup parent) {
        ViewInflater<View> inflater = (ViewInflater<View>) getViewInflater(view);
        if (mLayoutInflaterDelegate.beforeApplyAttributes(context, view, inflater, attrs, parent)) {
            return inflater;
        }
        applyAttributes(context, view, inflater, attrs, parent);
        mLayoutInflaterDelegate.afterApplyAttributes(context, view, inflater, attrs, parent);
        return inflater;
    }

    @Nullable
    public ViewInflater<?> getViewInflater(View view) {
        ViewInflater<?> setter = mViewAttrSetters.get(view.getClass().getName());
        Class c = view.getClass();
        while (setter == null && c != View.class) {
            c = c.getSuperclass();
            setter = mViewAttrSetters.get(c.getName());
        }
        return setter;
    }

    protected void inflateChildren(InflateContext context, ViewInflater<View> inflater, Node node, ViewGroup parent) {
        if (mLayoutInflaterDelegate.beforeInflateChildren(context, inflater, node, parent)) {
            return;
        }
        if (inflater.inflateChildren(this, node, parent)) {
            return;
        }
        inflateChildren(context, node, parent);
        mLayoutInflaterDelegate.afterInflateChildren(context, inflater, node, parent);
    }

    public void inflateChildren(InflateContext context, Node node, ViewGroup parent) {
        NodeList nodeList = node.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node currentNode = nodeList.item(i);
            if (currentNode.getNodeType() != Node.ELEMENT_NODE) continue;
            inflate(context, currentNode, parent, true);
        }
    }

    protected View doCreateView(InflateContext context, Node node, String viewName, ViewGroup parent, HashMap<String, String> attrs) {
        View view = mLayoutInflaterDelegate.beforeCreateView(context, node, viewName, parent, attrs);
        if (view != null)
            return view;
        view = mLayoutInflaterDelegate.afterCreateView(context, createViewForName(viewName, attrs), node, viewName, parent, attrs);
        // dev-994：MaterialSwitch(M3) 在 MaterialComponents Bridge 主题下会出现
        // showText=true 但 switchTextOn/switchTextOff 为 null，onMeasure 直接
        // new StaticLayout(null) → NullPointerException: CharSequence.length() on null。
        // 这类「主题没提供文案」的 Switch 家族在 attach/measure 前补空串即可避免崩溃。
        applySwitchTextDefense(view);
        return view;
    }

    /**
     * dev-994 inflater 侧 null 防御：对 Switch 家族（含 MaterialSwitch extends SwitchCompat）
     * 把 null 的 textOn/textOff 补成空串。
     *
     * <p>framework 在 {@code SwitchCompat.onMeasure()} 里只要 {@code mShowText=true} 就会
     * {@code makeLayout(mTextOnTransformed)}；主题没有提供 switchTextOn/switchTextOff 时这里为 null，
     * StaticLayout 构造直接 NPE（CharSequence.length() on null），:node24 主线程 FATAL。
     */
    private void applySwitchTextDefense(View view) {
        if (!(view instanceof androidx.appcompat.widget.SwitchCompat)) {
            return;
        }
        androidx.appcompat.widget.SwitchCompat switchView = (androidx.appcompat.widget.SwitchCompat) view;
        try {
            boolean fixed = false;
            if (switchView.getTextOn() == null) {
                switchView.setTextOn("");
                fixed = true;
            }
            if (switchView.getTextOff() == null) {
                switchView.setTextOff("");
                fixed = true;
            }
            if (fixed) {
                Log.w(LOG_TAG, "主题未提供 switchTextOn/switchTextOff（null），inflater 兜底补空串防 StaticLayout NPE："
                        + view.getClass().getName());
            }
        } catch (Throwable error) {
            // 兜底失败不能让 inflate 整个失败：后面的属性/布局照常，最坏是该控件 measure 崩（记日志便于定位）。
            Log.w(LOG_TAG, "Switch 文案 null 兜底失败（继续）：" + view.getClass().getName(), error);
        }
    }

    public View createViewForName(String name, HashMap<String, String> attrs) {
        try {
            if (name.equals("View")) {
                return new View(mContext);
            }
            if (!name.contains(".")) {
                name = "android.widget." + name;
            }
            ViewCreator<?> creator = mViewCreators.get(name);
            if (creator != null) {
                return creator.create(mContext, attrs);
            }
            Class<?> clazz;
            try {
                clazz = loadViewClass(name);
            } catch (ClassNotFoundException missing) {
                // Pro 工程布局可能引用本仓库不存在的 Pro 宿主类（如 FileConsoleView）：
                // 先走兼容映射/占位，保证 activity_main 整体能显示；确实兜不住再抛原始 CNFE。
                View compat = ProjectViewCompat.create(mContext, name);
                if (compat != null) {
                    return compat;
                }
                throw missing;
            }
            return constructView(name, clazz, attrValue(attrs, "style"));
        } catch (Exception e) {
            throw new InflateException(e);
        }
    }

    /** 取属性值（含带命名空间写法，如 android:style / app:style）。 */
    private static String attrValue(HashMap<String, String> attrs, String name) {
        String value = attrs.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            String key = entry.getKey();
            int colon = key.indexOf(':');
            if (colon >= 0 && key.substring(colon + 1).equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * dev-1016：按「构造器候选链」创建 View，命中即用，全部失败再回退系统 LayoutInflater。
     *
     * <p>背景：Auto.js Pro 的原生 res/ 布局大量使用 M3 控件（MaterialButton / MaterialCardView /
     * ShapeableImageView / MaterialToolbar / TextInputLayout / MaterialSwitch…），这些类通常只有
     * {@code (Context, AttributeSet, int)} / {@code (Context, AttributeSet)} / {@code (Context)} 三种构造器，
     * <b>没有</b> 4 参 {@code (Context, AttributeSet, int, int)}。旧实现只要布局里带 {@code style=...}
     * 就固定找 4 参构造器 → NoSuchMethodException（dev-1012 grid_item 卡片降级白卡）。
     *
     * <p>候选链（带 style 时）：(C,AS,int,int) → (C,AS,int) → (C,AS) → (C)；
     * 不带 style 时先沿用语义等价的 (C)（框架默认 defStyleAttr 解析），失败再逐级回退。
     * style 解析规则：
     * <ul>
     *   <li>{@code ?attr/xxx}（主题属性引用）→ 解析成 attr id，作为 defStyleAttr 交给 3/4 参构造器，
     *       由 framework 从主题取样式（例如 Button 的 {@code ?attr/materialButtonOutlinedStyle}）；</li>
     *   <li>{@code @style/xxx} 与裸 style 名（如 {@code Widget.AppCompat.Button.Colored}）→ 解析成
     *       style res id，作为 defStyleRes 交给 4 参构造器；</li>
     *   <li>{@code 0x...} → 按十六进制 style res id 解析（沿用既有分支）；</li>
     *   <li>{@code @null}/空值 → 按无 style 处理，保留控件默认样式；</li>
     *   <li>style 解析结果为 0（找不到）→ 不走全 0 的 {@code (C,AS,0,0)}（会绕开控件默认样式，
     *       按钮这类控件会丢失 padding/minHeight），而是回退无 style 候选链并打 W 日志；</li>
     *   <li>类没有 4 参构造器时 style 无法由框架应用：降级创建并打 W 日志（记录 class/arity/style）。</li>
     * </ul>
     */
    private View constructView(String name, Class<?> clazz, String style) {
        int defStyleAttr = 0;
        int defStyleRes = 0;
        String styleText = null;
        if (style != null) {
            String text = style.trim();
            if (!text.isEmpty() && !"@null".equals(text)) {
                styleText = text;
                if (text.startsWith("?")) {
                    defStyleAttr = com.stardust.autojs.core.ui.inflater.util.Dimensions.resolveAttr(mContext, text);
                } else if (text.startsWith("0x")) {
                    // 工程独立包的 id 可能是 0x8xxxxxxx（int 最高位为 1），先用 long 解析再截断。
                    try {
                        int styleRes = (int) Long.parseLong(text.substring(2), 16);
                        // 只接受真实 style 类型；拼错或指向其它资源时按未解析回退，
                        // 避免构造失败后又退到全 0 的 3 参构造。
                        if (styleRes != 0
                                && "style".equals(mContext.getResources().getResourceTypeName(styleRes))) {
                            defStyleRes = styleRes;
                        }
                    } catch (Exception ignored) {
                        defStyleRes = 0;
                    }
                } else {
                    // 裸 style 名与 @style/xxx 都交给 Res：后者会剥离前缀，再按名字查两个资源包。
                    defStyleRes = Res.parseStyle(mContext, text);
                }
            }
        }
        if (styleText != null && defStyleAttr == 0 && defStyleRes == 0) {
            // 关键：style 查不到时不能继续用 (C,AS,0,0)。那会绕开控件在 (C) 里选择的默认
            // defStyleAttr（如 buttonStyle），比完全不写 style 还差（按钮被压成文字条）。
            Log.w(LOG_TAG, "inflater: style 未解析，按无 style 创建以保留默认样式：" + name + " style=" + styleText);
            styleText = null;
        }
        if (styleText != null) {
            // 4 参（能同时带 defStyleAttr / defStyleRes）
            View view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class, int.class, int.class},
                    mContext, null, defStyleAttr, defStyleRes);
            if (view != null) {
                Log.d(LOG_TAG, "inflater: ctor " + name + " arity=4 style=" + styleText);
                return view;
            }
            // 3 参：defStyleAttr 可用于 ?attr 形式；@style 形式会丢样式（打 W）
            view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class, int.class},
                    mContext, null, defStyleAttr);
            if (view != null) {
                if (defStyleRes != 0 && defStyleAttr == 0) {
                    Log.w(LOG_TAG, "inflater: ctor fallback " + name + " arity=3 style 无法应用（类无 4 参构造器）：" + styleText);
                } else {
                    Log.d(LOG_TAG, "inflater: ctor " + name + " arity=3 style=" + styleText);
                }
                return view;
            }
            view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class}, mContext, null);
            if (view != null) {
                Log.w(LOG_TAG, "inflater: ctor fallback " + name + " arity=2 style 无法应用：" + styleText);
                return view;
            }
            view = tryConstruct(clazz, new Class[]{Context.class}, mContext);
            if (view != null) {
                Log.w(LOG_TAG, "inflater: ctor fallback " + name + " arity=1 style 无法应用：" + styleText);
                return view;
            }
        } else {
            // 无 style：先用 (C)（与框架默认 defStyleAttr 语义一致，M3 控件目前都走这条）
            View view = tryConstruct(clazz, new Class[]{Context.class}, mContext);
            if (view != null) {
                return view;
            }
            Log.w(LOG_TAG, "inflater: ctor fallback " + name + " arity=1 不可用，尝试 (C,AS)/(C,AS,int)/(C,AS,int,int)");
            view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class}, mContext, null);
            if (view != null) {
                return view;
            }
            view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class, int.class}, mContext, null, 0);
            if (view != null) {
                return view;
            }
            view = tryConstruct(clazz, new Class[]{Context.class, AttributeSet.class, int.class, int.class}, mContext, null, 0, 0);
            if (view != null) {
                return view;
            }
        }
        // 最后一档：系统 LayoutInflater 创建（等价框架默认构造路径），失败才抛 InflateException。
        Log.w(LOG_TAG, "inflater: ctor fallback " + name + " → LayoutInflater.createView()");
        try {
            View fallback = android.view.LayoutInflater.from(mContext).createView(name, null, null);
            if (fallback != null) {
                return fallback;
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "inflater: LayoutInflater 兜底失败：" + name, error);
        }
        throw new InflateException("无法创建 View：" + name + "（候选构造器与 LayoutInflater 均失败）");
    }

    /** 尝试一个构造器：不存在（NoSuchMethodException）返回 null 继续候选链；存在但实例化失败只 W 不中断。 */
    @Nullable
    private View tryConstruct(Class<?> clazz, Class<?>[] types, Object... args) {
        try {
            return (View) clazz.getConstructor(types).newInstance(args);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable error) {
            Log.w(LOG_TAG, "inflater: ctor 存在但实例化失败 " + clazz.getName() + " arity=" + types.length, error);
            return null;
        }
    }


    /**
     * 布局里的 View 类查找链（dev-988 FileConsoleView CNFE 的修复）：
     * <ol>
     *   <li>工程动态 dex（{@code .codecache/*.dex}，其 parent 已链到 context.getClassLoader()）；</li>
     *   <li>宿主 Application ClassLoader（{@code context.getClassLoader()}，必须能加载
     *       {@code com.stardust.autojs.*} 宿主类）；</li>
     *   <li>当前线程 context ClassLoader（:node24/overlay 里 Context 链异常时兜底）；</li>
     *   <li>调用方 ClassLoader（旧行为兜底）。</li>
     * </ol>
     * 全部失败时抛带「已尝试哪些 ClassLoader」的 CNFE，便于真机日志定位。
     */
    private Class<?> loadViewClass(String name) throws ClassNotFoundException {
        ClassLoader projectLoader = mClassLoader;
        ClassLoader hostLoader = null;
        ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
        StringBuilder tried = new StringBuilder();

        // 1) 工程 codecache DexClassLoader（parent = context.getClassLoader()）
        if (projectLoader != null) {
            try {
                return Class.forName(name, false, projectLoader);
            } catch (ClassNotFoundException ignored) {
                tried.append("projectDex(").append(projectLoader).append("); ");
            }
        }
        // 2) 宿主 Application ClassLoader：com.stardust.autojs.* 宿主类必须从这里找到
        Context context = mContext;
        if (context != null) {
            hostLoader = context.getClassLoader();
            if (hostLoader != null && hostLoader != projectLoader) {
                try {
                    return Class.forName(name, false, hostLoader);
                } catch (ClassNotFoundException ignored) {
                    tried.append("context(").append(hostLoader).append("); ");
                }
            }
        }
        // 3) 线程 context ClassLoader
        if (threadLoader != null && threadLoader != projectLoader && threadLoader != hostLoader) {
            try {
                return Class.forName(name, false, threadLoader);
            } catch (ClassNotFoundException ignored) {
                tried.append("thread(").append(threadLoader).append("); ");
            }
        }
        // 4) 调用方/系统 ClassLoader（保持旧行为兜底）
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException error) {
            throw new ClassNotFoundException(name + "（已尝试 ClassLoader: " + tried
                    + "caller(" + DynamicLayoutInflater.class.getClassLoader() + ")）", error);
        }
    }

    public HashMap<String, String> getAttributesMap(Node currentNode) {
        NamedNodeMap attributeMap = currentNode.getAttributes();
        int attributeCount = attributeMap.getLength();
        HashMap<String, String> attributes = new HashMap<>(attributeCount);
        for (int j = 0; j < attributeCount; j++) {
            Node attr = attributeMap.item(j);
            String nodeName = attr.getNodeName();
            attributes.put(nodeName, attr.getNodeValue());
        }
        resolveProjectResources(attributes);
        return attributes;
    }

    /**
     * 把工程资源引用（{@code @color/x}、{@code @string/x}、{@code @dimen/x}、{@code @drawable/x}）
     * 就地改写成引擎自己能解析的字面量。
     *
     * <p>只在工程模式（{@code project.json} 声明了 {@code androidResources}）下才生效，
     * 普通脚本走这里是一个空循环。
     */
    private void resolveProjectResources(HashMap<String, String> attributes) {
        ProjectResources resources = mResourceParser.getProjectResources();
        if (resources == null) {
            return;
        }
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.length() < 3 || value.charAt(0) != '@') {
                continue;
            }
            String resolved = resources.resolveReference(value);
            if (resolved != null) {
                entry.setValue(resolved);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void applyAttributes(InflateContext context, View view, ViewInflater<View> setter, Map<String, String> attrs, @Nullable ViewGroup parent) {
        if (setter != null) {
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                String[] attr = entry.getKey().split(":");
                if (attr.length == 1) {
                    applyAttribute(context, setter, view, null, attr[0], entry.getValue(), parent, attrs);
                } else if (attr.length == 2) {
                    applyAttribute(context, setter, view, attr[0], attr[1], entry.getValue(), parent, attrs);
                } else {
                    throw new InflateException("illegal attr name: " + entry.getKey());
                }
            }
            setter.applyPendingAttributes(view, parent);
        } else {
            Log.e(LOG_TAG, "cannot set attributes for view: " + view.getClass());
        }

    }

    protected void applyAttribute(InflateContext context, ViewInflater<View> inflater, View view, String ns, String attrName, String value, ViewGroup parent, Map<String, String> attrs) {
        if (value == null) {
            // dev-994：属性值 null 一律跳过（例如工程资源/字符串解析失败后留下的 null），
            // 不能把 null 交给 framework 的 setText/setTitle/menu.add 之类方法。
            Log.w(LOG_TAG, "跳过 null 属性值：" + attrName + "（view=" + view.getClass().getName() + "）");
            return;
        }
        if (mLayoutInflaterDelegate.beforeApplyAttribute(context, inflater, view, ns, attrName, value, parent, attrs)) {
            return;
        }
        boolean isDynamic = isDynamicValue(value);
        if ((isDynamic && mInflateFlags == FLAG_IGNORES_DYNAMIC_ATTRS)
                || (!isDynamic && mInflateFlags == FLAG_JUST_DYNAMIC_ATTRS)) {
            return;
        }
        try {
            inflater.setAttr(view, ns, attrName, value, parent, attrs);
        } catch (Throwable error) {
            // dev-994：单个属性应用失败（类型不匹配/主题缺 attr/资源缺失…）不能让整个 overlay inflate 失败，
            // 记 W 后跳过该属性继续；真机 logcat 按「属性应用失败」grep 可定位。
            Log.w(LOG_TAG, "属性应用失败，跳过：" + (ns == null ? "" : ns + ":") + attrName + "=" + value
                    + "（view=" + view.getClass().getName() + "）", error);
            return;
        }
        try {
            mLayoutInflaterDelegate.afterApplyAttribute(context, inflater, view, ns, attrName, value, parent, attrs);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "属性 delegate 回调失败（继续）：" + attrName, error);
        }
    }

    public boolean isDynamicValue(String value) {
        int i = value.indexOf("{{");
        if (i < 0)
            return false;
        return value.indexOf("}}", i + 1) >= 0;
    }


}
