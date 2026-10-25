package com.jdkshen.aijspro.engine;

import com.stardust.autojs.engine.node24.NodeUiProtocol;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.*;

/** Static callbacks and Activity state must share a renderer, while WorkManager stays in main. */
public class NodeUiProcessContractTest {
    private Element component(String tag, String name) throws Exception {
        File file = new File("src/main/AndroidManifest.xml");
        if (!file.isFile()) file = new File("apps/app/src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(file);
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (name.equals(attribute(node, "name"))) return node;
        }
        throw new AssertionError("Missing manifest component: " + name);
    }
    private String attribute(Element node, String name) {
        return node.getAttributeNS("http://schemas.android.com/apk/res/android", name);
    }
    @Test public void legacyUiAndDialogCallbacksShareAnIsolatedRenderer() throws Exception {
        Element service = component("service", NodeUiProtocol.SERVICE_CLASS);
        String process = attribute(service, "process");
        assertFalse("renderer cannot run in main", process.isEmpty());
        for (String name : new String[]{NodeUiProtocol.ACTIVITY_CLASS, "com.jdkshen.aijspro.engine.NodeUiDialogActivity"}) {
            Element activity = component("activity", name);
            assertEquals("static callbacks must remain in the service process", process, attribute(activity, "process"));
            assertEquals("false", attribute(activity, "exported"));
        }
        assertEquals("false", attribute(service, "exported"));
    }
    @Test public void workAndSettingsKeepTheirMainProcessBackend() throws Exception {
        assertNotEquals(NodeUiProtocol.SERVICE_CLASS, NodeUiProtocol.WORK_SERVICE_CLASS);
        Element work = component("service", NodeUiProtocol.WORK_SERVICE_CLASS);
        assertEquals("", attribute(work, "process"));
        assertEquals("false", attribute(work, "exported"));
    }
    @Test public void nativeJavaUiSharesItsOwnNodePoolSlot() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Element activity = component("activity", "com.jdkshen.aijspro.engine.NodeUiLocalActivity$Slot" + i);
            Element service = component("service", "org.autojs.source.engine.android.Node24PoolService" + i);
            assertEquals(":node24_" + i, attribute(activity, "process"));
            assertEquals(attribute(service, "process"), attribute(activity, "process"));
        }
    }
}
