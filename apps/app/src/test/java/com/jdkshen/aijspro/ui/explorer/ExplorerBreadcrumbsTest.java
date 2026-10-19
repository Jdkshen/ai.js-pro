package com.jdkshen.aijspro.ui.explorer;
import java.io.File;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExplorerBreadcrumbsTest {
    private final String storage = new File(System.getProperty("java.io.tmpdir"), "breadcrumb-test").getAbsolutePath();
    private final String scripts = new File(storage, "scripts").getPath();
    @Test public void deepProjectKeepsRealAncestorTargets() throws Exception {
        String project = new File(scripts, "group/project").getPath();
        List<ExplorerBreadcrumbs.Segment> nodes = ExplorerBreadcrumbs.build(scripts, storage, project);
        assertEquals(4, nodes.size());
        // 显示文案应为：内部存储 › 脚本 › group › project
        assertEquals("内部存储", nodes.get(0).label);
        assertEquals("脚本", nodes.get(1).label);
        assertEquals("project", nodes.get(3).label);
        assertEquals(new File(project).getCanonicalPath(), nodes.get(3).path);
        assertEquals(new File(scripts, "group").getCanonicalPath(), nodes.get(2).path);
    }
    @Test public void siblingPrefixDoesNotBecomeScriptsChild() throws Exception {
        String other = new File(storage, "scripts-other/project").getPath();
        List<ExplorerBreadcrumbs.Segment> nodes = ExplorerBreadcrumbs.build(scripts, storage, other);
        // 不在脚本目录下：内部存储 › scripts-other › project（没有“脚本”一级）
        assertEquals("内部存储", nodes.get(0).label);
        assertEquals(new File(storage).getCanonicalPath(), nodes.get(0).path);
        assertEquals("scripts-other", nodes.get(1).label);
        assertEquals(new File(other).getCanonicalPath(), nodes.get(2).path);
    }
    @Test public void outsideStorageUsesActualDestination() throws Exception {
        String other = new File(storage).getParentFile().getAbsolutePath();
        List<ExplorerBreadcrumbs.Segment> nodes = ExplorerBreadcrumbs.build(scripts, storage, other);
        assertEquals(new File(other).getCanonicalPath(), nodes.get(nodes.size()-1).path);
    }
}
