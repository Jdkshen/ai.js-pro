package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;

import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.InflateContext;
import com.stardust.autojs.core.ui.inflater.LayoutInflaterDelegate;
import com.stardust.autojs.core.ui.inflater.ProjectResources;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.ShouldCallOnFinishInflate;
import com.stardust.autojs.core.ui.inflater.ViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ViewGroupInflater;
import com.stardust.autojs.core.ui.inflater.util.Ids;
import com.stardust.autojs.core.ui.inflater.util.Res;
import com.stardust.autojs.core.ui.xml.XmlConverter;

import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Per-inflation Widget stages. All calls happen inside the protected main-thread inflate. */
public final class NodeUiWidgetInflater extends DynamicLayoutInflater {
    private static final String TAG = "NodeUiWidgetInflater";
    private static final ThreadLocal<Context> CURRENT_CONTEXT = new ThreadLocal<>();
    /** bug 2.3：已经提示过的工程 xml 图标，避免每次 inflate 刷屏。 */
    private static final Set<String> WARNED_XML_DRAWABLES = ConcurrentHashMap.newKeySet();
    /** xml drawable 文件 → 根标签缓存；每次属性解析都读文件会拖慢 list 绑定。 */
    private static final ConcurrentHashMap<String, String> XML_DRAWABLE_ROOTS = new ConcurrentHashMap<>();

    /**
     * task-246 R03：Widget 标签上的构造期属性。
     *
     * <p>{@code style} / {@code theme} 和普通属性不同，必须在根 View <b>构造时</b> 生效：
     * style 交给 {@code (Context, AttributeSet, int, int)} 的 defStyleRes，theme 交给
     * {@link ContextThemeWrapper}。后置 {@code setAttr} 对它们没有意义（{@code BaseViewInflater}
     * 对 style 返回 false、对 theme 是显式 no-op），旧实现等于把这两个外部声明丢掉了。
     */
    static final class Construction {
        final String style;
        final String theme;

        private Construction(String style, String theme) {
            this.style = style;
            this.theme = theme;
        }

        /** 取 Widget 标签上的 style/theme 引用；两个名字都接受带命名空间的写法（android:/app:）。 */
        static Construction of(Map<String, String> attributes) {
            return new Construction(reference(attributes, "style"), reference(attributes, "theme"));
        }

        private static String reference(Map<String, String> attributes, String name) {
            if (attributes == null) {
                return null;
            }
            String direct = useful(attributes.get(name));
            if (direct != null) {
                return direct;
            }
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String key = entry.getKey();
                int colon = key == null ? -1 : key.indexOf(':');
                if (colon < 0 || !key.substring(colon + 1).equals(name)) {
                    continue;
                }
                String value = useful(entry.getValue());
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static String useful(String value) {
            if (value == null) {
                return null;
            }
            String text = value.trim();
            return text.isEmpty() || "@null".equals(text) ? null : text;
        }
    }

    /** 把一个构造期属性从「后置应用」的属性表里移除（含带命名空间的写法）。 */
    static void removeAttribute(Map<String, String> attributes, String name) {
        if (attributes == null) {
            return;
        }
        attributes.remove(name);
        java.util.Iterator<String> keys = attributes.keySet().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            int colon = key.indexOf(':');
            if (colon >= 0 && key.substring(colon + 1).equals(name)) {
                keys.remove();
            }
        }
    }

    /**
     * 把构造期 style 注入本轮渲染的根节点：{@code DynamicLayoutInflater.createViewForName} 会读
     * {@code style} 作为 defStyleRes，因此必须在根 View 创建前写进 DOM。外部（Widget 标签）的
     * 声明覆盖 render XML 根节点自己的 style，与「外部属性覆盖内部默认值」一致。
     */
    static void applyConstructionStyle(Node node, String style) {
        if (style == null || !(node instanceof Element)) {
            return;
        }
        ((Element) node).setAttribute("style", style);
    }
    static Context currentContext() { return CURRENT_CONTEXT.get(); }

    // ------------------------------------------------------------------ 布局引用解析（bug 2.4 / 2.6）
    //
    // 以前 <include layout="…"> 只认「名字」，而且直接把原始引用交给 ProjectResources.fileOf()：
    //  - 数字 id（ui.R.layout.xxx / @2131427356 / 0x7f0b0001）永远查不到，报错里只有一个裸数字；
    //  - 参考 res/layout 的原生布局还会写成 android:layout，同样只按裸名字查。
    // 现在统一先解析成「布局名」再交给 base 的 inflateInclude 展开；解析不出来时抛带「布局不存在」
    // 的可执行诊断（列出可用的布局名），不再只回显数字。

    /**
     * 布局引用里的数字 id：{@code @2131427356} / {@code 2131427356} /
     * {@code @0x7f0b0001} / {@code 0x7f0b0001} / {@code #7f0b0001}。
     *
     * @return 解析出的 id；不是纯数字引用（含 {@code @layout/name} 这类命名引用）时返回 0
     */
    static int parseLayoutId(String reference) {
        if (reference == null) {
            return 0;
        }
        String text = reference.trim();
        if (text.isEmpty()) {
            return 0;
        }
        if (text.charAt(0) == '@') {
            text = text.substring(1).trim();
        }
        if (text.startsWith("+")) {
            text = text.substring(1).trim();
        }
        // 带 type/ 的引用（@layout/x、@id/x）不是数字 id。
        if (text.isEmpty() || text.indexOf('/') >= 0) {
            return 0;
        }
        // 少数包段的资源 id 可能是负数（文档里的 layoutId 也可能为负），十进制要接受前导 '-'。
        boolean negative = text.startsWith("-");
        if (negative) {
            text = text.substring(1);
        }
        boolean hexPrefix = text.startsWith("0x") || text.startsWith("0X");
        boolean hex = !negative && (hexPrefix || text.startsWith("#"));
        String digits = hex ? text.substring(hexPrefix ? 2 : 1) : text;
        if (digits.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            boolean ok = hex ? Character.digit(c, 16) >= 0 : Character.isDigit(c);
            if (!ok) {
                return 0;
            }
        }
        try {
            long value = Long.parseLong((negative ? "-" : "") + digits, hex ? 16 : 10);
            return (int) value;
        } catch (NumberFormatException overflow) {
            return 0;
        }
    }

    /**
     * 布局引用 → 工程布局名。支持 {@code @layout/x}、{@code @x}、{@code x}、{@code x.xml}，
     * 以及数字 id（经资源表反查）。
     *
     * @return 布局名；引用为空/不是布局（如 {@code @color/x}、framework 资源）/数字 id 查不到时返回 null
     */
    static String resolveLayoutName(String reference, ProjectResources resources) {
        if (reference == null) {
            return null;
        }
        String text = reference.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.charAt(0) == '@') {
            String body = text.substring(1).trim();
            if (body.startsWith("+")) {
                body = body.substring(1).trim();
            }
            int slash = body.indexOf('/');
            if (slash >= 0) {
                String type = body.substring(0, slash);
                if (type.startsWith("android:")) {
                    // @android:layout/x 是 framework 布局，不属于工程资源表。
                    return null;
                }
                if (!ProjectResources.TYPE_LAYOUT.equals(type)) {
                    return null;
                }
                body = body.substring(slash + 1);
            }
            text = body;
        }
        int id = parseLayoutId(text);
        if (id != 0) {
            return resources == null ? null : resources.layoutNameOfId(id);
        }
        String name = ProjectResources.stripExtension(text);
        return name.isEmpty() ? null : name;
    }

    /**
     * 找不到布局时的用户可见诊断。始终包含「布局不存在」，并把原始引用、可用布局名与
     * 需要检查的位置列出来；绝不把裸数字当成最终答案。
     */
    static String layoutMissingMessage(String reference, ProjectResources resources) {
        StringBuilder message = new StringBuilder("布局不存在：")
                .append(reference == null || reference.trim().isEmpty() ? "(include 缺少 layout 属性)" : reference.trim());
        int id = parseLayoutId(reference);
        if (id != 0) {
            String name = resolveLayoutName(reference, resources);
            message.append("（数字 id ").append(id);
            message.append(resources == null
                    ? "：工程 androidResources 未加载，无法反查布局名"
                    : "：资源表里没有对应布局").append("）");
            if (name != null) {
                message.append("，解析为 @layout/").append(name);
            }
        }
        message.append("。请检查 layout 引用（@layout/名字 或 ui.R.layout.名字），"
                + "以及 project.json 的 androidResources / res/layout 下是否存在该文件");
        if (resources == null) {
            message.append("（当前 project resources=null）");
        } else {
            String[] names = resources.names(ProjectResources.TYPE_LAYOUT);
            message.append("；已加载布局 ").append(names.length).append(" 个");
            if (names.length > 0) {
                message.append("：");
                for (int i = 0; i < names.length && i < 12; i++) {
                    if (i > 0) message.append('、');
                    message.append(names[i]);
                }
                if (names.length > 12) message.append("…");
            }
        }
        return message.toString();
    }

    /**
     * include 的属性转换失败时，XmlConverter 会把节点换成占位标签并丢掉属性，layout 引用只在
     * {@link NodeUiXmlSource.Input#attributes} 里；这里把它还原到 DOM，供 {@link #inflateInclude} 读取。
     */
    private static void restoreIncludeLayoutReference(Node node, NodeUiXmlSource.Input input) {
        if (input == null || !(node instanceof Element) || layoutReferenceOf(node) != null) {
            return;
        }
        String layout = input.attributes.get("layout");
        if (layout == null) {
            for (Map.Entry<String, String> entry : input.attributes.entrySet()) {
                if (entry.getKey() != null && entry.getKey().endsWith(":layout")) {
                    layout = entry.getValue();
                    break;
                }
            }
        }
        if (layout != null && !layout.trim().isEmpty()) {
            ((Element) node).setAttribute("layout", layout);
        }
    }

    /** 读取 include 节点上的 layout 引用（裸 layout 或任意命名空间前缀的 *:layout）。 */
    private static String layoutReferenceOf(Node node) {
        org.w3c.dom.NamedNodeMap attributes = node.getAttributes();
        if (attributes == null) {
            return null;
        }
        Node direct = attributes.getNamedItem("layout");
        if (direct != null && direct.getNodeValue() != null && !direct.getNodeValue().trim().isEmpty()) {
            return direct.getNodeValue();
        }
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            String name = attr.getNodeName();
            if (name != null && name.endsWith(":layout") && attr.getNodeValue() != null
                    && !attr.getNodeValue().trim().isEmpty()) {
                return attr.getNodeValue();
            }
        }
        return null;
    }

    private ProjectResources projectResources() {
        ResourceParser parser = getResourceParser();
        return parser == null ? null : parser.getProjectResources();
    }
    public interface Callbacks {
        Object render(long token, String name, Context context, ViewGroup parent);
        void rootViewCreated(long token, View view);
        String contentSlot(long token);
        void inflated(long token, String internalAttributes);
        boolean hasAttribute(long token, String name);
        void setAttribute(long token, String name, String value);
        void mounted(long token);
        void release(long token);
    }

    public static final class Registration implements AutoCloseable {
        public final String owner;
        private final Callbacks callbacks;
        private final AtomicLong next = new AtomicLong();
        private volatile Set<String> tags;
        private volatile boolean closed;

        public Registration(String owner, String[] names, Callbacks callbacks) {
            this.owner = owner;
            this.callbacks = callbacks;
            updateTags(names);
        }
        public void updateTags(String[] names) {
            Set<String> copy = new HashSet<>();
            for (String name : names) copy.add(name.toLowerCase(Locale.ROOT));
            tags = copy;
        }
        public boolean supports(String name) { return !closed && tags.contains(name.toLowerCase(Locale.ROOT)); }
        public void checkOpen() { if (closed) throw new IllegalStateException("Widget script scope is closed"); }
        @Override public void close() { closed = true; NodeUiLocalSession.clearWidgetRegistration(this); }
    }

    private static final class Frame {
        long token;
        Node rootNode;
        View root;
        HashMap<String, String> internal = new HashMap<>();
        boolean waitingRoot = true;
        /** task-246 R03：Widget 标签上的 style，等真正创建根 View 时作为 defStyleRes 注入。 */
        String constructionStyle;
    }
    private final Registration registration;
    private final NodeUiXmlSource source = new NodeUiXmlSource();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final IdentityHashMap<View, Frame> roots = new IdentityHashMap<>();
    private ViewGroup syntheticParent;

    public NodeUiWidgetInflater(ResourceParser parser, Registration registration) {
        super(parser);
        this.registration = registration;
        setLayoutInflaterDelegate(new Delegate());
    }
    public void setSyntheticParent(ViewGroup parent) { syntheticParent = parent; }

    @Override protected String convertXml(InflateContext context, String xml) {
        registration.checkOpen();
        try { return source.convert(xml, registration::supports, NodeUiHost::normalizeInlineTagName); }
        catch (Exception error) { throw new InflateException(error); }
    }

    @Override public View inflate(InflateContext context, Node node, ViewGroup parent, boolean attach) {
        registration.checkOpen();
        NodeUiXmlSource.Input input = source.take(node);
        String tag = input == null ? node.getNodeName() : input.tag;
        boolean widget = registration.supports(tag);
        if (!frames.isEmpty() && frames.peek().waitingRoot && frames.peek().rootNode == null) {
            frames.peek().rootNode = node;
            frames.peek().internal = widget ? originalAttributes(node) : getAttributesMap(node);
        }
        if (widget) {
            View view = inflateWidget(context, node, parent, attach, tag);
            if (view instanceof ShouldCallOnFinishInflate) ((ShouldCallOnFinishInflate) view).onFinishDynamicInflate();
            return view;
        }
        // <include> 的展开由 inflateInclude 负责。转换失败的 include，DOM 已被占位标签替换、
        // layout 引用只留在 Input 里——先还原再展开，这样页面仍会进入 parent 的 child
        // （ViewPager/list 的 initAdapterFromChildren 才数得到，bug 2.6）；转换期告警不让整页失败。
        if ("include".equals(tag) || "include".equals(node.getNodeName())) {
            restoreIncludeLayoutReference(node, input);
            return inflateInclude(context, node, parent, attach);
        }
        if (input != null && input.conversionError != null) throw new InflateException(input.conversionError);
        // 根 View 创建前：本轮等待根的 Widget 别名（可能多层）把构造期 style 交给它。
        applyPendingConstructionStyle(node);
        return super.inflate(context, node, parent, attach);
    }

    /**
     * 汇总所有等待根的 Widget 别名的构造期 style 并注入根节点。别名链里越靠外层的声明越优先
     * （内层 → 外层的迭代顺序，后面的覆盖前面的），同一根只注入一次。
     */
    private void applyPendingConstructionStyle(Node node) {
        // <include> 自己不是根：真正创建 View 的是展开后的布局根节点，style 留到那时再注入。
        if ("include".equals(node.getNodeName())) return;
        String style = null;
        for (Frame frame : frames) {
            if (!frame.waitingRoot) continue;
            if (frame.constructionStyle != null) style = frame.constructionStyle;
            frame.constructionStyle = null;
        }
        if (style == null) return;
        applyConstructionStyle(node, style);
        Log.i(TAG, "C-WIDGET: 构造期 style=" + style + " → " + node.getNodeName());
    }

    private HashMap<String, String> originalAttributes(Node node) {
        NodeUiXmlSource.Input input = source.take(node);
        if (input != null) return new HashMap<>(input.attributes);
        HashMap<String, String> attrs = getAttributesMap(node);
        String encoded = attrs.get("android:__aijsWidgetAttrs");
        if (encoded == null) return attrs;
        try {
            JSONObject json = new JSONObject(encoded);
            HashMap<String, String> original = new HashMap<>();
            java.util.Iterator<String> names = json.keys();
            while (names.hasNext()) { String name = names.next(); original.put(name, json.getString(name)); }
            return original;
        } catch (Exception error) { throw new InflateException(error); }
    }

    private HashMap<String, String> nativeAttributes(Node node, Map<String, String> original,
                                                     Frame frame, Map<String, String> custom) {
        StringBuilder xml = new StringBuilder("<view xmlns:android=\"http://schemas.android.com/apk/res/android\"");
        Set<String> prefixes = new HashSet<>(); prefixes.add("android");
        for (Map.Entry<String, String> attr : original.entrySet()) {
            String key = attr.getKey();
            if (key.equals("xmlns") || key.startsWith("xmlns:")) continue;
            int colon = key.indexOf(':');
            String name = colon < 0 ? key : key.substring(colon + 1);
            if (registration.callbacks.hasAttribute(frame.token, name)) {
                custom.put(name, attr.getValue()); continue;
            }
            if (colon > 0) {
                String prefix = key.substring(0, colon);
                if (prefixes.add(prefix)) {
                    String uri = original.get("xmlns:" + prefix);
                    if (uri == null) uri = node.lookupNamespaceURI(prefix);
                    if (uri == null && prefix.equals("app")) uri = "http://schemas.android.com/apk/res-auto";
                    if (uri == null) throw new InflateException("Unbound Widget attribute prefix: " + prefix);
                    xml.append(" xmlns:").append(prefix).append("=\"").append(XmlConverter.escapeAttribute(uri)).append('"');
                }
            }
            xml.append(' ').append(key).append("=\"").append(XmlConverter.escapeAttribute(attr.getValue())).append('"');
        }
        try {
            String converted = XmlConverter.convertToAndroidLayout(xml.append("/>").toString());
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return getAttributesMap(factory.newDocumentBuilder().parse(new InputSource(new StringReader(converted))).getDocumentElement());
        } catch (Exception error) { throw new InflateException(error); }
    }

    @Override public View inflate(InflateContext context, String xml, ViewGroup parent, boolean attach) {
        Context previous = CURRENT_CONTEXT.get();
        CURRENT_CONTEXT.set(getContext());
        try { return super.inflate(context, xml, parent, attach); }
        finally { if (previous == null) CURRENT_CONTEXT.remove(); else CURRENT_CONTEXT.set(previous); }
    }

    private void bindPendingRoots(View view) {
        // An alias Widget may render another Widget. All aliases receive the same
        // real root before its XML children; the outermost Widget owns extras.
        for (Frame frame : frames) {
            if (!frame.waitingRoot) continue;
            frame.waitingRoot = false;
            frame.root = view;
            registration.callbacks.rootViewCreated(frame.token, view);
            registration.checkOpen();
        }
        if (!frames.isEmpty() && frames.peek().root == view) roots.put(view, frames.peek());
    }

    private View inflateWidget(InflateContext context, Node node, ViewGroup parent, boolean attach, String tag) {
        registration.checkOpen();
        if (frames.size() >= 32) throw new InflateException("Widget nesting exceeds 32 levels");
        Frame frame = new Frame();
        frame.token = registration.next.incrementAndGet();
        frames.push(frame);
        boolean success = false;
        Context previousContext = null;
        try {
            HashMap<String, String> original = originalAttributes(node);
            // task-246 R03：style/theme 必须在根 View 构造时生效。Widget 自己声明了同名 setter
            //（hasAttribute）时以 Widget 为准，不下沉为构造期属性。
            Construction construction = Construction.of(original);
            boolean styleForConstruction = construction.style != null
                    && !registration.callbacks.hasAttribute(frame.token, "style");
            boolean themeForConstruction = construction.theme != null
                    && !registration.callbacks.hasAttribute(frame.token, "theme");
            if (styleForConstruction) frame.constructionStyle = construction.style;
            if (themeForConstruction) {
                Context themed = themedContextForWidget(construction.theme);
                if (themed != null) {
                    previousContext = getContext();
                    setContext(themed);
                }
            }
            Object rendered = registration.callbacks.render(frame.token, tag, getContext(),
                    parent == syntheticParent ? null : parent);
            registration.checkOpen();
            View root;
            if (rendered instanceof CharSequence) {
                String xml = rendered.toString();
                if (xml.trim().isEmpty()) throw new InflateException("Widget.render returned empty XML");
                root = inflate(xml, parent, false);
            } else if (rendered instanceof View) {
                root = (View) rendered;
                if (root.getParent() != null) throw new InflateException("Widget.render View already has a parent");
                // 原生 View 由 Widget 自己构造，宿主无法再补 defStyleRes。
                warnUnappliedConstructionStyle(frame, "原生 View render");
                bindPendingRoots(root);
            } else throw new InflateException("Widget.render must return XML or an Android View");
            warnUnappliedConstructionStyle(frame, "render 未产生可注入 style 的原生根 View");
            registration.checkOpen();
            if (frame.waitingRoot || frame.root != root) throw new InflateException("Widget render has no single root");
            String slot = registration.callbacks.contentSlot(frame.token);
            ViewGroup childrenParent = contentParent(root, slot);
            boolean hasChildren = false;
            for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                if (node.getChildNodes().item(i).getNodeType() == Node.ELEMENT_NODE) hasChildren = true;
            }
            if (hasChildren) {
                if (childrenParent == null) throw new InflateException("Widget XML children require a ViewGroup");
                inflateChildren(context, node, childrenParent);
                ViewInflater<?> childInflater = getViewInflater(childrenParent);
                if (childInflater instanceof ViewGroupInflater) {
                    applyPendingAttributesOfChildren(context, (ViewGroupInflater) childInflater, childrenParent);
                }
            }
            registration.callbacks.inflated(frame.token, new JSONObject(frame.internal).toString());
            registration.checkOpen();
            Map<String, String> custom = new HashMap<>();
            HashMap<String, String> applicable = original;
            if (styleForConstruction || themeForConstruction) {
                applicable = new HashMap<>(original);
                if (styleForConstruction) removeAttribute(applicable, "style");
                if (themeForConstruction) removeAttribute(applicable, "theme");
            }
            HashMap<String, String> external = nativeAttributes(node, applicable, frame, custom);
            // LayoutParams are generated for the actual parent before it can attach.
            prepareLayoutParamsForAttach(context, root, external, parent);
            Frame previous = roots.put(root, frame);
            try { applyAttributes(context, root, external, parent); }
            finally { if (previous == null) roots.remove(root); else roots.put(root, previous); }
            for (Map.Entry<String, String> attr : custom.entrySet()) {
                registration.callbacks.setAttribute(frame.token, attr.getKey(), attr.getValue());
                registration.checkOpen();
            }
            registration.callbacks.mounted(frame.token);
            registration.checkOpen();
            if (attach && parent != null) parent.addView(root);
            success = true;
            return root;
        } finally {
            if (previousContext != null) setContext(previousContext);
            frame.constructionStyle = null;
            frames.pop();
            if (frame.root != null && roots.get(frame.root) == frame) {
                roots.remove(frame.root);
                for (Frame parentFrame : frames) if (parentFrame.root == frame.root) {
                    roots.put(frame.root, parentFrame); break;
                }
            }
            try { registration.callbacks.release(frame.token); }
            catch (Throwable error) { if (success) throw new InflateException(error); }
        }
    }

    /**
     * Widget 标签 {@code theme="…"} 的构造期上下文：与 framework 的 {@code android:theme} 一致，
     * render()、渲染 XML 的子 View 构造和属性应用都在该主题下进行（{@code ?attr/…} 才解析得到）。
     * 解析不出来时保持原上下文，不额外创建主题。
     */
    private Context themedContextForWidget(String reference) {
        try {
            int themeId = resolveThemeId(reference);
            if (themeId == 0) {
                Log.w(TAG, "C-WIDGET: theme 未解析，保持原主题：" + reference);
                return null;
            }
            Context themed = new ContextThemeWrapper(getContext(), themeId);
            Log.i(TAG, "C-WIDGET: 构造期 theme=" + reference + " id=" + themeId);
            return themed;
        } catch (Throwable error) {
            Log.w(TAG, "C-WIDGET: 构造期 theme 失败，保持原主题：" + reference, error);
            return null;
        }
    }

    private int resolveThemeId(String reference) {
        String text = reference.trim();
        if (text.startsWith("?")) {
            return com.stardust.autojs.core.ui.inflater.util.Dimensions.resolveAttr(getContext(), text);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            try {
                int themeId = (int) Long.parseLong(text.substring(2), 16);
                return themeId != 0 && "style".equals(
                        getContext().getResources().getResourceTypeName(themeId)) ? themeId : 0;
            } catch (Exception ignored) {
                return 0;
            }
        }
        return Res.parseStyle(getContext(), text);
    }

    /** 原生 View render（或渲染 XML 没有原生根节点）时 style 无法在构造期应用，只记录不报错。 */
    private static void warnUnappliedConstructionStyle(Frame frame, String reason) {
        if (frame.constructionStyle == null) return;
        Log.w(TAG, "C-WIDGET: 构造期 style=" + frame.constructionStyle + " 无法应用（" + reason + "）");
        frame.constructionStyle = null;
    }

    private ViewGroup contentParent(View root, String slot) {
        if (slot != null && !slot.isEmpty()) {
            View found = root.findViewById(Ids.parse(slot));
            if (!(found instanceof ViewGroup)) throw new InflateException("Widget contentSlot is not a ViewGroup: " + slot);
            return (ViewGroup) found;
        }
        View found = root.findViewById(Ids.parse("slot"));
        if (!(found instanceof ViewGroup)) found = root.findViewById(Ids.parse("content"));
        return found instanceof ViewGroup ? (ViewGroup) found : root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    /**
     * bug 2.4 / 2.6：{@code <include layout="…"/>} 的引用解析与展开。
     *
     * <p>支持数字 layoutId（{@code @2131427356} / {@code 0x7f0b0001}，经工程资源表反查名字）、
     * {@code @layout/名字}、{@code @名字}、{@code 名字.xml}。解析成规范引用 {@code @layout/名字} 后
     * 交回 base 读取 res/layout 并递归 inflate —— 展开出的根 View 由 base attach 到 parent，因此
     * ViewPager / list 的 {@code getChildCount()} 会把每个 {@code <include>} 页面算进去，
     * {@code initAdapterFromChildren()} 才有正确的页数。解析不出来时抛带「布局不存在」的诊断，
     * 不再只回显一个裸数字。
     */
    @Override
    protected View inflateInclude(InflateContext context, Node node, ViewGroup parent, boolean attachToParent) {
        String reference = layoutReferenceOf(node);
        ProjectResources resources = projectResources();
        String name = resolveLayoutName(reference, resources);
        boolean fileAvailable = name != null && resources != null
                && resources.fileOf(ProjectResources.TYPE_LAYOUT, name) != null;
        if (!fileAvailable) {
            throw new InflateException(layoutMissingMessage(reference, resources));
        }
        String canonical = "@layout/" + name;
        if (!canonical.equals(reference) && node instanceof Element) {
            ((Element) node).setAttribute("layout", canonical);
            Log.i(TAG, "C-INCLUDE: 布局引用 " + reference + " → " + canonical);
        }
        View view = super.inflateInclude(context, node, parent, attachToParent);
        if (view != null) {
            Log.i(TAG, "C-INCLUDE: 已展开 " + canonical + "（parent="
                    + (parent == null ? "null" : parent.getClass().getSimpleName())
                    + ", children=" + (parent == null ? 0 : parent.getChildCount()) + "）");
        }
        return view;
    }

    /**
     * bug 2.3：工程 res/drawable 下的 xml 图标（{@code <vector>} / {@code <animated-vector>} 等）
     * 在没有 aapt2 编译资源时会被 {@code ProjectResources.createXmlDrawable} 判为不支持，静默变空白。
     * 这里在属性解析时检测并输出可执行诊断（生成并加载 build/project-res.apk），每个文件只提示一次。
     */
    @Override
    public HashMap<String, String> getAttributesMap(Node node) {
        HashMap<String, String> attributes = super.getAttributesMap(node);
        diagnoseProjectXmlDrawables(attributes);
        return attributes;
    }

    private void diagnoseProjectXmlDrawables(Map<String, String> attributes) {
        ProjectResources resources = projectResources();
        // 有真资源包时 framework 自己解析 vector；没有工程资源表时也没有 @drawable 引用可查。
        if (resources == null || resources.isCompiled()) {
            return;
        }
        for (String value : attributes.values()) {
            File file = projectXmlDrawableFile(value, resources);
            if (file == null) {
                continue;
            }
            String root = xmlDrawableRootTag(file);
            if (!isUnsupportedXmlDrawableRoot(root) || !WARNED_XML_DRAWABLES.add(file.getAbsolutePath())) {
                continue;
            }
            Log.w(TAG, vectorDrawableDiagnostic(value, file, root));
        }
    }

    /** 属性值 → 工程 xml drawable 文件；不是 {@code @drawable/@mipmap} 或 {@code file://*.xml} 时返回 null。 */
    private static File projectXmlDrawableFile(String value, ProjectResources resources) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("file://")) {
            File file = new File(value.substring("file://".length()));
            return xmlFile(file) ? file : null;
        }
        if (!value.startsWith("@")) {
            return null;
        }
        String body = value.substring(1);
        int slash = body.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String type = body.substring(0, slash);
        if (!ProjectResources.TYPE_DRAWABLE.equals(type) && !ProjectResources.TYPE_MIPMAP.equals(type)) {
            return null;
        }
        String name = ProjectResources.stripExtension(body.substring(slash + 1));
        File file = resources.fileOf(type, name);
        if (file == null && ProjectResources.TYPE_DRAWABLE.equals(type)) {
            file = resources.fileOf(ProjectResources.TYPE_MIPMAP, name);
        }
        return xmlFile(file) ? file : null;
    }

    private static boolean xmlFile(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    /** xml drawable 的根标签；文件缺失或解析失败返回 null。 */
    static String xmlDrawableRootTag(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String path = file.getAbsolutePath();
        String cached = XML_DRAWABLE_ROOTS.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            String root = factory.newDocumentBuilder().parse(file).getDocumentElement().getNodeName();
            XML_DRAWABLE_ROOTS.put(path, root);
            return root;
        } catch (Throwable error) {
            Log.w(TAG, "读取 xml drawable 失败：" + file, error);
            return null;
        }
    }

    /** 当前手写子集（{@code <shape>}）之外的 xml drawable 根标签。 */
    static boolean isUnsupportedXmlDrawableRoot(String root) {
        if (root == null) {
            return false;
        }
        switch (root) {
            case "vector":
            case "animated-vector":
            case "selector":
            case "layer-list":
            case "ripple":
            case "inset":
            case "clip":
            case "scale":
            case "transition":
            case "level-list":
                return true;
            default:
                return false;
        }
    }

    /** bug 2.3 的可执行诊断文案（纯字符串，便于 JVM 单测）。 */
    static String vectorDrawableDiagnostic(String value, File file, String root) {
        return "工程 xml 图标 " + value + "（" + file.getAbsolutePath() + " 根节点 <" + root + ">）"
                + "无法用当前 res/ 合成表渲染（设备端没有 aapt2），图标会空白。"
                + "可执行修复：在 PC 上运行 tools/build-project-res.ps1 生成 build/project-res.apk"
                + "（宿主 Node UI 会自动挂载真资源 id，vector 由 framework 正常绘制）；"
                + "或把图标改成 <shape> / PNG。";
    }

    private final class Delegate extends LayoutInflaterDelegate.NoOp {
        @Override public View afterCreateView(InflateContext context, View view, Node node, String name,
                                              ViewGroup parent, HashMap<String, String> attrs) {
            bindPendingRoots(view);
            return view;
        }
        @Override public boolean beforeApplyAttribute(InflateContext context, ViewInflater<View> inflater, View view,
                String ns, String name, String value, ViewGroup parent, Map<String, String> attrs) {
            Frame frame = roots.get(view);
            if (frame == null) return false;
            registration.checkOpen();
            if (!registration.callbacks.hasAttribute(frame.token, name)) return false;
            registration.callbacks.setAttribute(frame.token, name, value);
            registration.checkOpen();
            return true;
        }
    }
}
