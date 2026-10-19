package org.autojs.source.engine.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class Node24ConsoleLineTest {
    @Test public void decodesAllLevelsAndPreservesUnicodeAndColons() {
        String levels = "VDIWEA";
        for (int i = 0; i < levels.length(); i++) {
            Node24ConsoleLine line = Node24ConsoleLine.decode("\u001eAJSLOG1:" + levels.charAt(i) + ":中文😀: value");
            assertEquals(i + 2, line.level);
            assertEquals("中文😀: value", line.text);
        }
    }

    @Test public void rawOrMalformedStdoutIsNeverDiscarded() {
        for (String raw : new String[]{"normal stdout", "\u001eAJSLOG1:", "\u001eAJSLOG1:X:data", "\u001eAJSLOG1:W?data"}) {
            Node24ConsoleLine line = Node24ConsoleLine.decode(raw);
            assertEquals(3, line.level);
            assertEquals(raw, line.text);
        }
    }

    @Test public void emptyAndTruncatedBodiesStillHaveAValidLevel() {
        assertEquals("", Node24ConsoleLine.decode("\u001eAJSLOG1:E:").text);
        assertEquals(6, Node24ConsoleLine.decode("\u001eAJSLOG1:E:truncated").level);
        assertEquals("", Node24ConsoleLine.decode(null).text);
    }
}
