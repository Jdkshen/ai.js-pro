package com.jdkshen.aijspro.storage.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class WorkspaceListingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void recursiveRegexSearchReachesNestedMatchesWithoutLosingFolders() throws Exception {
        File root = temporary.newFolder("scripts");
        File child = new File(root, "project"); assertTrue(child.mkdir());
        File match = new File(child, "MAIN.JS"); assertTrue(match.createNewFile());
        assertTrue(new File(child, "image.png").createNewFile());
        assertTrue(WorkspaceListing.read(root, "^main\\.js$", true, false, false, 20).isEmpty());
        assertEquals(match, WorkspaceListing.read(root, "^main\\.js$", true, true, false, 20).get(0));
        assertEquals(1, WorkspaceListing.read(root, "", false, true, false, 20).size());
    }
    @Test public void hiddenToggleNeverExposesInternalTrashAndTemporaryData() throws Exception {
        File root = temporary.newFolder("scripts");
        assertTrue(new File(root, ".gitignore").createNewFile());
        assertTrue(new File(root, ".autojs-glass-trash").mkdir());
        assertTrue(new File(root, ".flutter-copy").createNewFile());
        assertTrue(new File(root, "README").createNewFile());
        assertEquals(1, WorkspaceListing.read(root, "", false, false, false, 10).size());
        List<File> visible = WorkspaceListing.read(root, "", false, false, true, 10);
        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(file -> file.getName().equals(".gitignore")));
    }
    @Test public void invalidRegexAndExcessiveResultsReportErrorsRatherThanPartialSuccess() throws Exception {
        File root = temporary.newFolder("scripts");
        assertTrue(new File(root, "a.js").createNewFile());
        assertTrue(new File(root, "b.js").createNewFile());
        try { WorkspaceListing.read(root, "[", true, true, false, 20); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("正则")); }
        try { WorkspaceListing.read(root, "", false, false, false, 1); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("超过")); }
    }
}
