package com.stardust.autojs.sample;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mozilla.javascript.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用与运行时同一份 Rhino 逐文件编译内置 Rhino 样例，确保它们**至少能解析**。
 *
 * <p>写它的原因：Rhino 1.7.7 不支持 ES6 模板字符串（反引号）与对象字面量简写，
 * 样例里只要出现一次，脚本一开始就报 `illegal character` / `invalid object initializer`。
 * 2026-09-15 实测时全仓库有 7 个 Rhino 样例是这种「一运行就报错」的状态
 * （用户就是在 OCR 样例上踩到的）。
 *
 * <p>只检查 Rhino 样例：带 `@engine quickjs` 标记的文件允许 ES6 语法，跳过。
 */
public class RhinoSampleSyntaxTest {

    /** 相对 engine 模块目录（Gradle 单测的工作目录就是模块目录）。 */
    private static final String SAMPLE_DIR = "../../apps/app/src/main/assets/sample";

    @Test
    public void rhinoSamplesShouldCompile() throws IOException {
        Path root = new File(SAMPLE_DIR).getCanonicalFile().toPath();
        assertTrue("找不到样例目录：" + root, Files.isDirectory(root));

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js"))
                    // Vue 的第三方打包产物不是脚本样例
                    .filter(path -> !path.toString().contains("Vue2+Vant"))
                    .forEach(files::add);
        }

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String header = text.length() > 200 ? text.substring(0, 200) : text;
            if (header.contains("@engine") && header.contains("quickjs")) {
                continue;
            }
            checked++;
            String relative = root.relativize(file).toString().replace('\\', '/');
            Context context = Context.enter();
            try {
                // 与运行时一致：RhinoJavaScriptEngine 设的是 Context.VERSION_ES6
                context.setLanguageVersion(Context.VERSION_ES6);
                context.compileString(text, relative, 1, null);
            } catch (RuntimeException e) {
                // EvaluatorException（语法错误）也是 RuntimeException
                failures.add(relative + " :: " + firstLine(e.getMessage()));
            } finally {
                Context.exit();
            }
        }

        assertTrue("检查了 " + checked + " 个 Rhino 样例，其中 " + failures.size()
                        + " 个有语法错误（Rhino 1.7.7 不支持模板字符串/对象简写）：\n  "
                        + String.join("\n  ", failures),
                failures.isEmpty());
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(no message)";
        }
        int index = message.indexOf('\n');
        return index < 0 ? message : message.substring(0, index);
    }
}
