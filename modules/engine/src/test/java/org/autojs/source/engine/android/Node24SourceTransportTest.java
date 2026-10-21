package org.autojs.source.engine.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class Node24SourceTransportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void largeUnicodeSourceRoundTripsAndDeletesTransportFile() throws Exception {
        String source = String.join("", java.util.Collections.nCopies(180000, "脚本😀"));
        File file = Node24SourceTransport.write(temp.getRoot(), source);
        assertEquals(source, Node24SourceTransport.read(temp.getRoot(), file.toString()));
        assertFalse(file.exists());
    }
    @Test public void rejectsFilesOutsideOwnedCache() throws Exception {
        File outside = temp.newFile("entry-outside.js");
        try { Node24SourceTransport.read(temp.getRoot(), outside.toString()); fail("escaped cache"); }
        catch (java.io.IOException expected) { assertTrue(outside.exists()); }
    }
}
