package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;

import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.InflateContext;
import com.stardust.autojs.core.ui.inflater.LayoutInflaterDelegate;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.ViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.ViewGroupInflater;
import com.stardust.autojs.core.ui.inflater.util.Ids;
import com.stardust.autojs.core.ui.xml.XmlConverter;

import org.json.JSONObject;
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
    private static final ThreadLocal<Context> CURRENT_CONTEXT = new ThreadLocal<>();
    static Context currentContext() { return CURRENT_CONTEXT.get(); }
    public interface Callbacks {
        String prepareXml(String xml);
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
    }
    private final Registration registration;
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
        // Preserve a custom tag's source attributes until its instance exists;
        // XML aliases such as size/w/id must not rename custom attr$ setters.
        return super.convertXml(context, registration.callbacks.prepareXml(xml));
    }

    private HashMap<String, String> originalAttributes(Node node) {
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
                    String uri = node.lookupNamespaceURI(prefix);
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
        try { return super.inflate(context, NodeUiHost.normalizeInlineLayoutXml(xml), parent, attach); }
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

    private View inflateWidget(InflateContext context, Node node, ViewGroup parent, boolean attach) {
        registration.checkOpen();
        if (frames.size() >= 32) throw new InflateException("Widget nesting exceeds 32 levels");
        Frame frame = new Frame();
        frame.token = registration.next.incrementAndGet();
        frames.push(frame);
        boolean success = false;
        try {
            HashMap<String, String> original = originalAttributes(node);
            Object rendered = registration.callbacks.render(frame.token, node.getNodeName(), getContext(),
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
                bindPendingRoots(root);
            } else throw new InflateException("Widget.render must return XML or an Android View");
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
            HashMap<String, String> external = nativeAttributes(node, original, frame, custom);
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
        @Override public View beforeInflateView(InflateContext context, Node node, ViewGroup parent, boolean attach) {
            registration.checkOpen();
            if (!frames.isEmpty() && frames.peek().waitingRoot && frames.peek().rootNode == null) {
                frames.peek().rootNode = node;
                frames.peek().internal = originalAttributes(node);
            }
            return registration.supports(node.getNodeName()) ? inflateWidget(context, node, parent, attach) : null;
        }
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
