package com.jdkshen.aijspro.flutterpoc;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PocProjectEntryTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private File file(String path) throws IOException {
        File file = new File(temp.getRoot(), path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), "// fixture".getBytes(StandardCharsets.UTF_8));
        return file.getCanonicalFile();
    }

    private JSONObject frontend() throws Exception {
        return new JSONObject().put("main", "dist/main.node.js")
                .put("scripts", new JSONObject().put("build-apk-pre-prepare", "sh build.sh"));
    }

    private void missing(JSONObject config) throws Exception {
        try { PocProjectEntry.resolve(temp.getRoot(), config); fail("Must reject a missing entry"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("入口文件不存在")); }
    }

    @Test public void runsDeclaredBundleWhenPresent() throws Exception {
        File bundle = file("dist/main.node.js");
        file("main.node.js"); file("package.json");
        assertEquals(bundle, PocProjectEntry.resolve(temp.getRoot(), frontend()));
    }

    @Test public void runsFrontendSourceBeforeApkBuildAndPreservesConfig() throws Exception {
        File source = file("main.node.js"); file("package.json");
        JSONObject config = frontend();
        assertEquals(source, PocProjectEntry.resolve(temp.getRoot(), config));
        assertEquals("dist/main.node.js", config.getString("main"));
    }

    @Test public void keepsOrdinaryAndNestedEntries() throws Exception {
        File ordinary = file("main.js");
        assertEquals(ordinary, PocProjectEntry.resolve(temp.getRoot(), new JSONObject()));
        File nested = file("src/start.js");
        assertEquals(nested, PocProjectEntry.resolve(temp.getRoot(), new JSONObject().put("main", "src/start.js")));
    }

    @Test public void doesNotGuessMainJsForMissingNodeSource() throws Exception {
        file("main.js"); file("package.json");
        missing(frontend());
    }

    @Test public void requiresBuildHookAndNodeProject() throws Exception {
        file("main.node.js");
        missing(frontend());
        file("package.json");
        missing(frontend().put("scripts", new JSONObject()));
    }

    @Test public void doesNotSubstituteAnotherNodeEntryOrRhinoSource() throws Exception {
        file("main.node.js"); file("main.js"); file("package.json");
        missing(frontend().put("main", "dist/other.node.js"));
        missing(frontend().put("main", "dist/main.js"));
    }

    @Test public void rejectsBlankOrNonStringMain() throws Exception {
        file("main.js");
        for (Object main : new Object[]{"", 12, new JSONObject()}) {
            try { PocProjectEntry.resolve(temp.getRoot(), new JSONObject().put("main", main)); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("非空字符串")); }
        }
    }

    @Test public void doesNotStartDevelopmentForAnIncompleteRelease() throws Exception {
        file("main.node.js"); file("package.json");
        for (String key : new String[]{"isRelease", "is_release", "release"}) {
            missing(frontend().put("buildInfo", new JSONObject().put(key, true)));
        }
    }

    @Test public void everyBundledProjectHasARunnableIdeEntry() throws Exception {
        File samples = new File("src/main/assets/sample");
        assertTrue(samples.isDirectory());
        try (Stream<Path> paths = Files.walk(samples.toPath())) {
            List<Path> configs = paths.filter(path -> path.getFileName().toString().equals("project.json"))
                    .collect(Collectors.toList());
            assertTrue(!configs.isEmpty());
            for (Path path : configs) {
                JSONObject config = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                assertTrue(path.toString(), PocProjectEntry.resolve(path.getParent().toFile(), config).isFile());
            }
        }
    }
}
