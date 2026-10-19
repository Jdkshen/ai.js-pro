package com.stardust.autojs.rhino;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用 R8 的 D8 把 jar 里的 class 文件 dex 化。
 *
 * <p>旧实现使用 dx（{@code com.android.dx.command.dexer.Main}，本仓库为 1.11），
 * 它实测只认识 class major &lt;= 51（Java 7）；Java 8/11/17 编译出的 jar
 * （major 52/55/61）会直接失败，进而让 {@code loadDex} 抛 FileNotFoundException。
 *
 * <p><b>运行环境</b>：D8 的公开 API 依赖 {@code java.nio.file}，Android 8.0
 * （API 26）起才可用，因此调用方必须先做版本判断；低版本仍回退 dx。
 *
 * <p>该类不引用任何 Android 运行时 API（只用到编译期注解），方便在 JVM 上直接单测。
 */
@RequiresApi(26)
final class D8JarDexer {

    /**
     * D8 的 desugaring 目标，与 App 的 minSdk 保持一致。
     */
    private static final int MIN_API = 21;

    private D8JarDexer() {
    }

    /**
     * 把 jar dex 到 outputDir，通常生成 classes.dex（类太多时会有 classes2.dex...）。
     *
     * @throws IOException D8 不可用、jar 损坏或 class 版本不受支持
     */
    static void dex(File jar, File outputDir) throws IOException {
        if (jar == null || !jar.isFile()) {
            throw new FileNotFoundException("jar does not exist: " + jar);
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IOException("cannot create D8 output dir: " + outputDir);
        }

        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        DiagnosticsHandler diagnostics = new DiagnosticsHandler() {
            @Override
            public void error(Diagnostic diagnostic) {
                String message = diagnostic.getDiagnosticMessage();
                errors.add(message == null ? String.valueOf(diagnostic) : message);
            }

            @Override
            public void warning(Diagnostic diagnostic) {
                // 没有配置 library classpath 时，D8 会对每个缺失的 Java 类型打一条
                // warning（例如 java.io.Writer），但 dex 仍能正常生成；忽略以免刷 logcat。
            }

            @Override
            public void info(Diagnostic diagnostic) {
                // 不需要 D8 的统计信息。
            }
        };

        try {
            D8Command.Builder builder = D8Command.builder(diagnostics);
            builder.addProgramFiles(jar.toPath());
            builder.setOutput(outputDir.toPath(), OutputMode.DexIndexed);
            builder.setMinApiLevel(MIN_API);
            D8.run(builder.build());
        } catch (CompilationFailedException e) {
            throw new IOException("D8 dex 失败: " + summarize(errors), e);
        } catch (RuntimeException e) {
            throw new IOException("D8 运行失败: " + summarize(errors), e);
        } catch (LinkageError e) {
            throw new IOException("当前设备不支持 D8（需要 Android 8.0 / API 26 及以上）: " + e, e);
        }
    }

    private static String summarize(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "未知错误（D8 没有输出诊断信息）";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(errors.size(), 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(errors.get(i));
        }
        if (errors.size() > count) {
            builder.append("; 其余 ").append(errors.size() - count).append(" 条已省略");
        }
        return builder.toString();
    }
}
