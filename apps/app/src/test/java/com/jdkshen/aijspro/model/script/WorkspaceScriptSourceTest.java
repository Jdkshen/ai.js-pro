package com.jdkshen.aijspro.model.script;

import com.stardust.autojs.script.AutoFileSource;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import org.json.JSONArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class WorkspaceScriptSourceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void routesScriptsRecordingsAndResourcesSeparately() throws Exception {
        assertTrue(WorkspaceScriptSource.create(new File("entry.MJS")) instanceof JavaScriptFileSource);
        assertTrue(WorkspaceScriptSource.create(new File("record.auto")) instanceof AutoFileSource);
        for (String name : new String[]{"photo.png", "settings.json", "native.so", "classes.dex", "app.apk", "readme.md"}) {
            assertFalse(WorkspaceScriptSource.runnable(new File(name)));
            try { WorkspaceScriptSource.create(new File(name)); fail(name); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
    }
    @Test public void xmlIsQuotedAsDataAndKeepsItsName() throws Exception {
        File file = temporary.newFile("布局.xml");
        String xml = "<vertical><text text=\"引号 ' ` ${value} \\\\ \"/></vertical>\n";
        Files.write(file.toPath(), ("\uFEFF" + xml).getBytes(StandardCharsets.UTF_8));
        JavaScriptSource source = (JavaScriptSource) WorkspaceScriptSource.create(file);
        String code = source.getScript();
        assertEquals("布局.xml", source.getName());
        assertTrue(code.startsWith("\"ui\";\nui.layout("));
        assertEquals(xml, new JSONArray("[" + code.substring(code.indexOf('(') + 1, code.lastIndexOf(')')) + "]").getString(0));
    }
    @Test public void hugeXmlIsRejectedBeforeExecution() throws Exception {
        File file = temporary.newFile("large.xml");
        Files.write(file.toPath(), new byte[1024 * 1024 + 1]);
        try { WorkspaceScriptSource.create(file); fail("oversize"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("1MiB")); }
    }
}
