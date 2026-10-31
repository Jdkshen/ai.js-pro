package com.jdkshen.aijspro.autojs.build;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Node24PackagingAssetsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final String[] FILES = {"assets/node24/prelude.js", "assets/node24/ui.js",
            "assets/source-engine-upgrade/node24/arm64-v8a/runtime.json",
            "assets/source-engine-upgrade/node24/arm64-v8a/libnode.so",
            "assets/source-engine-upgrade/node24/arm64-v8a/libc++_shared.so",
            "assets/source-engine-upgrade/node24/arm64-v8a/libajsnode24bridge.so"};

    @Test public void nodeProjectAndExplicitFeaturesIncludeRuntime() throws Exception {
        assertTrue(Node24PackagingAssets.requiresRuntime(true, null));
        assertTrue(Node24PackagingAssets.requiresRuntime(false, new JSONObject("{\"type\":\"node\"}")));
        assertTrue(Node24PackagingAssets.requiresRuntime(false, new JSONObject("{\"features\":{\"nodejs\":\"enabled\"}}")));
        assertTrue(Node24PackagingAssets.requiresRuntime(false, new JSONObject("{\"features\":{\"nodejs\":\"auto\"}}")));
        assertFalse(Node24PackagingAssets.requiresRuntime(false, new JSONObject("{\"features\":{\"nodejs\":\"disabled\"}}")));
    }

    /**
     * 「只是开了 nodejs 特性位」与「明确是 Node 工程」要分开：前者运行资产不完整只告警
     * （入口可能还是 Rhino/QuickJS），后者必须按打包错误处理（见
     * {@code Node24TemplateConsistency.verifyWorkspace} 的 strict 参数）。
     */
    @Test public void distinguishesNodeProjectsFromFeatureFlags() throws Exception {
        assertTrue(Node24PackagingAssets.isNodeProject(new JSONObject("{\"type\":\"node\"}")));
        assertTrue(Node24PackagingAssets.isNodeProject(new JSONObject("{\"type\":\"nodejs\"}")));
        assertFalse(Node24PackagingAssets.isNodeProject(new JSONObject("{\"features\":{\"nodejs\":\"enabled\"}}")));
        assertFalse(Node24PackagingAssets.isNodeProject(new JSONObject("{}")));
        assertFalse(Node24PackagingAssets.isNodeProject(null));
    }

    private File host(boolean complete, boolean traversal) throws IOException {
        File file = tmp.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < FILES.length - (complete ? 0 : 1); i++) {
                out.putNextEntry(new ZipEntry(FILES[i]));
                out.write(("host-" + i).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            if (traversal) {
                out.putNextEntry(new ZipEntry("assets/node24/../../escape"));
                out.write(1);
                out.closeEntry();
            }
        }
        return file;
    }

    @Test public void replacesOldPartialRuntimeWithMatchingHostFiles() throws Exception {
        File workspace = tmp.newFolder();
        File old = new File(workspace, FILES[3]);
        old.getParentFile().mkdirs();
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        assertEquals(FILES.length, Node24PackagingAssets.copyFromHost(host(true, false), workspace));
        assertEquals("host-3", new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
        assertEquals("host-2", new String(Files.readAllBytes(new File(workspace, FILES[2]).toPath()), StandardCharsets.UTF_8));
    }

    @Test public void failsBeforeCopyIfHostIsMissingRuntime() throws Exception {
        File workspace = tmp.newFolder();
        try { Node24PackagingAssets.copyFromHost(host(false, false), workspace); fail("missing runtime accepted"); }
        catch (IOException expected) { assertFalse(new File(workspace, FILES[0]).exists()); }
    }

    @Test public void rejectsArchiveTraversal() throws Exception {
        File workspace = tmp.newFolder();
        try { Node24PackagingAssets.copyFromHost(host(true, true), workspace); fail("traversal accepted"); }
        catch (IOException expected) { assertFalse(new File(workspace, "escape").exists()); }
    }
}
