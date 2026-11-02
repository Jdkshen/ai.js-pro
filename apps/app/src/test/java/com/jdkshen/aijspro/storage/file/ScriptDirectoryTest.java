package com.jdkshen.aijspro.storage.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class ScriptDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void absolutePathDoesNotGetExternalRootPrefixedAgain() throws Exception {
        File external = temporary.newFolder("external");
        File target = new File(temporary.getRoot(), "another/脚本 空格");
        assertEquals(target.getCanonicalFile(), ScriptDirectory.prepare(" " + target.getPath() + " ", external, null));
        assertTrue(target.isDirectory());
        assertEquals(target.getCanonicalFile(), ScriptDirectory.resolve(target.getPath(), external));
    }
    @Test public void oldRelativePreferenceStillResolvesUnderStorage() throws Exception {
        File external = temporary.newFolder("storage");
        assertEquals(new File(external, "脚本").getCanonicalFile(), ScriptDirectory.resolve("脚本", external));
        assertEquals(new File(external, "new").getCanonicalFile(), ScriptDirectory.prepare("old/../new", external, null));
        assertEquals(new File(external, "脚本").getCanonicalFile(), ScriptDirectory.resolvePreference("/脚本/", external, false));
        assertEquals(new File(external, "custom/nested").getCanonicalFile(), ScriptDirectory.resolvePreference("/custom/nested/", external, false));
        File absolute = new File(temporary.getRoot(), "outside").getCanonicalFile();
        assertEquals(absolute, ScriptDirectory.resolvePreference(absolute.getPath(), external, true));
    }
    @Test public void badInputsDoNotReplaceAFileOrMakeSamplesWritable() throws Exception {
        File external = temporary.newFolder("storage");
        File samples = temporary.newFolder("samples");
        File file = temporary.newFile("existing.js");
        for (String input : new String[]{"", " ", "content://documents/123", "x\0y", file.getPath(), new File(samples, "nested").getPath()}) {
            try { ScriptDirectory.prepare(input, external, samples); fail("Accepted: " + input); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
        assertTrue(file.isFile());
        assertFalse(new File(samples, "nested").exists());
    }
}
