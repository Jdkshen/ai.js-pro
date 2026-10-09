package com.stardust.autojs.engine.module;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.provider.ModuleSource;
import org.mozilla.javascript.commonjs.module.provider.ModuleSourceProvider;
import org.mozilla.javascript.commonjs.module.provider.SoftCachingModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

public class TemplateLiteralModuleSourceProviderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void nestedModulesCompileXmlTemplatesAndPreserveCaching() throws Exception {
        Path root = temporary.newFolder("Rhino 引擎").toPath();
        Files.createDirectories(root.resolve("sub dir"));
        Files.write(root.resolve("parent.js"),
                "module.exports = require('./sub dir/utils');".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("sub dir/utils.js"),
                ("var name = 'board'; module.exports = { xml: `<relative>\n"
                        + "<canvas id=\"${name}\" w=\"*\" h=\"*\"/>\n</relative>` };")
                        .getBytes(StandardCharsets.UTF_8));
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            ModuleSourceProvider source = new TemplateLiteralModuleSourceProvider(
                    new UrlModuleSourceProvider(Collections.singletonList(root.toUri()), null));
            new PathAwareRequire(cx, scope, new SoftCachingModuleScriptProvider(source)).install(scope);
            assertEquals("<relative>\n<canvas id=\"board\" w=\"*\" h=\"*\"/>\n</relative>",
                    Context.toString(cx.evaluateString(scope, "require('parent').xml", "test", 1, null)));
            assertEquals("true", Context.toString(cx.evaluateString(scope,
                    "require('parent') === require('parent')", "test", 1, null)));
        } finally {
            Context.exit();
        }
    }

    @Test
    public void preservesMetadataAndClosesOriginalReader() throws Exception {
        final boolean[] closed = {false};
        StringReader reader = new StringReader("module.exports = `value`;") {
            @Override public void close() { closed[0] = true; super.close(); }
        };
        Object security = new Object();
        Object validator = new Object();
        URI uri = URI.create("file:/modules/utils.js");
        URI base = URI.create("file:/modules/");
        ModuleSource result = TemplateLiteralModuleSourceProvider.preprocess(
                new ModuleSource(reader, security, uri, base, validator));
        assertTrue(closed[0]);
        assertSame(security, result.getSecurityDomain());
        assertSame(validator, result.getValidator());
        assertEquals(uri, result.getUri());
        assertEquals(base, result.getBase());
        assertEquals("module.exports = 'value';", read(result));
    }

    @Test
    public void missingAndUnchangedModulesPassThrough() throws Exception {
        assertNull(TemplateLiteralModuleSourceProvider.preprocess(null));
        assertSame(ModuleSourceProvider.NOT_MODIFIED,
                TemplateLiteralModuleSourceProvider.preprocess(ModuleSourceProvider.NOT_MODIFIED));
    }

    @Test
    public void unsupportedTemplatesRemainUnmodified() throws Exception {
        String script = "module.exports = tag`value`;";
        assertEquals(script, read(TemplateLiteralModuleSourceProvider.preprocess(
                new ModuleSource(new StringReader(script), null, null, null, null))));
    }

    @Test
    public void requiredModulesSupportObjectAndMethodShorthand() throws Exception {
        Path root = temporary.newFolder("object methods").toPath();
        Files.write(root.resolve("utils.js"),
                "var drawMatches='path'; module.exports={drawMatches, get(){return this.drawMatches;}};"
                        .getBytes(StandardCharsets.UTF_8));
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            ModuleSourceProvider source = new TemplateLiteralModuleSourceProvider(
                    new UrlModuleSourceProvider(Collections.singletonList(root.toUri()), null));
            new PathAwareRequire(cx, scope, new SoftCachingModuleScriptProvider(source)).install(scope);
            assertEquals("path", Context.toString(cx.evaluateString(scope,
                    "require('utils').get()", "test", 1, null)));
        } finally { Context.exit(); }
    }

    private static String read(ModuleSource source) throws Exception {
        StringBuilder text = new StringBuilder();
        try (java.io.Reader reader = source.getReader()) {
            int next;
            while ((next = reader.read()) != -1) text.append((char) next);
        }
        return text.toString();
    }
}
