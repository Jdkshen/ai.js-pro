package com.stardust.autojs.engine.module;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.provider.SoftCachingModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

public class PathAwareRequireTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void nestedRelativeModulesWithSpacesLoadAndCache() throws Exception {
        Path root = temporary.newFolder("Rhino 引擎").toPath();
        Files.createDirectories(root.resolve("sub dir"));
        write(root.resolve("parent.js"), "module.exports = require('./sub dir/child');");
        write(root.resolve("sub dir/child.js"), "module.exports = require('../sibling file');");
        write(root.resolve("sibling file.js"), "module.exports = { value: 42 };");
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            install(cx, scope, root);
            Object result = cx.evaluateString(scope,
                    "require('parent').value + '/' + (require('parent') === require('sibling file'))",
                    "test", 1, null);
            assertEquals("42/true", Context.toString(result));
        } finally {
            Context.exit();
        }
    }

    @Test
    public void encodedRelativePathsStillRespectSandbox() throws Exception {
        Path root = temporary.newFolder("modules").toPath();
        write(root.resolve("parent.js"), "module.exports = require('../outside file');");
        write(root.getParent().resolve("outside file.js"), "module.exports = 99;");
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            install(cx, scope, root);
            try {
                cx.evaluateString(scope, "require('parent')", "test", 1, null);
                fail("Module escaped the sandbox");
            } catch (RuntimeException expected) {
                assertTrue(expected.getMessage().contains("not contained in sandbox"));
            }
        } finally {
            Context.exit();
        }
    }

    private static void install(Context cx, Scriptable scope, Path root) {
        UrlModuleSourceProvider source = new UrlModuleSourceProvider(Collections.singletonList(root.toUri()), null);
        new PathAwareRequire(cx, scope, new SoftCachingModuleScriptProvider(source)).install(scope);
    }

    private static void write(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
