package com.stardust.autojs.engine.node24;

import com.stardust.autojs.core.ui.xml.XmlConverter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Per-inflater source identity survives native alias conversion and late registration. */
final class NodeUiXmlSource {
    private static final String MARKER = "android:__aijsSourceNode";
    private static final String DATA = NodeUiXmlSource.class.getName();
    private static final String PLACEHOLDER = "aijs-widget-node";
    static final class Input {
        long id;
        String tag;
        final HashMap<String, String> attributes = new HashMap<>();
        RuntimeException conversionError;
    }
    private long nextId;
    private final Map<Long, Input> inputs = new HashMap<>();

    String convert(String xml, Predicate<String> registered, UnaryOperator<String> normalize) throws Exception {
        return XmlConverter.convertToAndroidLayout(xml, new XmlConverter.NodeAdapter() {
            final Map<Node, Input> sources = new IdentityHashMap<>();
            @Override public Node prepare(Node source) {
                Input input = new Input(); input.id = ++nextId; input.tag = source.getNodeName();
                for (int i = 0; i < source.getAttributes().getLength(); i++) {
                    Node attr = source.getAttributes().item(i);
                    input.attributes.put(attr.getNodeName(), attr.getNodeValue());
                }
                inputs.put(input.id, input); sources.put(source, input);
                return opening(source, input, registered.test(input.tag) ? PLACEHOLDER : normalize.apply(input.tag));
            }
            @Override public Node onFailure(Node source, RuntimeException error) {
                Input input = sources.get(source); input.conversionError = error;
                return opening(source, input, PLACEHOLDER);
            }
        });
    }

    private Node opening(Node source, Input input, String tag) {
        Element node = source.getOwnerDocument().createElement(tag);
        for (Map.Entry<String, String> attr : input.attributes.entrySet()) {
            String name = attr.getKey();
            if (!PLACEHOLDER.equals(tag) || name.equals("xmlns") || name.startsWith("xmlns:"))
                node.setAttribute(name, attr.getValue());
        }
        node.setAttribute(MARKER, Long.toString(input.id));
        return node;
    }

    Input take(Node node) {
        Input input = (Input) node.getUserData(DATA);
        if (input != null) return input;
        Node marker = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(MARKER);
        if (marker == null) return null;
        input = inputs.remove(Long.parseLong(marker.getNodeValue()));
        if (input == null) throw new IllegalStateException("Expired Widget XML source");
        node.getAttributes().removeNamedItem(MARKER);
        node.setUserData(DATA, input, null);
        return input;
    }
}
