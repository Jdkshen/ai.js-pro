package com.stardust.autojs.engine.module;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** `.4` 的 require 报错：Rhino 原生 Require.call 在非 ModuleScope 下不解析相对 ID → 这里锁住兜底重写。 */
public class PathAwareRequireTest {

    @Test
    public void relativeIdIsRewrittenToAbsolutePathUnderBaseDir() throws Exception {
        File base = new File("build/tmp/pathaware/base");
        base.mkdirs();
        String rewritten = PathAwareRequire.rewriteRelativeId(base.getAbsolutePath(), "./脚本.js");
        assertTrue("应变成绝对路径：" + rewritten, new File(rewritten).isAbsolute());
        assertEquals(new File(base, "脚本.js").getCanonicalPath(), rewritten);
    }

    @Test
    public void parentRelativeIdIsCanonicalised() throws Exception {
        File base = new File("build/tmp/pathaware/base/sub");
        base.mkdirs();
        String rewritten = PathAwareRequire.rewriteRelativeId(base.getAbsolutePath(), "../lib/x.js");
        assertEquals(new File(base, "../lib/x.js").getCanonicalPath(), rewritten);
    }

    @Test
    public void bareIdsAndMissingBaseAreUntouched() {
        assertEquals("jvm-npm.js", PathAwareRequire.rewriteRelativeId("/tmp", "jvm-npm.js"));
        assertEquals("./x.js", PathAwareRequire.rewriteRelativeId(null, "./x.js"));
        assertEquals("./x.js", PathAwareRequire.rewriteRelativeId("", "./x.js"));
    }

    @Test
    public void spacesAreEncodedAfterRewrite() {
        assertEquals("a%20b.js", PathAwareRequire.encodeModuleId("a b.js"));
        String rewritten = PathAwareRequire.rewriteRelativeId("/tmp", "./a b.js");
        assertTrue(rewritten.contains("a b.js"));
    }
}
