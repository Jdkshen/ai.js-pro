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
import java.io.StringReader;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

/** Per-inflation Widget stages. All calls happen inside the protected main-thread inflate. */
public final class NodeUiWidgetInflater extends DynamicLayoutInflater {
    private static final String TAG = "NodeUiWidgetInflater";
    private static final ThreadLocal<Context> CURRENT_CONTEXT = new ThreadLocal<>();

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
